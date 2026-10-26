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
import com.synpharm.design.api.dto.ExperimentCreateRequest;
import com.synpharm.design.api.dto.ExperimentResponse;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.WetExperiment;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.WetExperimentMapper;
import com.synpharm.design.support.HitJudge;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 湿实验台账用例测试。
 *
 * <p>几条容易「看起来没事」的线，这里逐条钉死：①{@code errorDelta}/{@code isHit} 只能来自
 * 服务端计算（{@code ExperimentCreateRequest} 里根本没有这两个字段），所以断言必须落在
 * <b>写库实体</b>上；②归属校验 fail-closed（项目不存在 / 不是本人一律 40440，实验一律 40448），
 * 且失败时绝不能落库；③{@code assayType} 必须先登记在 {@code design_library_item(category=ASSAY)}，
 * 否则台账里会混进没人认得的检测类型；④PATCH 的「不传 = 不变」——这条上一组真踩过，
 * 认证结果时顺手把实验条件清空了，这里用「只传必填字段」的请求把它钉死；
 * ⑤不传 {@code projectId} 查列表时按本人全部项目 id 限定，本人一个项目都没有时短路成空页，
 * 连实验表都不查。
 *
 * <p>{@code wet_experiment} 没有 {@code user_id} 列，属主来自所属项目，所以测归属失败/成功时
 * 都要把 {@code projectMapper.selectById} 的二次查询 mock 上。
 *
 * <p>{@link HitJudge} 在这里被替换成 mock：本测试只关心「判定结果有没有被如实写进实体」，
 * 判定口径本身由 {@code HitJudgeTest} 负责。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;

    /** W + yyMMddHHmmss + 4 位大写十六进制 */
    private static final String EXPERIMENT_NO_PATTERN = "W\\d{12}[0-9A-F]{4}";

    private static final long INSERTED_ID = 900L;

    @Mock private WetExperimentMapper experimentMapper;
    @Mock private DesignProjectMapper projectMapper;
    @Mock private DesignLibraryItemMapper libraryItemMapper;
    @Mock private HitJudge hitJudge;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private ExperimentAppServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 靠 TableInfo 把方法引用翻成列名；单测里没有 Spring/MyBatis 上下文，
        // 手工注册一次，才能真正断言 AppService 下发到 SQL 的过滤条件（而不是只断言「调过一次」）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WetExperiment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DesignProject.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DesignLibraryItem.class);
        CurrentUserTestSupport.login(ME);
        // MyBatis-Plus 会在 insert 后把自增主键回填到实体上，mock 必须模拟这一点
        when(experimentMapper.insert(any(WetExperiment.class))).thenAnswer(invocation -> {
            WetExperiment inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(INSERTED_ID);
            }
            return 1;
        });
        // 默认：assayType 已在知识库注册
        when(libraryItemMapper.selectCount(any())).thenReturn(1L);
        // 默认：判定不可得（需要判定的用例在用例内覆盖）
        when(hitJudge.judge(any(), any(), any(), any()))
                .thenReturn(new HitJudge.Verdict(null, "无数值结果"));
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

    private static DesignProject ownedProject(long id) {
        DesignProject p = new DesignProject();
        p.setId(id);
        p.setUserId(ME);
        p.setName("项目 " + id);
        return p;
    }

    private static ExperimentCreateRequest createRequest() {
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setProjectId(1L);
        request.setAssayType("IC50");
        request.setResultStatus("SUCCEEDED");
        return request;
    }

    private static WetExperiment experiment(Long id, Long projectId) {
        WetExperiment e = new WetExperiment();
        e.setId(id);
        e.setExperimentNo("W261014153045A7F3");
        e.setProjectId(projectId);
        e.setRoundId(30L);
        e.setAssayType("IC50");
        e.setResultStatus("SUCCEEDED");
        e.setOperatorId(ME);
        e.setFeedbackToModel(0);
        return e;
    }

    /** 库里已有的实验：更正请求里省略的字段都要保持这些值 */
    private static WetExperiment storedExperiment() {
        WetExperiment e = experiment(42L, 1L);
        e.setAssayProtocol("IC50 标准方案");
        e.setTargetRole("WILD_TYPE");
        e.setResultNum(new BigDecimal("150"));
        e.setResultText("curves ok");
        e.setUnit("nM");
        e.setOperator("GTE");
        e.setPredictedValue(new BigDecimal("100"));
        e.setPredictedMetric("MOL_IC50");
        e.setLabName("华东实验室");
        e.setErrorDelta(new BigDecimal("50"));
        e.setIsHit(0);
        return e;
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("录入：项目不存在 -> 40440，且不落库")
    void createRejectsMissingProject() {
        when(projectMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：项目不属于当前用户 -> 40440，且不落库")
    void createRejectsForeignProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(OTHER, 30L));

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：assayType 未在知识库(category=ASSAY)注册 -> 40049，且不落库")
    void createRejectsUnregisteredAssayType() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(libraryItemMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未在知识库注册")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPERIMENT_ASSAY_UNKNOWN);
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：未知 resultStatus -> 400，且枚举校验先于知识库查询")
    void createRejectsUnknownResultStatus() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        ExperimentCreateRequest request = createRequest();
        request.setResultStatus("DONE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的 resultStatus")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(libraryItemMapper, never()).selectCount(any());
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：未知 operator（BETWEEN 不在 DDL 允许集内）-> 400，且不落库")
    void createRejectsUnknownOperator() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        ExperimentCreateRequest request = createRequest();
        request.setOperator("BETWEEN");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的 operator")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：未知 targetRole -> 400，且不落库")
    void createRejectsUnknownTargetRole() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        ExperimentCreateRequest request = createRequest();
        request.setTargetRole("PRIMARY");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的 targetRole")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(experimentMapper, never()).insert(any(WetExperiment.class));
    }

    @Test
    @DisplayName("录入：服务端补编号/属主/回灌标记，errorDelta 与 isHit 取自判定结果")
    void createFillsServerSideFields() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(hitJudge.judge(any(), any(), any(), any())).thenReturn(new HitJudge.Verdict(true, null));
        ExperimentCreateRequest request = createRequest();
        request.setResultNum(new BigDecimal("150"));
        request.setPredictedValue(new BigDecimal("100"));
        request.setPredictedMetric("MOL_IC50");

        ExperimentResponse response = service.create(request);

        assertThat(response.getId()).isEqualTo(INSERTED_ID);
        assertThat(response.getExperimentNo()).matches(EXPERIMENT_NO_PATTERN);
        assertThat(response.getOperatorId()).isEqualTo(ME);
        assertThat(response.getFeedbackToModel()).isFalse();
        assertThat(response.getIsHit()).isTrue();
        assertThat(response.getErrorDelta()).isEqualByComparingTo("50");

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).insert(captor.capture());
        WetExperiment inserted = captor.getValue();
        assertThat(inserted.getExperimentNo()).matches(EXPERIMENT_NO_PATTERN);
        assertThat(inserted.getProjectId()).isEqualTo(1L);
        // roundId 未传 -> 回落到项目当前轮次
        assertThat(inserted.getRoundId()).isEqualTo(30L);
        assertThat(inserted.getOperatorId()).isEqualTo(ME);
        assertThat(inserted.getFeedbackToModel()).isZero();
        // 请求 DTO 里没有 errorDelta/isHit，这两个值只能来自服务端计算
        assertThat(inserted.getErrorDelta()).isEqualByComparingTo("50");
        assertThat(inserted.getIsHit()).isEqualTo(1);
    }

    @Test
    @DisplayName("录入：显式传 roundId 时按传入值落库")
    void createHonoursExplicitRoundId() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        ExperimentCreateRequest request = createRequest();
        request.setRoundId(55L);

        service.create(request);

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoundId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("录入：判定为不命中时 isHit 落 0；缺预测值时 errorDelta 为 null")
    void createWritesJudgementVerbatim() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(hitJudge.judge(any(), any(), any(), any())).thenReturn(new HitJudge.Verdict(false, null));
        ExperimentCreateRequest request = createRequest();
        request.setResultNum(new BigDecimal("150"));

        ExperimentResponse response = service.create(request);

        assertThat(response.getIsHit()).isFalse();
        assertThat(response.getErrorDelta()).isNull();

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsHit()).isZero();
        assertThat(captor.getValue().getErrorDelta()).isNull();
    }

    @Test
    @DisplayName("录入：判定不可得(hit=null)时 isHit 落 null，不伪装成不命中")
    void createLeavesIsHitNullWhenUnjudgeable() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        ExperimentCreateRequest request = createRequest();
        request.setResultNum(new BigDecimal("150"));

        ExperimentResponse response = service.create(request);

        assertThat(response.getIsHit()).isNull();

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsHit()).isNull();
    }

    // -------------------------------------------------------------------- list

    @Test
    @DisplayName("列表：指定 projectId 时先校验归属，他人项目 -> 40440，且不查实验表")
    void listRejectsForeignProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(OTHER, 30L));

        assertThatThrownBy(() -> service.list(1L, null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(experimentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：非法 resultStatus -> 400，且一次库都不查")
    void listRejectsUnknownResultStatus() {
        assertThatThrownBy(() -> service.list(null, null, "NOPE", 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的 resultStatus")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(experimentMapper, never()).selectPage(any(), any());
        verify(projectMapper, never()).selectById(any());
        verify(projectMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("列表：指定 projectId 时把范围限定在该项目，并映射成 DTO")
    void listScopesToGivenProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        Page<WetExperiment> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(experiment(42L, 1L)));
        when(experimentMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ExperimentResponse> result = service.list(1L, null, null, 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).getExperimentNo()).isEqualTo("W261014153045A7F3");

        ArgumentCaptor<Wrapper<WetExperiment>> captor = wrapperCaptor();
        verify(experimentMapper).selectPage(any(), captor.capture());
        AbstractWrapper<WetExperiment, ?, ?> query = asWrapper(captor.getValue());
        // 条件构造器的列与取值都是惰性求值的：先取 SQL 片段，参数表才会被填上
        assertThat(query.getSqlSegment()).contains("project_id");
        assertThat(query.getParamNameValuePairs()).containsValue(1L);
    }

    @Test
    @DisplayName("列表：不传 projectId 时按本人全部项目 id 限定")
    void listScopesToAllOwnedProjects() {
        when(projectMapper.selectList(any())).thenReturn(List.of(ownedProject(7L), ownedProject(8L)));
        when(experimentMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        service.list(null, null, null, 1, 20);

        ArgumentCaptor<Wrapper<WetExperiment>> captor = wrapperCaptor();
        verify(experimentMapper).selectPage(any(), captor.capture());
        AbstractWrapper<WetExperiment, ?, ?> query = asWrapper(captor.getValue());
        assertThat(query.getSqlSegment()).contains("project_id");
        assertThat(query.getParamNameValuePairs()).containsValue(7L);
        assertThat(query.getParamNameValuePairs()).containsValue(8L);
    }

    @Test
    @DisplayName("列表：本人一个项目都没有 -> 空页短路，连实验表都不查")
    void listShortCircuitsWhenNoOwnedProject() {
        when(projectMapper.selectList(any())).thenReturn(List.of());

        PageResult<ExperimentResponse> result = service.list(null, null, null, 1, 20);

        assertThat(result.list()).isEmpty();
        assertThat(result.total()).isZero();
        verify(experimentMapper, never()).selectPage(any(), any());
    }

    // --------------------------------------------------------------------- get

    @Test
    @DisplayName("详情：实验不存在 -> 40448")
    void getRejectsMissingExperiment() {
        when(experimentMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPERIMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：他人项目的实验 -> 40448（属主来自所属项目）")
    void getRejectsForeignExperiment() {
        when(experimentMapper.selectById(42L)).thenReturn(experiment(42L, 1L));
        when(projectMapper.selectById(1L)).thenReturn(project(OTHER, 30L));

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPERIMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("详情：本人实验 -> 逐项映射，isHit/feedbackToModel 的 0/1 转 boolean")
    void getMapsOwnedExperiment() {
        when(experimentMapper.selectById(42L)).thenReturn(storedExperiment());
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));

        ExperimentResponse response = service.get(42L);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getProjectId()).isEqualTo(1L);
        assertThat(response.getOperatorId()).isEqualTo(ME);
        assertThat(response.getIsHit()).isFalse();
        assertThat(response.getFeedbackToModel()).isFalse();
        assertThat(response.getErrorDelta()).isEqualByComparingTo("50");
    }

    // ------------------------------------------------------------------- patch

    @Test
    @DisplayName("更正：请求里为 null 的字段保持原值（不传 ≠ 清空）")
    void patchKeepsOmittedFieldsUnchanged() {
        when(experimentMapper.selectById(42L)).thenReturn(storedExperiment());
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(hitJudge.judge(any(), any(), any(), any())).thenReturn(new HitJudge.Verdict(true, null));

        service.patch(42L, createRequest());

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).updateById(captor.capture());
        WetExperiment updated = captor.getValue();
        assertThat(updated.getAssayProtocol()).isEqualTo("IC50 标准方案");
        assertThat(updated.getTargetRole()).isEqualTo("WILD_TYPE");
        assertThat(updated.getUnit()).isEqualTo("nM");
        assertThat(updated.getOperator()).isEqualTo("GTE");
        assertThat(updated.getResultNum()).isEqualByComparingTo("150");
        assertThat(updated.getResultText()).isEqualTo("curves ok");
        assertThat(updated.getPredictedValue()).isEqualByComparingTo("100");
        assertThat(updated.getPredictedMetric()).isEqualTo("MOL_IC50");
        assertThat(updated.getLabName()).isEqualTo("华东实验室");
        // 判定重算：旧的 isHit=0 被判定结果覆盖
        assertThat(updated.getIsHit()).isEqualTo(1);
        // errorDelta 重算 = 150 - 100
        assertThat(updated.getErrorDelta()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("更正：改了 resultNum 会重算 errorDelta 并重新判定 isHit")
    void patchRecomputesDeltaAndJudgement() {
        when(experimentMapper.selectById(42L)).thenReturn(storedExperiment());
        when(projectMapper.selectById(1L)).thenReturn(project(ME, 30L));
        when(hitJudge.judge(any(), any(), any(), any())).thenReturn(new HitJudge.Verdict(false, null));
        ExperimentCreateRequest request = createRequest();
        request.setResultStatus("FAILED");
        request.setResultNum(new BigDecimal("180"));

        service.patch(42L, request);

        ArgumentCaptor<WetExperiment> captor = ArgumentCaptor.forClass(WetExperiment.class);
        verify(experimentMapper).updateById(captor.capture());
        WetExperiment updated = captor.getValue();
        assertThat(updated.getResultNum()).isEqualByComparingTo("180");
        assertThat(updated.getResultStatus()).isEqualTo("FAILED");
        // 180 - 100（预测值沿用原值）
        assertThat(updated.getErrorDelta()).isEqualByComparingTo("80");
        assertThat(updated.getIsHit()).isZero();
        assertThat(updated.getAssayProtocol()).isEqualTo("IC50 标准方案");
    }

    // -------------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<WetExperiment>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static AbstractWrapper<WetExperiment, ?, ?> asWrapper(Wrapper<WetExperiment> wrapper) {
        return (AbstractWrapper<WetExperiment, ?, ?>) wrapper;
    }
}
