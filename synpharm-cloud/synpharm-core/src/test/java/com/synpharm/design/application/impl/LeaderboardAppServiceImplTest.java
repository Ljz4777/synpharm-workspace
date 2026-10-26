package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.LeaderboardResponse;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.EvaluationResult;
import com.synpharm.design.domain.model.EvaluationRun;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationResultMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationRunMapper;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排行榜测试。
 *
 * <p>四组不能靠"点一下页面"发现的问题：
 * <ol>
 *   <li><b>偏移上限是硬拒绝</b>（409 USE_CURSOR 而不是悄悄变慢）—— 性能悬崖最怕"能跑但慢"；</li>
 *   <li><b>{@code sortBy} 白名单</b> —— 名字最终拼进 ORDER BY，参数化救不了它；</li>
 *   <li><b>红线 R1</b>：{@code weightsSnapshot} 必须来自 run 的快照、{@code redistributed} 必须说明
 *       哪些指标没算出来及其权重去向，否则同一批数据的名次无法解释或复现；</li>
 *   <li><b>键集模式不返回 total</b>（规范 §4.2）—— 每次翻页 COUNT(*) 正是该规则要避免的。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardAppServiceImplTest {

    private static final long ME = 100L;
    private static final long PROJECT = 700L;
    private static final long ROUND = 800L;
    private static final long RUN = 900L;

    @Mock private DesignProjectMapper projectMapper;
    @Mock private CandidateMoleculeMapper candidateMapper;
    @Mock private EvaluationResultMapper resultMapper;
    @Mock private EvaluationRunMapper runMapper;
    @Mock private MetricDefinitionMapper metricDefinitionMapper;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private LeaderboardAppServiceImpl service;

    @BeforeEach
    void setUp() {
        register(DesignProject.class);
        register(CandidateMolecule.class);
        register(EvaluationResult.class);
        register(EvaluationRun.class);
        register(MetricDefinition.class);
        CurrentUserTestSupport.login(ME);
        when(projectMapper.selectById(PROJECT)).thenReturn(project());
        when(runMapper.selectList(any())).thenReturn(List.of(run()));
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(
                metric("AFFINITY_SCORE", "结合亲和力", "pIC50", "HIGHER"),
                metric("ADMET_SCORE", "ADMET", "score", "HIGHER"),
                metric("VINA_DOCKING", "对接", "kcal/mol", "LOWER")));
        when(resultMapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static void register(Class<?> entity) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
    }

    // ------------------------------------------------------------------ 守卫

    @Test
    @DisplayName("偏页 > 1000：409 USE_CURSOR，且一次库都不查（硬拒绝，不是悄悄变慢）")
    void rejectsDeepOffset() {
        assertThatThrownBy(() -> service.leaderboard(PROJECT, ROUND, null, null, null, null, 21, 50))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USE_CURSOR);
        verify(candidateMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("偏页恰好 1000 仍允许（边界含等号）")
    void allowsOffsetAtLimit() {
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.leaderboard(PROJECT, ROUND, null, null, null, null, 20, 50)).isNotNull();
    }

    @Test
    @DisplayName("roundId 缺失：400（排行榜主体是某一轮的候选）")
    void rejectsMissingRoundId() {
        assertThatThrownBy(() -> service.leaderboard(PROJECT, null, null, null, null, null, 1, 50))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("他人项目：404（不泄露项目是否存在）")
    void rejectsForeignProject() {
        DesignProject other = project();
        other.setUserId(999L);
        when(projectMapper.selectById(PROJECT)).thenReturn(other);

        assertThatThrownBy(() -> service.leaderboard(PROJECT, ROUND, null, null, null, null, 1, 50))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("sortBy 不在本轮启用指标内：400，且不把它拼进 SQL")
    void rejectsUnknownSortField() {
        assertThatThrownBy(() ->
                service.leaderboard(PROJECT, ROUND, "TOTAL_SCORE; DROP TABLE", null, null, null, 1, 50))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(candidateMapper, never()).selectList(any());
    }

    // -------------------------------------------------------------- 偏移分页

    @Test
    @DisplayName("偏移模式：返回 total/page/pageSize/hasNext，且不含 nextAfter*")
    void offsetModeReturnsTotal() {
        when(candidateMapper.selectCount(any())).thenReturn(3L);
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate(1L, "10.5"), candidate(2L, "9.0")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, "desc", null, null, 1, 2);

        assertThat(r.getTotal()).isEqualTo(3L);
        assertThat(r.getPage()).isEqualTo(1L);
        assertThat(r.getPageSize()).isEqualTo(2L);
        assertThat(r.getHasNext()).isFalse();
        assertThat(r.getNextAfterScore()).isNull();
        assertThat(r.getNextAfterId()).isNull();
        assertThat(r.getList()).hasSize(2);
    }

    @Test
    @DisplayName("偏移模式：多取一条判断 hasNext，返回体里不带那一条")
    void detectsHasNextWithExtraRow() {
        when(candidateMapper.selectList(any())).thenReturn(
                List.of(candidate(1L, "10"), candidate(2L, "9"), candidate(3L, "8")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, "desc", null, null, 1, 2);

        assertThat(r.getHasNext()).isTrue();
        assertThat(r.getList()).hasSize(2);
    }

    // -------------------------------------------------------------- 键集分页

    @Test
    @DisplayName("键集模式：返回 nextAfterScore/nextAfterId，且 total 必须为 null（规范 §4.2）")
    void keysetModeOmitsTotal() {
        when(candidateMapper.selectList(any())).thenReturn(
                List.of(candidate(11L, "8.5"), candidate(12L, "7.0")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, "desc",
                new BigDecimal("9.0"), 10L, 1, 50);

        assertThat(r.getTotal()).isNull();
        assertThat(r.getPage()).isNull();
        assertThat(r.getNextAfterScore()).isEqualByComparingTo("7.0");
        assertThat(r.getNextAfterId()).isEqualTo(12L);
        // 键集模式不该发 COUNT
        verify(candidateMapper, never()).selectCount(any());
    }

    // ------------------------------------------------------- R1：权重与缺失指标

    @Test
    @DisplayName("weightsSnapshot 取自 run 的权重快照（不是指标定义里的默认值）")
    void weightsSnapshotComesFromRun() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate(1L, "5")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, null, null, null, 1, 50);

        assertThat(r.getWeightsSnapshot())
                .containsEntry("AFFINITY_SCORE", new BigDecimal("0.4"))
                .containsEntry("ADMET_SCORE", new BigDecimal("0.35"))
                .containsEntry("VINA_DOCKING", new BigDecimal("0.25"));
    }

    @Test
    @DisplayName("指标值批量填充：结果表只查 2 次（填充 1 次 + 缺失原因 1 次），与页大小/缺失指标数无关")
    void fillsMetricsWithSingleQuery() {
        when(candidateMapper.selectList(any())).thenReturn(
                List.of(candidate(1L, "10"), candidate(2L, "9"), candidate(3L, "8")));
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(1L, "AFFINITY_SCORE", "8.2"), result(1L, "ADMET_SCORE", "0.7"),
                result(2L, "AFFINITY_SCORE", "7.5"), result(3L, "ADMET_SCORE", "0.6")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, null, null, null, 1, 50);

        // 2 次 = fillMetrics(1) + failureReasons(1)；缺失 2 个指标也没有额外查询
        verify(resultMapper, times(2)).selectList(any());
        assertThat(r.getList()).hasSize(3);
        // 候选 1：有 AFFINITY_SCORE + ADMET_SCORE，缺 VINA_DOCKING
        assertThat(r.getList().get(0).getMetrics())
                .containsEntry("AFFINITY_SCORE", new BigDecimal("8.2"))
                .containsEntry("ADMET_SCORE", new BigDecimal("0.7"));
        assertThat(r.getList().get(0).getUnavailableMetrics()).containsExactly("VINA_DOCKING");
        // 候选 3：只有 ADMET → AFFINITY_SCORE 与 VINA_DOCKING 标为不可用
        assertThat(r.getList().get(2).getUnavailableMetrics())
                .containsExactlyInAnyOrder("AFFINITY_SCORE", "VINA_DOCKING");
    }

    @Test
    @DisplayName("redistributed：本页完全没出现的指标要说明原因与权重去向（红线 R1）")
    void explainsMissingMetrics() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate(1L, "10")));
        when(resultMapper.selectList(any())).thenReturn(List.of(result(1L, "AFFINITY_SCORE", "8.2")));
        // describeMetricFailure 的失败样本查询
        when(resultMapper.selectList(any())).thenReturn(List.of(result(1L, "AFFINITY_SCORE", "8.2")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, null, null, null, 1, 50);

        assertThat(r.getRedistributed()).extracting("metricCode")
                .containsExactlyInAnyOrder("ADMET_SCORE", "VINA_DOCKING");
        assertThat(r.getRedistributed()).allSatisfy(x -> {
            assertThat(x.getReason()).isNotBlank();
            assertThat(x.getRedistributedWeight()).isNotNull();
        });
    }

    @Test
    @DisplayName("没有评估运行时：仍返回候选（按 total_score），权重快照为空而不是崩")
    void worksWithoutEvaluationRun() {
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate(1L, "3.3")));

        LeaderboardResponse r = service.leaderboard(PROJECT, ROUND, null, null, null, null, 1, 50);

        assertThat(r.getList()).hasSize(1);
        assertThat(r.getWeightsSnapshot()).isEmpty();
        assertThat(r.getMetricColumns()).isEmpty();
        assertThat(r.getList().get(0).getUnavailableMetrics()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static DesignProject project() {
        DesignProject p = new DesignProject();
        p.setId(PROJECT);
        p.setUserId(ME);
        p.setStatus("ACTIVE");
        return p;
    }

    private static EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setId(RUN);
        run.setProjectId(PROJECT);
        run.setRoundId(ROUND);
        run.setEnabledMetrics("AFFINITY_SCORE,ADMET_SCORE,VINA_DOCKING");
        run.setMetricWeights("{\"AFFINITY_SCORE\":0.4,\"ADMET_SCORE\":0.35,\"VINA_DOCKING\":0.25}");
        run.setStatus("SUCCEEDED");
        return run;
    }

    private static MetricDefinition metric(String code, String name, String unit, String better) {
        MetricDefinition d = new MetricDefinition();
        d.setMetricCode(code);
        d.setMetricName(name);
        d.setUnit(unit);
        d.setBetter(better);
        // 定义表里的默认权重刻意与 run 快照不同，用来证明快照优先
        d.setWeight(new BigDecimal("0.99"));
        return d;
    }

    private static CandidateMolecule candidate(long id, String totalScore) {
        CandidateMolecule c = new CandidateMolecule();
        c.setId(id);
        c.setProjectId(PROJECT);
        c.setRoundId(ROUND);
        c.setMoleculeName("MOL-" + id);
        c.setModalityCode("SMALL_MOLECULE");
        c.setReviewStatus("QUALIFIED");
        c.setTotalScore(totalScore == null ? null : new BigDecimal(totalScore));
        c.setHardPassed(1);
        c.setDeleted(0);
        return c;
    }

    private static EvaluationResult result(long moleculeId, String metricCode, String value) {
        EvaluationResult r = new EvaluationResult();
        r.setId(moleculeId * 100 + metricCode.hashCode() % 100);
        r.setRunId(RUN);
        r.setProjectId(PROJECT);
        r.setMoleculeId(moleculeId);
        r.setMetricCode(metricCode);
        r.setValueNum(new BigDecimal(value));
        r.setSuccess(1);
        return r;
    }
}
