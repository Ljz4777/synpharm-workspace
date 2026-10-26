package com.synpharm.design.support.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.EvaluationFreeze;
import com.synpharm.design.domain.model.ProjectRiskItem;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.MoleculeEditLogMapper;
import com.synpharm.design.support.BoundaryJudge;
import com.synpharm.design.support.evaluation.EvaluationContextBuilder;
import com.synpharm.design.support.evaluation.EvaluationDtos;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D1 证据打包。
 *
 * <p>最要紧的两条断言：
 * ① <b>同一份冻结快照装配两次必须同 hash</b> —— 它是 {@code inputSnapshotHash}
 *    能当回放指纹的前提，这里不同，回放校验就没有意义；
 * ② <b>缺指标只降级为 PARTIAL，不报错</b> —— 本系统小分子 10 个指标只有 4 个算得出来，
 *    若把缺指标当异常，整个决策阶段都用不了。
 *
 * @author SynPharm Team
 */
class EvidenceBundleAssemblerTest {

    private EvidenceBundleAssembler assembler;

    @BeforeEach
    void setUp() {
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

        EvaluationContextBuilder contextBuilder = mock(EvaluationContextBuilder.class);
        // 两个指标有值（QED / PAINS_HITS），一个没值（DTI_CONFIDENCE）；一条硬约束判通过
        lenient().when(contextBuilder.build(any()))
                .thenReturn(new EvaluationDtos.EvalContext(1L, 1L,
                        List.of(
                                new EvaluationDtos.ScoredMetric("QED", new BigDecimal("0.5"), "HIGHER",
                                        null, null, null, null, null, null),
                                new EvaluationDtos.ScoredMetric("PAINS_HITS", new BigDecimal("0.3"), "LOWER",
                                        null, null, null, null, null, null),
                                new EvaluationDtos.ScoredMetric("DTI_CONFIDENCE", new BigDecimal("0.6"), "HIGHER",
                                        null, null, null, null, null, null)),
                        List.of(new EvaluationDtos.HardRuleView("1", "MOL_WT", "分子量 150~600 Da",
                                "BETWEEN", new BigDecimal("150"), new BigDecimal("600"), null, "STRICT")),
                        List.of()));

        CandidateMoleculeMapper candidateMapper = mock(CandidateMoleculeMapper.class);
        MoleculeEditLogMapper editLogMapper = mock(MoleculeEditLogMapper.class);
        lenient().when(candidateMapper.selectById(any())).thenReturn(null);
        lenient().when(editLogMapper.selectList(any())).thenReturn(List.of());

        assembler = new EvidenceBundleAssembler(jsonCodec, contextBuilder, new BoundaryJudge(),
                candidateMapper, editLogMapper);
    }

    @Test
    @DisplayName("同一份冻结快照装配两次得到同一个 contentHash")
    void contentHashIsStable() {
        CandidateMolecule candidate = candidate();
        EvaluationFreeze freeze = freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8},"
                + "\"PAINS_HITS\":{\"value\":0,\"normalized\":1.0}}");

        DecisionDtos.AssembledBundle first = assembler.assemble(candidate, freeze, snapshot(), List.of(), 0);
        DecisionDtos.AssembledBundle second = assembler.assemble(candidate(), freeze, snapshot(), List.of(), 0);

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.contentHash()).hasSize(64);
    }

    @Test
    @DisplayName("缺指标只降级为 PARTIAL，不报错；coversMetrics 如实统计")
    void missingMetricsDegradeNotFail() {
        DecisionDtos.AssembledBundle bundle = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8},"
                        + "\"PAINS_HITS\":{\"value\":0,\"normalized\":1.0}}"),
                snapshot(), List.of(), 0);

        assertThat(bundle.coversMetrics()).isEqualTo(2);
        assertThat(bundle.totalMetrics()).isEqualTo(3);
        assertThat(bundle.missingMetrics()).containsExactly("DTI_CONFIDENCE");
        assertThat(bundle.completeness()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("一个指标都没有值时完整性为 INCOMPLETE")
    void emptyScoresIncomplete() {
        DecisionDtos.AssembledBundle bundle = assembler.assemble(candidate(),
                freeze("{}"), snapshot(), List.of(), 0);

        assertThat(bundle.coversMetrics()).isZero();
        assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("硬约束引用的指标不在快照里 → NOT_EVALUATED（判不了，不当通过也不当不达标）")
    void hardRuleRecheckNotEvaluated() {
        // MOL_WT 不在 scores 里 —— 快照没记这个值，不能判通过，也不能含糊地判不达标
        DecisionDtos.AssembledBundle bundle = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8}}"),
                snapshot(), List.of(), 0);

        assertThat(bundle.verdicts()).hasSize(1);
        assertThat(bundle.verdicts().get(0).hardPassed()).isFalse();
        assertThat(bundle.verdicts().get(0).outcome())
                .isEqualTo(EvidenceBundleAssembler.OUTCOME_NOT_EVALUATED);
        assertThat(bundle.verdicts().get(0).reason()).contains("MOL_WT");
        assertThat(bundle.completeness()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("硬约束值在快照里但越界 → 真正的 FAIL（要换分子，而不是补数据）")
    void hardRuleRecheckFails() {
        // MOL_WT = 900，超出 150~600
        DecisionDtos.AssembledBundle bundle = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8},"
                        + "\"MOL_WT\":{\"value\":900,\"normalized\":null}}"),
                snapshot(), List.of(), 0);

        assertThat(bundle.verdicts()).hasSize(1);
        assertThat(bundle.verdicts().get(0).hardPassed()).isFalse();
        assertThat(bundle.verdicts().get(0).outcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(bundle.completeness()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("必须验证的风险点在没有实测记录时进入 pendingItems")
    void pendingItemsFromMustVerify() {
        List<ProjectRiskItem> risks = List.of(risk("PTM_HOTSPOT", "MEDIUM", 1));

        DecisionDtos.AssembledBundle noExperiment = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8}}"), snapshot(), risks, 0);
        DecisionDtos.AssembledBundle withExperiment = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8}}"), snapshot(), risks, 1);

        assertThat(noExperiment.pendingItems()).hasSize(1);
        assertThat(noExperiment.pendingItems().get(0).item()).contains("PTM_HOTSPOT");
        assertThat(withExperiment.pendingItems()).isEmpty();
    }

    @Test
    @DisplayName("非必须验证的风险点不进 pendingItems")
    void nonMustVerifyRiskIgnored() {
        DecisionDtos.AssembledBundle bundle = assembler.assemble(candidate(),
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8}}"),
                snapshot(), List.of(risk("AGGREGATION", "LOW", 0)), 0);

        assertThat(bundle.pendingItems()).isEmpty();
        assertThat(bundle.riskFlags()).hasSize(1);
    }

    @Test
    @DisplayName("血缘从 root 排到当前，只含 id 与来源")
    void lineageOrderedRootFirst() {
        CandidateMolecule parent = new CandidateMolecule();
        parent.setId(100L);
        parent.setOriginType("GENERATED");
        parent.setParentMolId(null);

        CandidateMolecule child = candidate();
        child.setParentMolId(100L);

        CandidateMoleculeMapper mapper = mock(CandidateMoleculeMapper.class);
        when(mapper.selectById(100L)).thenReturn(parent);
        MoleculeEditLogMapper logs = mock(MoleculeEditLogMapper.class);
        lenient().when(logs.selectList(any())).thenReturn(List.of());

        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());
        EvaluationContextBuilder builder = mock(EvaluationContextBuilder.class);
        lenient().when(builder.build(any())).thenReturn(EvaluationDtos.EvalContext.empty());

        EvidenceBundleAssembler local = new EvidenceBundleAssembler(jsonCodec, builder,
                new BoundaryJudge(), mapper, logs);

        DecisionDtos.AssembledBundle bundle = local.assemble(child,
                freeze("{\"QED\":{\"value\":0.8,\"normalized\":0.8}}"), snapshot(), List.of(), 0);

        assertThat(bundle.lineage()).extracting(DecisionDtos.LineageStep::molId)
                .containsExactly(100L, child.getId());
        assertThat(bundle.lineage().get(1).stage()).isEqualTo("EDIT");
    }

    // ------------------------------------------------------------------ 构造工具

    private static CandidateMolecule candidate() {
        CandidateMolecule c = new CandidateMolecule();
        c.setId(200L);
        c.setProjectId(1L);
        c.setRoundId(2L);
        c.setOriginType("EDIT");
        c.setBranchStatus("active");
        return c;
    }

    private static EvaluationFreeze freeze(String scoresJson) {
        EvaluationFreeze f = new EvaluationFreeze();
        f.setId(9L);
        f.setCandidateId(200L);
        f.setScoresJson(scoresJson);
        return f;
    }

    private static BaselineSnapshot snapshot() {
        BaselineSnapshot s = new BaselineSnapshot();
        s.setId(3L);
        s.setProjectId(1L);
        s.setMetricsJson("{\"items\":[]}");
        s.setHardRulesJson("{\"items\":[]}");
        return s;
    }

    private static ProjectRiskItem risk(String code, String severity, int mustVerify) {
        ProjectRiskItem r = new ProjectRiskItem();
        r.setId(1L);
        r.setProjectId(1L);
        r.setRiskCode(code);
        r.setSeverity(severity);
        r.setMustVerify(mustVerify);
        r.setSource("MANUAL");
        r.setDeleted(0);
        return r;
    }

    @SuppressWarnings("unused")
    private static String unused() {
        return anyString();
    }
}
