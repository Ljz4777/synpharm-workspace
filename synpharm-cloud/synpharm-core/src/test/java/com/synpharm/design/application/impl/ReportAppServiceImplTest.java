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
import com.synpharm.design.api.dto.AcceptedReportResponse;
import com.synpharm.design.api.dto.JobResponse;
import com.synpharm.design.api.dto.ReportCreateRequest;
import com.synpharm.design.api.dto.ReportDecisionRequest;
import com.synpharm.design.api.dto.ReportResponse;
import com.synpharm.design.application.JobAppService;
import com.synpharm.design.application.ReportAppService;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignReport;
import com.synpharm.design.domain.model.JobType;
import com.synpharm.design.domain.model.ReportDecision;
import com.synpharm.design.domain.model.ReportStatus;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignReportMapper;
import com.synpharm.design.support.JobPublisher;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告与审批用例测试。
 *
 * <p>四条容易「看起来没事」的线，这里逐条钉死：①报告必须自带能力边界声明（红线 R1/R2），
 * 否则读者会默认所有指标都算过；②归属校验 fail-closed，别人的 id 一律 404；
 * ③{@code status}（正文生成到哪一步）与 {@code decision}（人怎么批）正交，正文没生成完
 * 不许审批，且该校验发生在写库之前；④REVISE 必须同事务落一份修订版占位，而
 * APPROVED/REJECTED 绝不能凭空多出版本。
 *
 * <p>{@code reportNo} 与 {@code capabilityNote} 都不可为空，{@code bodyJson} 必须真的
 * 经 {@link JsonCodec} 反序列化成 Map（这里用 spy 走真实实现，而不是 mock 掉转换）。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;

    /** R + yyMMddHHmmss + 4 位大写十六进制 */
    private static final String REPORT_NO_PATTERN = "R\\d{12}[0-9A-F]{4}";

    private static final long INSERTED_ID = 900L;

    /** createOrReuse 返回的任务主键/编号（报告创建成功后必须回填） */
    private static final long JOB_ID = 700L;
    private static final String JOB_NO = "J261014153045B1C2";
    private static final String JOB_STATUS = "PENDING";

    @Mock private DesignReportMapper reportMapper;
    @Mock private DesignProjectMapper projectMapper;
    @Mock private JobAppService jobAppService;
    @Mock private JobPublisher jobPublisher;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private ReportAppServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 靠 TableInfo 把方法引用翻成列名；单测里没有 Spring/MyBatis 上下文，
        // 手工注册一次，才能真正断言 AppService 下发到 SQL 的过滤条件（而不是只断言「调过一次」）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DesignReport.class);
        CurrentUserTestSupport.login(ME);
        when(reportMapper.insert(any(DesignReport.class))).thenAnswer(invocation -> {
            DesignReport inserted = invocation.getArgument(0);
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

    private static DesignProject project(Long ownerId, Long currentRoundId) {
        DesignProject p = new DesignProject();
        p.setId(1L);
        p.setProjectNo("P260101000000ABCD");
        p.setUserId(ownerId);
        p.setName("测试项目");
        p.setStatus("ACTIVE");
        p.setCurrentRoundId(currentRoundId);
        return p;
    }

    private static DesignReport report(Long id, Long ownerId, ReportStatus status, ReportDecision decision) {
        DesignReport r = new DesignReport();
        r.setId(id);
        r.setReportNo("R261014153045A7F3");
        r.setProjectId(1L);
        r.setRoundId(30L);
        r.setUserId(ownerId);
        r.setTitle("测试项目 · TOP_N 报告");
        r.setReportType("TOP_N");
        r.setTopN(10);
        r.setCapabilityNote("能力边界：本报告由测试/预置数据构造，未经过生成管线。");
        r.setDecision(decision.name());
        r.setVersionNo(1);
        r.setStatus(status.name());
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private static ReportCreateRequest createRequest(String reportType) {
        ReportCreateRequest request = new ReportCreateRequest();
        request.setProjectId(1L);
        request.setReportType(reportType);
        return request;
    }

    private static ReportDecisionRequest decisionRequest(String decision, String note) {
        ReportDecisionRequest request = new ReportDecisionRequest();
        request.setDecision(decision);
        request.setNote(note);
        return request;
    }

    private void stubOwnedReport(ReportStatus status, ReportDecision decision) {
        when(reportMapper.selectById(42L)).thenReturn(report(42L, ME, status, decision));
    }

    /** createOrReuse 默认返回「新建任务」（existing[0] 保持 false，bizId 为空） */
    private void stubNewJob() {
        when(jobAppService.createOrReuse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(createdJob(null));
    }

    /**
     * createOrReuse 幂等命中既有任务：把 {@code existing[0]} 置真（调用方据此判断重放），
     * 返回的任务 {@code bizId} 指向 {@code existingReportId}（null 表示同键任务尚未关联报告）。
     */
    private void stubReusedJob(Long existingReportId) {
        when(jobAppService.createOrReuse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    boolean[] existing = invocation.getArgument(8);
                    existing[0] = true;
                    return createdJob(existingReportId);
                });
    }

    private static JobAppService.CreatedJob createdJob(Long bizId) {
        JobResponse job = new JobResponse();
        job.setJobId(JOB_ID);
        job.setJobNo(JOB_NO);
        job.setJobType(JobType.REPORT.name());
        job.setStatus(JOB_STATUS);
        job.setBizType("REPORT");
        job.setBizId(bizId);
        return new JobAppService.CreatedJob(JOB_ID, JOB_NO, job);
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("生成报告：未知 reportType -> 400，且不落库")
    void createRejectsUnknownReportType() {
        assertThatThrownBy(() -> service.create(createRequest("NOPE"), null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reportMapper, never()).insert(any(DesignReport.class));
        verify(projectMapper, never()).selectById(any());
        verify(jobAppService, never()).createOrReuse(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("生成报告：项目不存在 -> 40440（不是 500）")
    void createRejectsMissingProject() {
        when(projectMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(createRequest("TOP_N"), null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(reportMapper, never()).insert(any(DesignReport.class));
    }

    @Test
    @DisplayName("生成报告：项目不属于当前用户 -> 40440")
    void createRejectsForeignProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(OTHER, 30L));

        assertThatThrownBy(() -> service.create(createRequest("TOP_N"), null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(reportMapper, never()).insert(any(DesignReport.class));
    }

    @Test
    @DisplayName("生成报告：登记 PENDING 记录，topN 默认 10，roundId 回落到项目当前轮次，jobId=任务 id")
    void createRegistersPendingReportWithDefaults() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubNewJob();

        AcceptedReportResponse response = service.create(createRequest("TOP_N"), null);

        assertThat(response.getReportId()).isEqualTo(INSERTED_ID);
        assertThat(response.getReportNo()).matches(REPORT_NO_PATTERN);
        assertThat(response.getJobId()).isEqualTo(JOB_ID);
        assertThat(response.getStatus()).isEqualTo(ReportStatus.PENDING.name());

        ArgumentCaptor<DesignReport> captor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).insert(captor.capture());
        DesignReport inserted = captor.getValue();
        assertThat(inserted.getReportNo()).matches(REPORT_NO_PATTERN);
        assertThat(inserted.getProjectId()).isEqualTo(1L);
        assertThat(inserted.getRoundId()).isEqualTo(30L);
        assertThat(inserted.getUserId()).isEqualTo(ME);
        assertThat(inserted.getReportType()).isEqualTo("TOP_N");
        assertThat(inserted.getTopN()).isEqualTo(10);
        assertThat(inserted.getStatus()).isEqualTo(ReportStatus.PENDING.name());
        assertThat(inserted.getDecision()).isEqualTo(ReportDecision.PENDING.name());
        assertThat(inserted.getVersionNo()).isEqualTo(1);
        // 红线 R1/R2：能力边界声明不允许为空（不断言具体文案，只钉住「能力边界」这一语义标签）
        assertThat(inserted.getCapabilityNote()).isNotBlank();
        assertThat(inserted.getCapabilityNote()).contains("能力边界");
        assertThat(inserted.getTitle()).contains("测试项目").contains("TOP_N");
    }

    @Test
    @DisplayName("生成报告：显式传 roundId/topN 时按传入值落库")
    void createHonoursExplicitRoundAndTopN() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubNewJob();
        ReportCreateRequest request = createRequest("ADHOC");
        request.setRoundId(55L);
        request.setTopN(5);

        service.create(request, null);

        ArgumentCaptor<DesignReport> captor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoundId()).isEqualTo(55L);
        assertThat(captor.getValue().getTopN()).isEqualTo(5);
    }

    @Test
    @DisplayName("生成报告：任务先于业务行登记（bizId 传 null），登记成功后以新报告 id 回填关联并提交后投递")
    void createRegistersTaskBeforeReportAndPublishes() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubNewJob();

        AcceptedReportResponse response = service.create(createRequest("TOP_N"), "idem-key-1");

        assertThat(response.getReportId()).isEqualTo(INSERTED_ID);
        assertThat(response.getJobId()).isEqualTo(JOB_ID);
        assertThat(response.getStatus()).isEqualTo(ReportStatus.PENDING.name());

        // 幂等必须先于写业务行：登记任务时 bizId 为 null，bizType 固定 REPORT，幂等键原样透传；
        // 开关的默认值必须在服务端定下来再下发（透传 null 会被计算层当成「未指定」，与契约默认 true 相反）
        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        verify(jobAppService).createOrReuse(eq(JobType.REPORT), eq(1L), eq(30L), isNull(), eq("REPORT"), isNull(),
                params.capture(), eq("idem-key-1"), any());
        assertThat(params.getValue())
                .containsEntry("projectId", 1L)
                .containsEntry("reportType", "TOP_N")
                .containsEntry("topN", 10)
                .containsEntry("includeLineage", true)
                .containsEntry("includeExperimentAccuracy", true);

        // 报告自增 id 之后才拿到，所以 attachBusiness 必须以「新插入的报告 id」被调用
        verify(jobAppService).attachBusiness(JOB_ID, "REPORT", INSERTED_ID);
        ArgumentCaptor<DesignReport> updated = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(INSERTED_ID);
        assertThat(updated.getValue().getJobId()).isEqualTo(JOB_ID);

        // 投递 payload 必须能让计算层直接定位到报告；evaluationRunId 为 null 时不应出现在 payload
        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(jobPublisher).publishAfterCommit(eq(JOB_ID), eq(JOB_NO), eq(JobType.REPORT), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("reportId", INSERTED_ID)
                .containsEntry("projectId", 1L)
                .containsEntry("topN", 10)
                .containsEntry("includeLineage", true)
                .containsEntry("includeExperimentAccuracy", true)
                .doesNotContainKey("evaluationRunId");
    }

    @Test
    @DisplayName("生成报告：显式传 includeLineage=false / includeExperimentAccuracy=false -> 如实下发 false，不被默认值覆盖")
    void createHonoursExplicitFalseIncludeFlags() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubNewJob();
        ReportCreateRequest request = createRequest("TOP_N");
        request.setIncludeLineage(false);
        request.setIncludeExperimentAccuracy(false);

        service.create(request, null);

        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        verify(jobAppService).createOrReuse(any(), any(), any(), any(), any(), any(), params.capture(), any(), any());
        assertThat(params.getValue())
                .containsEntry("includeLineage", false)
                .containsEntry("includeExperimentAccuracy", false);

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(jobPublisher).publishAfterCommit(any(), any(), any(), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("includeLineage", false)
                .containsEntry("includeExperimentAccuracy", false);
    }

    @Test
    @DisplayName("生成报告：显式传 topN=3 -> 落库、任务参数与投递 payload 都用 3")
    void createHonoursExplicitTopN() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubNewJob();
        ReportCreateRequest request = createRequest("TOP_N");
        request.setTopN(3);

        service.create(request, null);

        ArgumentCaptor<DesignReport> inserted = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTopN()).isEqualTo(3);

        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        verify(jobAppService).createOrReuse(any(), any(), any(), any(), any(), any(), params.capture(), any(), any());
        assertThat(params.getValue()).containsEntry("topN", 3);

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(jobPublisher).publishAfterCommit(any(), any(), any(), payload.capture());
        assertThat(payload.getValue()).containsEntry("topN", 3);
    }

    @Test
    @DisplayName("生成报告：幂等重放命中已终态任务且 bizId 指向已有报告 -> 返回旧报告，绝不 insert、不投递")
    void createReusesExistingReportOnIdempotentReplay() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(reportMapper.selectById(42L)).thenReturn(report(42L, ME, ReportStatus.SUCCEEDED, ReportDecision.PENDING));
        stubReusedJob(42L);

        AcceptedReportResponse response = service.create(createRequest("TOP_N"), "idem-key-2");

        // reportId/reportNo/jobId/status 全部取自旧报告，不得 insert 新报告
        assertThat(response.getReportId()).isEqualTo(42L);
        assertThat(response.getReportNo()).isEqualTo("R261014153045A7F3");
        assertThat(response.getJobId()).isEqualTo(JOB_ID);
        assertThat(response.getStatus()).isEqualTo(ReportStatus.SUCCEEDED.name());

        verify(reportMapper, never()).insert(any(DesignReport.class));
        verify(jobAppService, never()).attachBusiness(any(), any(), any());
        verify(jobPublisher, never()).publishAfterCommit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("生成报告：幂等命中已终态任务但 bizId 为空 -> 409 冲突，不 insert、不关联、不投递")
    void createFailsWithConflictWhenReusedJobHasNoBizId() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        stubReusedJob(null);

        assertReusedJobWithoutReportConflicts("idem-key-3");

        // bizId 为空时连查报告都不该发生
        verify(reportMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("生成报告：幂等命中已终态任务但 bizId 指向的报告已不存在 -> 409 冲突，不 insert、不关联、不投递")
    void createFailsWithConflictWhenReusedJobBizIdReportIsMissing() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(reportMapper.selectById(999L)).thenReturn(null);
        stubReusedJob(999L);

        assertReusedJobWithoutReportConflicts("idem-key-3b");

        verify(reportMapper).selectById(999L);
    }

    /**
     * 复用到已终态任务却找不到关联报告：必须如实报冲突，绝不能把新报告挂到终态任务上。
     *
     * <p>消费端约定「终态任务不再执行」，若仍新建报告并投递，新报告会永远停在 PENDING，
     * 而客户端轮询同一个 jobId 只看到 SUCCEEDED 却没有正文 —— 状态自相矛盾。
     */
    private void assertReusedJobWithoutReportConflicts(String idempotencyKey) {
        assertThatThrownBy(() -> service.create(createRequest("TOP_N"), idempotencyKey))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_CONFLICT);
                    // details 必须能让排查者直接定位到那个终态任务
                    assertThat(detailsOf(ex))
                            .containsEntry("existingId", JOB_ID)
                            .containsEntry("existingJobNo", JOB_NO)
                            .containsEntry("existingStatus", JOB_STATUS);
                });

        verify(reportMapper, never()).insert(any(DesignReport.class));
        verify(jobAppService, never()).attachBusiness(any(), any(), any());
        verify(jobPublisher, never()).publishAfterCommit(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(BizException ex) {
        return (Map<String, Object>) ex.getDetails();
    }

    @Test
    @DisplayName("生成报告：同键任务未终态 -> 409 BUSINESS_CONFLICT 透传，且不 insert、不关联、不投递")
    void createPropagatesConflictWithoutSideEffects() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(jobAppService.createOrReuse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.BUSINESS_CONFLICT,
                        "相同 Idempotency-Key 的任务仍在进行中", Map.of("existingId", 5L)));

        assertThatThrownBy(() -> service.create(createRequest("TOP_N"), "idem-key-4"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);

        verify(reportMapper, never()).insert(any(DesignReport.class));
        verify(jobAppService, never()).attachBusiness(any(), any(), any());
        verify(jobPublisher, never()).publishAfterCommit(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------- list

    @Test
    @DisplayName("列表：强制 user_id 过滤，并把实体映射成 DTO")
    void listFiltersByCurrentUserAndMapsRecords() {
        Page<DesignReport> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(report(42L, ME, ReportStatus.SUCCEEDED, ReportDecision.PENDING)));
        when(reportMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ReportResponse> result = service.list(1L, null, 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).getReportNo()).isEqualTo("R261014153045A7F3");

        ArgumentCaptor<Wrapper<DesignReport>> captor = wrapperCaptor();
        verify(reportMapper).selectPage(any(), captor.capture());
        AbstractWrapper<DesignReport, ?, ?> query = asWrapper(captor.getValue());
        // 条件构造器的列与取值都是惰性求值的：先取 SQL 片段，参数表才会被填上
        assertThat(query.getSqlSegment()).contains("user_id");
        assertThat(query.getParamNameValuePairs()).containsValue(ME);
    }

    @Test
    @DisplayName("列表：非法 decision -> 400，且不查库")
    void listRejectsUnknownDecision() {
        assertThatThrownBy(() -> service.list(1L, "NOPE", 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reportMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：合法 decision 当作查询条件下发")
    void listAppliesDecisionAsCondition() {
        when(reportMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        service.list(1L, "approved", 1, 20);

        ArgumentCaptor<Wrapper<DesignReport>> captor = wrapperCaptor();
        verify(reportMapper).selectPage(any(), captor.capture());
        AbstractWrapper<DesignReport, ?, ?> query = asWrapper(captor.getValue());
        assertThat(query.getSqlSegment()).contains("decision");
        // decision 的取值只会经由 eq(...) 进入参数表，出现即证明它被当作条件下发
        assertThat(query.getParamNameValuePairs()).containsValue(ReportDecision.APPROVED.name());
    }

    // --------------------------------------------------------------------- get

    @Test
    @DisplayName("详情：报告不存在 -> 40447")
    void getRejectsMissingReport() {
        when(reportMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：他人报告 -> 40447（不是 403，避免资源枚举）")
    void getRejectsForeignReport() {
        when(reportMapper.selectById(42L)).thenReturn(report(42L, OTHER, ReportStatus.SUCCEEDED, ReportDecision.PENDING));

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：字段逐项映射，bodyJson 经 JsonCodec 反序列化为 Map")
    void getMapsEntityToResponse() {
        DesignReport entity = report(42L, ME, ReportStatus.SUCCEEDED, ReportDecision.APPROVED);
        entity.setBodyJson("{\"topN\":10,\"candidates\":[\"M1\"]}");
        entity.setBodyMd("# 报告");
        entity.setDecisionBy(ME);
        entity.setDecisionAt(LocalDateTime.now());
        entity.setDecisionNote("同意");
        entity.setJobId(77L);
        when(reportMapper.selectById(42L)).thenReturn(entity);

        ReportResponse response = service.get(42L);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getReportNo()).isEqualTo("R261014153045A7F3");
        assertThat(response.getProjectId()).isEqualTo(1L);
        assertThat(response.getRoundId()).isEqualTo(30L);
        assertThat(response.getReportType()).isEqualTo("TOP_N");
        assertThat(response.getTopN()).isEqualTo(10);
        assertThat(response.getBodyJson()).containsEntry("topN", 10);
        assertThat(response.getBodyMd()).isEqualTo("# 报告");
        assertThat(response.getDecision()).isEqualTo(ReportDecision.APPROVED.name());
        assertThat(response.getDecisionBy()).isEqualTo(ME);
        assertThat(response.getDecisionNote()).isEqualTo("同意");
        assertThat(response.getStatus()).isEqualTo(ReportStatus.SUCCEEDED.name());
        assertThat(response.getJobId()).isEqualTo(77L);
        verify(jsonCodec).readMap("{\"topN\":10,\"candidates\":[\"M1\"]}");
    }

    // ------------------------------------------------------------------ decide

    @Test
    @DisplayName("审批：非法 decision -> 400")
    void decideRejectsUnknownDecision() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("NOPE", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：decision=PENDING 不是可提交结论 -> 400")
    void decideRejectsPendingAsDecision() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("PENDING", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：已审批的版本不可重复决策 -> 40955")
    void decideRejectsAlreadyDecided() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.APPROVED);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("APPROVED", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_ALREADY_DECIDED);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：正文未生成（status=PENDING）-> 40954，且校验发生在写库之前")
    void decideRejectsUnreadyBody() {
        stubOwnedReport(ReportStatus.PENDING, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("APPROVED", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_READY);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
        verify(reportMapper, never()).insert(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：REVISE 缺审批意见 -> 40048，且不落库")
    void decideRequiresNoteForRevise() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("REVISE", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_DECISION_NOTE_REQUIRED);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：REJECTED 缺审批意见 -> 40048")
    void decideRequiresNoteForRejected() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decisionRequest("REJECTED", "  ")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_DECISION_NOTE_REQUIRED);
        verify(reportMapper, never()).updateById(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：APPROVED 无意见合法，只更新原报告，不产生修订版")
    void decideApprovedWithoutNoteDoesNotCreateRevision() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        ReportDecisionRequest.Response response = service.decide(42L, decisionRequest("APPROVED", null));

        ArgumentCaptor<DesignReport> captor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).updateById(captor.capture());
        DesignReport updated = captor.getValue();
        assertThat(updated.getDecision()).isEqualTo(ReportDecision.APPROVED.name());
        assertThat(updated.getDecisionBy()).isEqualTo(ME);
        assertThat(updated.getDecisionAt()).isNotNull();
        assertThat(updated.getDecisionNote()).isNull();

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getDecision()).isEqualTo(ReportDecision.APPROVED.name());
        assertThat(response.getDecisionBy()).isEqualTo(ME);
        assertThat(response.getDecisionAt()).isNotNull();
        assertThat(response.getVersionNo()).isEqualTo(1);
        assertThat(response.getRevisionReportId()).isNull();
        verify(reportMapper, never()).insert(any(DesignReport.class));
    }

    @Test
    @DisplayName("审批：REVISE 写入结论并在同事务内插入版本 +1 的修订版占位")
    void decideReviseWritesDecisionAndInsertsRevision() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        ReportDecisionRequest.Response response = service.decide(42L, decisionRequest("REVISE", "补充实测准确率"));

        ArgumentCaptor<DesignReport> updatedCaptor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).updateById(updatedCaptor.capture());
        DesignReport updated = updatedCaptor.getValue();
        assertThat(updated.getId()).isEqualTo(42L);
        assertThat(updated.getDecision()).isEqualTo(ReportDecision.REVISE.name());
        assertThat(updated.getDecisionBy()).isEqualTo(ME);
        assertThat(updated.getDecisionAt()).isNotNull();
        assertThat(updated.getDecisionNote()).isEqualTo("补充实测准确率");

        ArgumentCaptor<DesignReport> revisionCaptor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper, times(1)).insert(revisionCaptor.capture());
        DesignReport revision = revisionCaptor.getValue();
        assertThat(revision.getRevisionOf()).isEqualTo(42L);
        assertThat(revision.getVersionNo()).isEqualTo(2);
        assertThat(revision.getDecision()).isEqualTo(ReportDecision.PENDING.name());
        assertThat(revision.getStatus()).isEqualTo(ReportStatus.PENDING.name());
        assertThat(revision.getReportNo()).matches(REPORT_NO_PATTERN);
        assertThat(revision.getReportNo()).isNotEqualTo(updated.getReportNo());
        assertThat(revision.getProjectId()).isEqualTo(updated.getProjectId());
        assertThat(revision.getRoundId()).isEqualTo(updated.getRoundId());
        assertThat(revision.getUserId()).isEqualTo(updated.getUserId());
        assertThat(revision.getTitle()).isEqualTo(updated.getTitle());
        assertThat(revision.getReportType()).isEqualTo(updated.getReportType());
        assertThat(revision.getTopN()).isEqualTo(updated.getTopN());
        assertThat(revision.getCapabilityNote()).isNotBlank();
        assertThat(revision.getDecisionBy()).isNull();

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getDecision()).isEqualTo(ReportDecision.REVISE.name());
        assertThat(response.getRevisionReportId()).isEqualTo(INSERTED_ID);
    }

    @Test
    @DisplayName("审批：REJECTED 直接终结该版本，不插入修订版")
    void decideRejectedDoesNotCreateRevision() {
        stubOwnedReport(ReportStatus.PARTIAL, ReportDecision.PENDING);

        ReportDecisionRequest.Response response = service.decide(42L, decisionRequest("REJECTED", "指标口径不对"));

        ArgumentCaptor<DesignReport> captor = ArgumentCaptor.forClass(DesignReport.class);
        verify(reportMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo(ReportDecision.REJECTED.name());
        assertThat(captor.getValue().getDecisionNote()).isEqualTo("指标口径不对");
        assertThat(response.getRevisionReportId()).isNull();
        verify(reportMapper, never()).insert(any(DesignReport.class));
    }

    // ------------------------------------------------------------------ export

    @Test
    @DisplayName("导出：未知 format -> 400")
    void exportRejectsUnknownFormat() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.export(42L, "docx"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("导出：pdf 渲染未落地 -> 503（如实拒绝，不伪造文件）")
    void exportPdfIsUnavailable() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.export(42L, "pdf"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("导出：markdown 但正文为空 -> 40954")
    void exportMarkdownRejectsEmptyBody() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.export(42L, "markdown"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_READY);
    }

    @Test
    @DisplayName("导出：json 但正文为空 -> 40954")
    void exportJsonRejectsEmptyBody() {
        stubOwnedReport(ReportStatus.SUCCEEDED, ReportDecision.PENDING);

        assertThatThrownBy(() -> service.export(42L, "json"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_READY);
    }

    @Test
    @DisplayName("导出 markdown：Content-Type 为 text/markdown，文件名为 reportNo + .md")
    void exportMarkdownReturnsPayload() {
        DesignReport entity = report(42L, ME, ReportStatus.SUCCEEDED, ReportDecision.PENDING);
        entity.setBodyMd("# 报告\n\n命中率 0.35");
        when(reportMapper.selectById(42L)).thenReturn(entity);

        ReportAppService.ExportPayload payload = service.export(42L, "markdown");

        assertThat(payload.contentType()).isEqualTo("text/markdown; charset=utf-8");
        assertThat(payload.fileName()).isEqualTo("R261014153045A7F3.md");
        assertThat(new String(payload.body(), StandardCharsets.UTF_8)).isEqualTo("# 报告\n\n命中率 0.35");
    }

    @Test
    @DisplayName("导出 json：Content-Type 为 application/json，文件名为 reportNo + .json")
    void exportJsonReturnsPayload() {
        DesignReport entity = report(42L, ME, ReportStatus.SUCCEEDED, ReportDecision.PENDING);
        entity.setBodyJson("{\"topN\":10}");
        when(reportMapper.selectById(42L)).thenReturn(entity);

        ReportAppService.ExportPayload payload = service.export(42L, "json");

        assertThat(payload.contentType()).isEqualTo("application/json; charset=utf-8");
        assertThat(payload.fileName()).isEqualTo("R261014153045A7F3.json");
        assertThat(new String(payload.body(), StandardCharsets.UTF_8)).isEqualTo("{\"topN\":10}");
    }

    // -------------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<DesignReport>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static AbstractWrapper<DesignReport, ?, ?> asWrapper(Wrapper<DesignReport> wrapper) {
        return (AbstractWrapper<DesignReport, ?, ?>) wrapper;
    }
}
