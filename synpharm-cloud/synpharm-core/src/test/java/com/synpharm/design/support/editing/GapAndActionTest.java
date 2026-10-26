package com.synpharm.design.support.editing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.EditActionMapping;
import com.synpharm.design.support.evaluation.EvaluationDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E1 缺口诊断 + E2 动作匹配 + E3 影响面。
 *
 * <p>这三个是纯规则判断，最容易被「改一下阈值试试」弄坏，
 * 而弄坏之后的症状是「推荐了一个会打坏已达标的动作」—— 代价很高。
 *
 * @author SynPharm Team
 */
class GapAndActionTest {

    private final ActionMatcher matcher = new ActionMatcher(new JsonCodec(new ObjectMapper()));

    private static EvaluationDtos.MetricScore score(String value, Boolean passed, String note) {
        return new EvaluationDtos.MetricScore("x",
                value == null ? null : new BigDecimal(value),
                null, passed, null, note);
    }

    private static EditActionMapping mapping(String dimension, String primary, String fallback,
                                             String modality, String sideEffects) {
        EditActionMapping m = new EditActionMapping();
        m.setGapDimension(dimension);
        m.setGapCause("");
        m.setPrimaryAction(primary);
        m.setFallbackAction(fallback);
        m.setAppliesToModality(modality);
        m.setSideEffectsJson(sideEffects);
        m.setSuccessRate(new BigDecimal("0.41"));
        m.setSampleSize(0);
        m.setPriority(10);
        m.setEnabled(1);
        m.setDeleted(0);
        return m;
    }

    // ------------------------------------------------------------------ E1

    @Test
    @DisplayName("已达标维度进保护名单，未达标且可编辑的进缺口")
    void diagnoseSplitsProtectedAndGaps() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "SA_SCORE", score("8.5", false, "合成难度偏高"),
                "DTI_CONFIDENCE", score("0.9", true, null));

        EditDtos.Diagnosis d = new GapDiagnostician().diagnose(scores);

        assertThat(d.noGaps()).contains("AFFINITY");
        assertThat(d.gaps()).anySatisfy(g -> assertThat(g.metricCode()).isEqualTo("SA_SCORE"));
        assertThat(d.recommendedOrder()).contains("SYNTHESIZABILITY");
    }

    @Test
    @DisplayName("DEVELOPABILITY（QED/LogP/MW）归 unfixable：没有可信的定向动作，不假装能改")
    void developabilityIsUnfixable() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "QED", score("0.2", false, "低于阈值"));

        EditDtos.Diagnosis d = new GapDiagnostician().diagnose(scores);

        assertThat(d.gaps()).isEmpty();
        assertThat(d.unfixable()).singleElement()
                .satisfies(g -> assertThat(g.dimension()).isEqualTo("DEVELOPABILITY"));
    }

    @Test
    @DisplayName("算不出值的指标 → NOT_EVALUATED 且难度 HARD（与「算了不及格」区分）")
    void notEvaluatedIsHarder() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "SA_SCORE", score(null, null, "计算层未返回该指标"));

        EditDtos.Diagnosis d = new GapDiagnostician().diagnose(scores);

        assertThat(d.gaps()).singleElement().satisfies(g -> {
            assertThat(g.gapType()).isEqualTo("NOT_EVALUATED");
            assertThat(g.difficulty()).isEqualTo("HARD");
            assertThat(g.reason()).contains("未算出值");
        });
    }

    @Test
    @DisplayName("没有可编辑手段的维度进 unfixable，并说明「修不了」")
    void unmappedDimensionIsUnfixable() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "PAINS_HITS", score("3", false, "PAINS 命中"));

        EditDtos.Diagnosis d = new GapDiagnostician().diagnose(scores);

        assertThat(d.gaps()).isEmpty();
        assertThat(d.unfixable()).singleElement().satisfies(g -> {
            assertThat(g.dimension()).isEqualTo("SAFETY");
            assertThat(g.difficulty()).isEqualTo("UNFIXABLE");
        });
        assertThat(d.recommendedOrder()).isEmpty();
        assertThat(d.note()).contains("修不了");
    }

    @Test
    @DisplayName("归因恒为 null，并给出「为什么没有归因」的说明")
    void attributionIsAlwaysNull() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "SA_SCORE", score("8.5", false, "合成难度偏高"));

        EditDtos.Gap gap = new GapDiagnostician().diagnose(scores).gaps().get(0);

        assertThat(gap.attribution()).isNull();
        assertThat(String.valueOf(gap.toMap().get("attributionNote"))).contains("归因模型");
    }

    @Test
    @DisplayName("可修缺口按难度排序，EASY 在前")
    void sortedByDifficulty() {
        Map<String, EvaluationDtos.MetricScore> scores = Map.of(
                "SA_SCORE", score("8.5", false, "难合成"),
                "SA_SCORE2", score(null, null, "未算"));
        // 两个不同指标映射到不同维度，确认排序稳定
        Map<String, EvaluationDtos.MetricScore> two = Map.of(
                "SA_SCORE", score("8.5", false, "难合成"),
                "TM_STABILITY", score("20", false, "热稳定性不足"));

        EditDtos.Diagnosis d = new GapDiagnostician().diagnose(two);
        assertThat(d.gaps()).hasSize(2);
        assertThat(d.recommendedOrder()).hasSize(2);
        assertThat(diagnostician().diagnose(scores).gaps()).isNotEmpty();
    }

    private GapDiagnostician diagnostician() {
        return new GapDiagnostician();
    }

    // ------------------------------------------------------------------ E2

    @Test
    @DisplayName("形式不匹配的动作绝不出现（给小分子推人源化是纯噪音）")
    void modalityFiltered() {
        List<EditActionMapping> mappings = List.of(
                mapping("IMMUNOGENICITY", "HUMANIZATION", null, "ANTIBODY,BISPECIFIC", null));
        List<EditDtos.Gap> gaps = List.of(new EditDtos.Gap("IMMUNOGENICITY_SCORE", "IMMUNOGENICITY",
                "THRESHOLD_NOT_MET", BigDecimal.ONE, null, null, true, "MEDIUM", "x"));

        assertThat(matcher.match(gaps, mappings, "SMALL_MOLECULE")).isEmpty();
    }

    @Test
    @DisplayName("* 通配对所有形式适用；主选与次选都给出")
    void wildcardAndFallback() {
        List<EditActionMapping> mappings = List.of(
                mapping("AFFINITY", "MUTATION", "INTERFACE_REDESIGN", "*", null));
        List<EditDtos.Gap> gaps = List.of(new EditDtos.Gap("DTI_CONFIDENCE", "AFFINITY",
                "THRESHOLD_NOT_MET", BigDecimal.ONE, null, null, true, "MEDIUM", "x"));

        List<EditDtos.ActionOption> options = matcher.match(gaps, mappings, "SMALL_MOLECULE");

        assertThat(options).hasSize(2);
        assertThat(options.get(0).actionCode()).isEqualTo("MUTATION");
        assertThat(options.get(1).actionCode()).isEqualTo("INTERFACE_REDESIGN");
        assertThat(options.get(1).isFallback()).isTrue();
        // sampleSize=0 → 成功率是估计值，必须标注
        assertThat(options.get(0).toMap().get("rateIsEstimate")).isEqualTo(true);
    }

    @Test
    @DisplayName("同一维度最多给 TOP_N 个动作")
    void topNLimit() {
        List<EditActionMapping> mappings = List.of(
                mapping("AFFINITY", "A1", "A2", "*", null),
                mapping("AFFINITY", "B1", "B2", "*", null));
        List<EditDtos.Gap> gaps = List.of(new EditDtos.Gap("DTI_CONFIDENCE", "AFFINITY",
                "THRESHOLD_NOT_MET", BigDecimal.ONE, null, null, true, "MEDIUM", "x"));

        assertThat(matcher.match(gaps, mappings, "SMALL_MOLECULE")).hasSize(ActionMatcher.TOP_N);
    }

    @Test
    @DisplayName("已停用的映射规则不参与匹配")
    void disabledMappingIgnored() {
        EditActionMapping off = mapping("AFFINITY", "MUTATION", null, "*", null);
        off.setEnabled(0);
        List<EditDtos.Gap> gaps = List.of(new EditDtos.Gap("DTI_CONFIDENCE", "AFFINITY",
                "THRESHOLD_NOT_MET", BigDecimal.ONE, null, null, true, "MEDIUM", "x"));

        assertThat(matcher.match(gaps, List.of(off), "SMALL_MOLECULE")).isEmpty();
    }

    // ------------------------------------------------------------------ E3

    @Test
    @DisplayName("MUST_DROP 打已达标维度 → BLOCK（不能拿已到手的东西去换）")
    void blocksWhenWouldBreakPassedDimension() {
        EditDtos.ActionOption option = new EditDtos.ActionOption("IMMUNOGENICITY", "HUMANIZATION",
                false, new BigDecimal("0.35"), 0, Map.of("AFFINITY", "MUST_DROP"));

        EditDtos.Impact impact = new ImpactAssessor().assess(option,
                List.of("AFFINITY"), dim -> "AFFINITY".equals(dim));

        assertThat(impact.guardResult()).isEqualTo(ImpactAssessor.BLOCK);
        assertThat(impact.blockReason()).contains("AFFINITY");
        assertThat(impact.protectedOnes()).singleElement()
                .satisfies(p -> assertThat(p.predictedDelta()).isNull());
    }

    @Test
    @DisplayName("MUST_DROP 但该维度本来就没达标 → WARN 放行")
    void warnsWhenDimensionAlreadyFailed() {
        EditDtos.ActionOption option = new EditDtos.ActionOption("IMMUNOGENICITY", "HUMANIZATION",
                false, new BigDecimal("0.35"), 0, Map.of("AFFINITY", "MUST_DROP"));

        EditDtos.Impact impact = new ImpactAssessor().assess(option, List.of(), dim -> false);

        assertThat(impact.guardResult()).isEqualTo(ImpactAssessor.WARN);
        assertThat(impact.blockReason()).isNull();
    }

    @Test
    @DisplayName("无副作用声明的动作 → PASS 但提示改完必须复核全部已达标维度")
    void unknownSideEffectsStillWarnsToRecheck() {
        EditDtos.ActionOption unknown = new EditDtos.ActionOption("STABILITY", "LINKER",
                false, null, 0, Map.of());

        EditDtos.Impact impact = new ImpactAssessor().assessUnknown(unknown);

        assertThat(impact.guardResult()).isEqualTo(ImpactAssessor.PASS);
        assertThat(impact.protectedOnes().get(0).note()).contains("复核");
    }

    @Test
    @DisplayName("WATCH 且维度已达标 → WARN（不是 BLOCK，WATCH 只是提醒）")
    void watchOnlyWarns() {
        EditDtos.ActionOption watch = new EditDtos.ActionOption("STABILITY", "LINKER",
                false, null, 0, Map.of("AFFINITY", "WATCH"));

        assertThat(new ImpactAssessor().assess(watch, List.of("AFFINITY"), d -> true).guardResult())
                .isEqualTo(ImpactAssessor.WARN);
    }
}
