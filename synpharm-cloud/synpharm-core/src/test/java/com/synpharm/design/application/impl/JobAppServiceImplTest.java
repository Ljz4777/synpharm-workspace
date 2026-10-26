package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.JobResponse;
import com.synpharm.design.application.JobAppService;
import com.synpharm.design.domain.model.DesignJob;
import com.synpharm.design.domain.model.JobStatus;
import com.synpharm.design.domain.model.JobType;
import com.synpharm.design.infrastructure.persistence.DesignJobMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务用例测试。
 *
 * <p>这里钉的是 {@code design_job} 同时承担的两个职责：
 * <ol>
 *   <li><b>幂等键存储</b> —— 同键未终态必须 409（重提会产生第二份工作），已终态才允许复用；
 *       并发时唯一索引是最后一道闸，撞索引后要改用胜出的那条，而不是把 500 抛给用户。</li>
 *   <li><b>异步任务进度载体</b> —— 回报按 {@code jobNo} 定位、终态忽略重投；查不到 jobNo
 *       必须抛错（这是重试/死信的触发条件，静默丢弃会让任务永远 PENDING）。</li>
 * </ol>
 *
 * <p>另外锁死两条容易写反的语义：{@code markDispatchFailure} 只登记「未派发」而<b>不</b>置
 * FAILED；{@code progress} 由 {@code done/total} 推导、两位小数且上限 100（total 未知时保持 0）。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;

    private static final long INSERTED_ID = 900L;

    private static final String JOB_NO = "J261014153045A7F3";

    /** J + yyMMddHHmmss + 4 位大写十六进制 */
    private static final String JOB_NO_PATTERN = "J\\d{12}[0-9A-F]{4}";

    @Mock private DesignJobMapper jobMapper;

    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private JobAppServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 靠 TableInfo 把方法引用翻成列名；单测里没有 Spring/MyBatis 上下文，
        // 手工注册一次，才能真正断言 AppService 下发到 SQL 的过滤条件（而不是只断言「调过一次」）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DesignJob.class);
        CurrentUserTestSupport.login(ME);
        // MyBatis-Plus 会在 insert 后把自增主键回填到实体上，mock 必须模拟这一点，
        // 否则后续按 id 组装响应会拿到 null
        when(jobMapper.insert(any(DesignJob.class))).thenAnswer(invocation -> {
            DesignJob inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(INSERTED_ID);
            }
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static DesignJob job(Long id, Long ownerId, JobStatus status) {
        DesignJob j = new DesignJob();
        j.setId(id);
        j.setJobNo(JOB_NO);
        j.setJobType(JobType.GENERATION.name());
        j.setUserId(ownerId);
        j.setStatus(status.name());
        j.setProgress(BigDecimal.ZERO);
        j.setTotalCount(0);
        j.setDoneCount(0);
        j.setRetryCount(0);
        return j;
    }

    private static DesignJob jobWithCounters(Long id, Long ownerId, JobStatus status, Integer total, Integer done) {
        DesignJob j = job(id, ownerId, status);
        j.setTotalCount(total);
        j.setDoneCount(done);
        return j;
    }

    // ------------------------------------------------------------- createOrReuse

    @Test
    @DisplayName("登记任务：无幂等键 -> 新建 PENDING，进度/计数/重试归零，queueName 取自该类型的路由键")
    void createRegistersPendingJobWithoutIdempotentKey() {
        boolean[] existing = new boolean[1];

        JobAppService.CreatedJob created = service.createOrReuse(JobType.GENERATION, 7L, 8L, 9L,
                "GENERATION_RUN", 11L, Map.of("roundId", 8L), null, existing);

        assertThat(existing[0]).isFalse();
        assertThat(created.id()).isEqualTo(INSERTED_ID);
        assertThat(created.no()).matches(JOB_NO_PATTERN);
        assertThat(created.job().getStatus()).isEqualTo(JobStatus.PENDING.name());

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).insert(captor.capture());
        DesignJob inserted = captor.getValue();
        assertThat(inserted.getJobNo()).matches(JOB_NO_PATTERN);
        assertThat(inserted.getJobType()).isEqualTo(JobType.GENERATION.name());
        assertThat(inserted.getUserId()).isEqualTo(ME);
        assertThat(inserted.getProjectId()).isEqualTo(7L);
        assertThat(inserted.getRoundId()).isEqualTo(8L);
        assertThat(inserted.getCandidateId()).isEqualTo(9L);
        assertThat(inserted.getBizType()).isEqualTo("GENERATION_RUN");
        assertThat(inserted.getBizId()).isEqualTo(11L);
        assertThat(inserted.getParamsJson()).contains("\"roundId\":8");
        assertThat(inserted.getStatus()).isEqualTo(JobStatus.PENDING.name());
        assertThat(inserted.getProgress()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inserted.getTotalCount()).isZero();
        assertThat(inserted.getDoneCount()).isZero();
        assertThat(inserted.getRetryCount()).isZero();
        assertThat(inserted.getQueueName()).isEqualTo(JobType.GENERATION.routingKey());
        assertThat(inserted.getQueueName()).isEqualTo("design.generate");
        // 无幂等键时不该去查幂等键
        verify(jobMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("登记任务：非异步类型（STRUCTURE_FETCH）queueName 留空，且不会触发 routingKey() 报错")
    void createLeavesQueueNameNullForNonAsyncType() {
        boolean[] existing = new boolean[1];

        JobAppService.CreatedJob created = service.createOrReuse(JobType.STRUCTURE_FETCH, 7L, null, null,
                null, null, null, null, existing);

        assertThat(existing[0]).isFalse();
        assertThat(created.id()).isEqualTo(INSERTED_ID);
        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).insert(captor.capture());
        assertThat(captor.getValue().getQueueName()).isNull();
    }

    @Test
    @DisplayName("登记任务：幂等键命中未终态任务 -> 409 BUSINESS_CONFLICT，details 带 existingId/existingJobNo/existingStatus")
    void createRejectsDuplicateWhilePreviousJobStillAlive() {
        when(jobMapper.selectOne(any())).thenReturn(job(7L, ME, JobStatus.RUNNING));

        assertThatThrownBy(() -> service.createOrReuse(JobType.GENERATION, null, null, null,
                null, null, null, "K-1", new boolean[1]))
                .isInstanceOf(BizException.class)
                .satisfies(thrown -> {
                    BizException ex = (BizException) thrown;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_CONFLICT);
                    assertThat(ex.getMessage()).contains("Idempotency-Key");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) ex.getDetails();
                    assertThat(details)
                            .containsEntry("existingId", 7L)
                            .containsEntry("existingJobNo", JOB_NO)
                            .containsEntry("existingStatus", JobStatus.RUNNING.name());
                });
        verify(jobMapper, never()).insert(any(DesignJob.class));
    }

    @Test
    @DisplayName("登记任务：幂等键命中已终态任务 -> 复用同一 jobId，existing[0]=true，不再 insert")
    void createReusesTerminalJobWithoutInserting() {
        when(jobMapper.selectOne(any())).thenReturn(job(7L, ME, JobStatus.SUCCEEDED));
        boolean[] existing = new boolean[1];

        JobAppService.CreatedJob created = service.createOrReuse(JobType.GENERATION, null, null, null,
                null, null, null, "K-1", existing);

        assertThat(existing[0]).isTrue();
        assertThat(created.id()).isEqualTo(7L);
        assertThat(created.no()).isEqualTo(JOB_NO);
        assertThat(created.job().getStatus()).isEqualTo(JobStatus.SUCCEEDED.name());
        verify(jobMapper, never()).insert(any(DesignJob.class));
    }

    @Test
    @DisplayName("登记任务：并发撞唯一索引 -> 复用胜出的任务，不再把异常抛给调用方")
    void createReusesWinnerOnDuplicateKeyRace() {
        when(jobMapper.selectOne(any())).thenReturn(null, job(6L, ME, JobStatus.PENDING));
        when(jobMapper.insert(any(DesignJob.class)))
                .thenThrow(new DuplicateKeyException("uk_idempotent_key"));
        boolean[] existing = new boolean[1];

        JobAppService.CreatedJob created = service.createOrReuse(JobType.GENERATION, null, null, null,
                null, null, null, "K-1", existing);

        assertThat(existing[0]).isTrue();
        assertThat(created.id()).isEqualTo(6L);
        verify(jobMapper).insert(any(DesignJob.class));
    }

    @Test
    @DisplayName("登记任务：撞唯一索引但随后查不到 -> 原样抛出 DuplicateKeyException")
    void createRethrowsDuplicateKeyWhenWinnerStillMissing() {
        when(jobMapper.selectOne(any())).thenReturn(null, (DesignJob) null);
        when(jobMapper.insert(any(DesignJob.class)))
                .thenThrow(new DuplicateKeyException("uk_idempotent_key"));

        assertThatThrownBy(() -> service.createOrReuse(JobType.GENERATION, null, null, null,
                null, null, null, "K-1", new boolean[1]))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------------ get

    @Test
    @DisplayName("详情：入参为数字 -> 按主键查")
    void getAcceptsNumericJobId() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, ME, JobStatus.PENDING));

        JobResponse response = service.get("42");

        assertThat(response.getJobId()).isEqualTo(42L);
        assertThat(response.getJobNo()).isEqualTo(JOB_NO);
        assertThat(response.getStatus()).isEqualTo(JobStatus.PENDING.name());
        verify(jobMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("详情：入参为 jobNo -> 按编号查")
    void getAcceptsJobNo() {
        when(jobMapper.selectOne(any())).thenReturn(job(42L, ME, JobStatus.SUCCEEDED));

        JobResponse response = service.get(JOB_NO);

        assertThat(response.getJobId()).isEqualTo(42L);
        assertThat(response.getJobNo()).isEqualTo(JOB_NO);
        assertThat(response.getStatus()).isEqualTo(JobStatus.SUCCEEDED.name());
        verify(jobMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("详情：任务不存在 -> 40446 JOB_NOT_FOUND")
    void getRejectsMissingJob() {
        when(jobMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.get("42"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：他人任务 -> 40446（不是 403，避免资源枚举）")
    void getRejectsForeignJob() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, OTHER, JobStatus.PENDING));

        assertThatThrownBy(() -> service.get("42"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：空字符串 -> 40446，且不查库")
    void getRejectsBlankIdentifier() {
        assertThatThrownBy(() -> service.get(""))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
        verify(jobMapper, never()).selectById(any());
        verify(jobMapper, never()).selectOne(any());
    }

    // ----------------------------------------------------------------------- list

    @Test
    @DisplayName("列表：强制 user_id 过滤，projectId/jobType/status 一并下推，实体映射成 DTO")
    void listFiltersByOwnerAndConditions() {
        Page<DesignJob> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(jobWithCounters(42L, ME, JobStatus.PENDING, 10, 3)));
        when(jobMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<JobResponse> result = service.list(7L, JobType.GENERATION.name(), JobStatus.PENDING.name(), 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).getJobNo()).isEqualTo(JOB_NO);
        assertThat(result.list().get(0).getTotalCount()).isEqualTo(10);

        ArgumentCaptor<Wrapper<DesignJob>> captor = wrapperCaptor();
        verify(jobMapper).selectPage(any(), captor.capture());
        AbstractWrapper<DesignJob, ?, ?> query = asWrapper(captor.getValue());
        // 条件构造器的列与取值都是惰性求值的：先取 SQL 片段，参数表才会被填上
        assertThat(query.getSqlSegment())
                .contains("user_id")
                .contains("project_id")
                .contains("job_type")
                .contains("status");
        assertThat(query.getParamNameValuePairs())
                .containsValue(ME)
                .containsValue(7L)
                .containsValue(JobType.GENERATION.name())
                .containsValue(JobStatus.PENDING.name());
    }

    @Test
    @DisplayName("列表：非法 jobType -> 400，且不查库")
    void listRejectsUnknownJobType() {
        assertThatThrownBy(() -> service.list(null, "NOPE", null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(jobMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：非法 status -> 400，且不查库")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list(null, null, "DONE", 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(jobMapper, never()).selectPage(any(), any());
    }

    // --------------------------------------------------------------------- cancel

    @Test
    @DisplayName("取消：PENDING -> CANCELLED 且写 completedAt")
    void cancelPendingJob() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, ME, JobStatus.PENDING));

        service.cancel("42");

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.CANCELLED.name());
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("取消：RUNNING -> CANCELLED 且写 completedAt")
    void cancelRunningJob() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, ME, JobStatus.RUNNING));

        service.cancel("42");

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.CANCELLED.name());
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("取消：已 SUCCEEDED -> 40953 JOB_NOT_CANCELLABLE，不写库")
    void cancelRejectsSucceededJob() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, ME, JobStatus.SUCCEEDED));

        assertThatThrownBy(() -> service.cancel("42"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_CANCELLABLE);
        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    @Test
    @DisplayName("取消：已 CANCELLED -> 40953 JOB_NOT_CANCELLABLE，不写库")
    void cancelRejectsAlreadyCancelledJob() {
        when(jobMapper.selectById(42L)).thenReturn(job(42L, ME, JobStatus.CANCELLED));

        assertThatThrownBy(() -> service.cancel("42"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_CANCELLABLE);
        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    @Test
    @DisplayName("取消：任务不存在 -> 40446 JOB_NOT_FOUND")
    void cancelRejectsMissingJob() {
        when(jobMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.cancel("42"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    // --------------------------------------------------------------------- report

    @Test
    @DisplayName("回报：缺 jobNo -> 400，且不查库")
    void reportRejectsBlankJobNo() {
        assertThatThrownBy(() -> service.report("  ", "RUNNING", 1, 1, null, null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(jobMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("回报：jobNo 不存在 -> 40446（必须抛，否则消息不会重试/进死信）")
    void reportRejectsUnknownJobNo() {
        when(jobMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.report("J0000000000000000", "RUNNING", 1, 1, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("J0000000000000000")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    @DisplayName("回报：任务已是终态 -> 直接返回，不 updateById（MQ 至少投递一次，重投是常态）")
    void reportIgnoresTerminalJob() {
        when(jobMapper.selectOne(any())).thenReturn(job(42L, ME, JobStatus.SUCCEEDED));

        service.report(JOB_NO, JobStatus.RUNNING.name(), 10, 5, null, null, null);

        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    @Test
    @DisplayName("回报：RUNNING 首次设置 startedAt")
    void reportRunningSetsStartedAt() {
        when(jobMapper.selectOne(any())).thenReturn(job(42L, ME, JobStatus.PENDING));

        service.report(JOB_NO, JobStatus.RUNNING.name(), 100, 0, Map.of("realness", "REAL"), null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        DesignJob updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(JobStatus.RUNNING.name());
        assertThat(updated.getStartedAt()).isNotNull();
        assertThat(updated.getTotalCount()).isEqualTo(100);
        assertThat(updated.getResultJson()).contains("REAL");
    }

    @Test
    @DisplayName("回报：已开始过的任务再回报 RUNNING，startedAt 不被覆盖")
    void reportRunningKeepsExistingStartedAt() {
        LocalDateTime started = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        DesignJob running = job(42L, ME, JobStatus.RUNNING);
        running.setStartedAt(started);
        when(jobMapper.selectOne(any())).thenReturn(running);

        service.report(JOB_NO, JobStatus.RUNNING.name(), 100, 10, null, null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isEqualTo(started);
    }

    @Test
    @DisplayName("回报：progress = done/total，两位小数（1/3 -> 33.33）")
    void reportComputesProgressAsTwoDecimalRatio() {
        when(jobMapper.selectOne(any())).thenReturn(jobWithCounters(42L, ME, JobStatus.RUNNING, 3, 0));

        service.report(JOB_NO, JobStatus.RUNNING.name(), 3, 1, null, null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProgress()).isEqualTo(new BigDecimal("33.33"));
    }

    @Test
    @DisplayName("回报：done 超过 total 时 progress 封顶 100")
    void reportCapsProgressAtHundred() {
        when(jobMapper.selectOne(any())).thenReturn(jobWithCounters(42L, ME, JobStatus.RUNNING, 2, 0));

        service.report(JOB_NO, JobStatus.RUNNING.name(), 2, 5, null, null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProgress()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("回报：total=0（数量未知）时 progress 保持 0，不做除零")
    void reportKeepsProgressZeroWhenTotalIsUnknown() {
        when(jobMapper.selectOne(any())).thenReturn(jobWithCounters(42L, ME, JobStatus.RUNNING, 0, 0));

        service.report(JOB_NO, JobStatus.RUNNING.name(), 0, 5, null, null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProgress()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("回报：SUCCEEDED -> progress 固定 100 且写 completedAt")
    void reportSucceededForcesFullProgressAndCompletedAt() {
        when(jobMapper.selectOne(any())).thenReturn(jobWithCounters(42L, ME, JobStatus.RUNNING, 10, 4));

        service.report(JOB_NO, JobStatus.SUCCEEDED.name(), 10, 4, null, null, null);

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        DesignJob updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(JobStatus.SUCCEEDED.name());
        assertThat(updated.getProgress()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(updated.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("回报：未知 status -> 400，且不写库")
    void reportRejectsUnknownStatus() {
        when(jobMapper.selectOne(any())).thenReturn(job(42L, ME, JobStatus.PENDING));

        assertThatThrownBy(() -> service.report(JOB_NO, "DONE", 1, 1, null, null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    // -------------------------------------------------------- markDispatchFailure

    @Test
    @DisplayName("投递失败登记：写 DEPENDENCY_UNAVAILABLE + 原因，但状态保持 PENDING（不是 FAILED）")
    void markDispatchFailureKeepsPendingStatus() {
        DesignJob pending = job(42L, ME, JobStatus.PENDING);
        when(jobMapper.selectById(42L)).thenReturn(pending);

        service.markDispatchFailure(42L, "broker down");

        ArgumentCaptor<DesignJob> captor = ArgumentCaptor.forClass(DesignJob.class);
        verify(jobMapper).updateById(captor.capture());
        DesignJob updated = captor.getValue();
        assertThat(updated.getErrorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE.name());
        assertThat(updated.getErrorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(updated.getErrorMsg()).contains("broker down");
        // 有意为之：消息没投出去不等于任务失败，置 FAILED 会丢掉「本该执行」的语义
        assertThat(updated.getStatus()).isEqualTo(JobStatus.PENDING.name());
        assertThat(updated.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("投递失败登记：任务已被删/不存在 -> 静默返回，不抛异常")
    void markDispatchFailureIgnoresMissingJob() {
        when(jobMapper.selectById(42L)).thenReturn(null);

        service.markDispatchFailure(42L, "broker down");

        verify(jobMapper, never()).updateById(any(DesignJob.class));
    }

    // -------------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<DesignJob>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static AbstractWrapper<DesignJob, ?, ?> asWrapper(Wrapper<DesignJob> wrapper) {
        return (AbstractWrapper<DesignJob, ?, ?>) wrapper;
    }
}
