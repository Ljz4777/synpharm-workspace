package com.synpharm.design.support.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.synpharm.design.support.BoundaryJudge;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 最小评估：归一化 + 硬约束 + 综合分。
 *
 * <p>锁住三件事：
 * <ul>
 *   <li>没有好坏方向的指标（MOL_WT）不参与综合分，否则「更重」会变成「更好」；</li>
 *   <li>算不出来的指标进 missingMetrics 且<strong>不填 0</strong>；</li>
 *   <li>weightCoverage 如实反映「综合分只代表多少权重」。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
class MinimalEvaluatorTest {

    private final MinimalEvaluator evaluator = new MinimalEvaluator(new BoundaryJudge());

    private static EvaluationDtos.ScoredMetric metric(String code, String better, String weight,
                                                      String min, String max) {
        return new EvaluationDtos.ScoredMetric(code,
                weight == null ? null : new BigDecimal(weight), better,
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                null, null, null, null);
    }

    private static EvaluationDtos.EvalContext ctx(List<EvaluationDtos.ScoredMetric> metrics,
                                                 List<EvaluationDtos.HardRuleView> rules) {
        return new EvaluationDtos.EvalContext(1L, 2L, metrics, rules, List.of());
    }

    // ------------------------------------------------------------------ 归一化

    @Test
    @DisplayName("HIGHER：值落在区间中点 → 归一化 0.5")
    void normalizeHigher() {
        assertThat(evaluator.normalize(new BigDecimal("5"), "HIGHER",
                new BigDecimal("0"), new BigDecimal("10")))
                .isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("LOWER：值越小归一化越高，且不越界")
    void normalizeLower() {
        assertThat(evaluator.normalize(new BigDecimal("0"), "LOWER",
                new BigDecimal("0"), new BigDecimal("10")))
                .isEqualByComparingTo("1");
        assertThat(evaluator.normalize(new BigDecimal("10"), "LOWER",
                new BigDecimal("0"), new BigDecimal("10")))
                .isEqualByComparingTo("0");
        // 越界钳制，不能出现 -0.5 或 1.5 这种会污染综合分的值
        assertThat(evaluator.normalize(new BigDecimal("20"), "LOWER",
                new BigDecimal("0"), new BigDecimal("10")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("NONE / 缺区间 → 归一化为 null（描述量不参与打分）")
    void normalizeNone() {
        assertThat(evaluator.normalize(new BigDecimal("500"), "NONE",
                new BigDecimal("0"), new BigDecimal("1000"))).isNull();
        assertThat(evaluator.normalize(new BigDecimal("500"), null,
                new BigDecimal("0"), new BigDecimal("1000"))).isNull();
        assertThat(evaluator.normalize(new BigDecimal("500"), "HIGHER", null, null)).isNull();
        // 区间退化（min == max）不能除零
        assertThat(evaluator.normalize(new BigDecimal("5"), "HIGHER",
                new BigDecimal("5"), new BigDecimal("5"))).isNull();
    }

    // ------------------------------------------------------------------ 综合分

    @Test
    @DisplayName("综合分只按可算权重归一化，并给出覆盖率")
    void totalScoreAndCoverage() {
        List<EvaluationDtos.ScoredMetric> metrics = List.of(
                metric("QED", "HIGHER", "0.5", "0", "1"),          // 算得出
                metric("DTI_CONFIDENCE", "HIGHER", "0.5", "0", "1") // 算不出
        );
        EvaluationDtos.EvalContext c = ctx(metrics, List.of());

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("QED", new BigDecimal("1"));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(values, c, Map.of());

        // QED=1 → 归一化 1 → 综合分 100（只来自 QED）
        assertThat(outcome.totalScore()).isEqualByComparingTo("100");
        // 但覆盖率只有 0.5：这个 100 分只代表一半权重
        assertThat(outcome.weightCoverage()).isEqualByComparingTo("0.5");
        assertThat(outcome.missingMetrics()).containsExactly("DTI_CONFIDENCE");
    }

    @Test
    @DisplayName("MOL_WT 这类描述量不参与综合分，即使配了权重")
    void descriptiveMetricExcluded() {
        List<EvaluationDtos.ScoredMetric> metrics = List.of(
                metric("MOL_WT", "NONE", "1.0", "0", "1000"),
                metric("QED", "HIGHER", "1.0", "0", "1"));
        Map<String, BigDecimal> values = Map.of(
                "MOL_WT", new BigDecimal("900"),
                "QED", new BigDecimal("0.6"));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(values, ctx(metrics, List.of()), Map.of());

        // 只看 QED=0.6；MOL_WT=900 若参与会把分数拉到 0.8 以上
        assertThat(outcome.totalScore()).isEqualByComparingTo("60");
        // 覆盖率是「可算权重 / 全部权重」= 1.0 / 2.0
        assertThat(outcome.weightCoverage()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("全部指标算不出来 → 综合分为 null，不是 0")
    void allMissingGivesNull() {
        List<EvaluationDtos.ScoredMetric> metrics = List.of(
                metric("QED", "HIGHER", "1.0", "0", "1"));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(Map.of(), ctx(metrics, List.of()), Map.of());

        assertThat(outcome.totalScore()).isNull();
        assertThat(outcome.missingMetrics()).containsExactly("QED");
        assertThat(outcome.scores().get("QED").value()).isNull();
        // 缺失值不能伪装成 0：LogP=0 和「算不出 LogP」含义完全不同
        assertThat(outcome.scores().get("QED").normalized()).isNull();
    }

    // ------------------------------------------------------------------ 硬约束

    @Test
    @DisplayName("硬约束未达标 → hardPassed=false 且列出违例")
    void hardRuleViolation() {
        List<EvaluationDtos.HardRuleView> rules = List.of(new EvaluationDtos.HardRuleView(
                "47", "MOL_WT", "MW 150~600", "BETWEEN",
                new BigDecimal("150"), new BigDecimal("600"), null, BoundaryJudge.STRICT));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(
                Map.of("MOL_WT", new BigDecimal("32")),
                ctx(List.of(metric("MOL_WT", "NONE", "0", "0", "1000")), rules), Map.of());

        assertThat(outcome.hardPassed()).isFalse();
        assertThat(outcome.violations()).hasSize(1);
        assertThat(outcome.violations().get(0)).contains("MW 150~600");
    }

    @Test
    @DisplayName("硬约束用到的指标算不出来 → 记 skipped，不当作通过")
    void hardRuleSkippedWhenMetricMissing() {
        List<EvaluationDtos.HardRuleView> rules = List.of(new EvaluationDtos.HardRuleView(
                "47", "MOL_WT", "MW 150~600", "BETWEEN",
                new BigDecimal("150"), new BigDecimal("600"), null, BoundaryJudge.STRICT));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(Map.of(), ctx(List.of(), rules), Map.of());

        assertThat(outcome.skippedRules()).anySatisfy(s -> assertThat(s).contains("MOL_WT"));
        // 关键：没判定不等于不通过，也不等于通过
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.hardPassed()).isTrue();
    }

    @Test
    @DisplayName("硬约束映射不到指标 → 记 SKIPPED_UNMAPPED_RULE")
    void hardRuleUnmapped() {
        List<EvaluationDtos.HardRuleView> rules = List.of(new EvaluationDtos.HardRuleView(
                "88", null, "某条没人能判的规则", "GTE",
                BigDecimal.ONE, null, null, BoundaryJudge.STRICT));

        EvaluationDtos.Outcome outcome = evaluator.evaluate(Map.of(), ctx(List.of(), rules), Map.of());

        assertThat(outcome.skippedRules()).anySatisfy(s -> assertThat(s).contains("SKIPPED_UNMAPPED_RULE"));
    }

    // ------------------------------------------------------------------ 指标阈值

    @Test
    @DisplayName("指标配置了阈值时给出 passed 与 risk")
    void metricThresholdGivesPassedAndRisk() {
        EvaluationDtos.ScoredMetric m = new EvaluationDtos.ScoredMetric(
                "QED", new BigDecimal("1.0"), "HIGHER",
                new BigDecimal("0"), new BigDecimal("1"),
                "GTE", new BigDecimal("0.7"), null, BoundaryJudge.STRICT);

        EvaluationDtos.Outcome fail = evaluator.evaluate(
                Map.of("QED", new BigDecimal("0.3")), ctx(List.of(m), List.of()), Map.of());
        assertThat(fail.scores().get("QED").passed()).isFalse();
        assertThat(fail.scores().get("QED").risk()).isEqualTo("HIGH");

        EvaluationDtos.Outcome pass = evaluator.evaluate(
                Map.of("QED", new BigDecimal("0.9")), ctx(List.of(m), List.of()), Map.of());
        assertThat(pass.scores().get("QED").passed()).isTrue();
        assertThat(pass.scores().get("QED").risk()).isEqualTo("LOW");
    }
}
