package com.synpharm.design.support.evaluation;

import com.synpharm.design.support.BoundaryJudge;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 最小评估（06 §5.1）：把「候选 + 冻结基线」算成可冻结的分数集合。
 *
 * <p><b>与完整评估体系的边界</b>：这里只做理化指标 + 硬约束判定 + 权重归一化综合分。
 * 不做亲和力预测、免疫原性预测、置信度、适用域、结构归因 ——
 * 那些字段一律留空，{@code note} 里写清为什么空着，而不是塞 0 冒充。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>算不出来就说算不出来</b>：进 {@code missingMetrics}，不填 0。
 *       LogP=0 和「LogP 算不出」在决策阶段含义完全不同。</li>
 *   <li><b>判不了就说判不了</b>：进 {@code skippedRules}，绝不当作通过。</li>
 *   <li><b>没有好坏方向的指标不参与综合分</b>：MOL_WT 只是描述量，
 *       强行归一化会让「分子更重」变成「分数更高」。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class MinimalEvaluator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCORE_SCALE = 3;

    private final BoundaryJudge boundaryJudge;

    /**
     * 纯函数式评估：不触达 IO，便于单测与回放。
     *
     * @param values 已算出的指标值（指标码 → 值）；缺失即「算不出来」
     * @param context 来自冻结基线快照的评分配置与硬约束
     * @param engineVersions 各指标引擎版本，原样写进快照
     */
    public EvaluationDtos.Outcome evaluate(Map<String, BigDecimal> values,
                                           EvaluationDtos.EvalContext context,
                                           Map<String, Object> engineVersions) {

        Map<String, EvaluationDtos.MetricScore> scores = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> skipped = new ArrayList<>(context.skippedRuleCodes());
        List<String> violations = new ArrayList<>();

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        BigDecimal configuredWeightTotal = BigDecimal.ZERO;

        // ---------------- 逐指标评分 ----------------
        for (EvaluationDtos.ScoredMetric metric : context.metrics()) {
            if (metric.weight() != null && metric.weight().signum() > 0) {
                configuredWeightTotal = configuredWeightTotal.add(metric.weight());
            }
            BigDecimal value = values.get(metric.code());
            if (value == null) {
                missing.add(metric.code());
                scores.put(metric.code(), new EvaluationDtos.MetricScore(
                        metric.code(), null, null, null, null, "计算层未返回该指标"));
                continue;
            }

            BigDecimal normalized = normalize(value, metric.better(), metric.minValue(), metric.maxValue());
            Boolean passed = null;
            String risk = null;
            String note = null;

            if (metric.operator() != null && metric.threshold() != null) {
                BoundaryJudge.Verdict verdict = boundaryJudge.judge(
                        value, metric.operator(), metric.threshold(), metric.threshold(),
                        metric.tolerance(), metric.boundaryPolicy());
                passed = verdict.isHardPassed();
                risk = riskOf(verdict);
                note = verdict.getReason();
            }

            scores.put(metric.code(), new EvaluationDtos.MetricScore(
                    metric.code(), value, normalized, passed, risk, note));

            if (normalized != null && metric.weight() != null && metric.weight().signum() > 0) {
                weightedSum = weightedSum.add(normalized.multiply(metric.weight()));
                weightTotal = weightTotal.add(metric.weight());
            }
        }

        // ---------------- 硬约束引用但不在指标块里的指标 ----------------
        // MolWt 这类描述量通常只做硬约束、不做优化目标，所以不在块6 的指标里。
        // 若不把它的值也记进 scores，决策阶段复核硬约束时就会「查不到值」→ 无法判定
        // → 整批候选被否决，而且错误信息会指向「分子不行」而不是「快照里没这个值」。
        // 这里只记录值，不给权重（不参与综合分）。
        for (EvaluationDtos.HardRuleView rule : context.hardRules()) {
            String code = rule.metricCode();
            if (code == null || scores.containsKey(code)) {
                continue;
            }
            BigDecimal value = values.get(code);
            if (value == null) {
                continue;
            }
            BoundaryJudge.Verdict verdict = boundaryJudge.judge(
                    value, rule.operator(), rule.min(), rule.max(),
                    rule.tolerance(), rule.policy());
            scores.put(code, new EvaluationDtos.MetricScore(
                    code, value, null, verdict.isHardPassed(), riskOf(verdict),
                    "仅用于硬约束判定；不在基线指标块内，不计入综合分"));
        }

        // ---------------- 硬约束判定 ----------------
        boolean hardPassed = true;
        for (EvaluationDtos.HardRuleView rule : context.hardRules()) {
            String code = rule.metricCode();
            if (code == null) {
                // 映射不上指标：没检查 ≠ 检查通过
                skipped.add("SKIPPED_UNMAPPED_RULE:" + rule.code());
                continue;
            }
            BigDecimal value = values.get(code);
            if (value == null) {
                skipped.add("SKIPPED_NO_METRIC:" + code);
                continue;
            }
            BoundaryJudge.Verdict verdict = boundaryJudge.judge(
                    value, rule.operator(), rule.min(), rule.max(),
                    rule.tolerance(), rule.policy());
            if (!verdict.isHardPassed()) {
                hardPassed = false;
                violations.add(rule.label() + "：" + value + "（" + verdict.getReason() + "）");
            }
        }

        BigDecimal totalScore = weightTotal.signum() <= 0
                ? null
                : weightedSum.divide(weightTotal, 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);

        // 可算权重 / 全部配置权重。低于 1 时综合分只代表部分指标，
        // 前端与决策阶段必须据此打折看待，而不是当成满配结论
        BigDecimal weightCoverage = configuredWeightTotal.signum() <= 0
                ? null
                : weightTotal.divide(configuredWeightTotal, 4, RoundingMode.HALF_UP);

        return new EvaluationDtos.Outcome(scores, hardPassed, totalScore, weightCoverage,
                List.copyOf(missing), List.copyOf(skipped), List.copyOf(violations),
                engineVersions == null ? Map.of() : engineVersions);
    }

    /**
     * 归一化到 0~1（越界钳制）。
     *
     * <p>{@code better} 缺失或为 {@code NONE} 时返回 {@code null}：
     * 描述型指标（分子量、TPSA）没有「越大越好」的语义，
     * 把它算进综合分等于把「更重」当成「更好」。
     */
    BigDecimal normalize(BigDecimal value, String better, BigDecimal min, BigDecimal max) {
        if (value == null || min == null || max == null) {
            return null;
        }
        String direction = better == null ? "NONE" : better.trim().toUpperCase();
        if (!"HIGHER".equals(direction) && !"LOWER".equals(direction)) {
            return null;
        }
        BigDecimal span = max.subtract(min);
        if (span.signum() <= 0) {
            return null;
        }
        BigDecimal raw = "HIGHER".equals(direction)
                ? value.subtract(min)
                : max.subtract(value);
        BigDecimal ratio = raw.divide(span, 6, RoundingMode.HALF_UP);
        if (ratio.signum() < 0) {
            return BigDecimal.ZERO;
        }
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return ratio.setScale(5, RoundingMode.HALF_UP);
    }

    private String riskOf(BoundaryJudge.Verdict verdict) {
        if (BoundaryJudge.FAIL.equals(verdict.getOutcome())) {
            return "HIGH";
        }
        if (BoundaryJudge.PASS_WITH_WARNING.equals(verdict.getOutcome())) {
            return "MEDIUM";
        }
        if (BoundaryJudge.ESCALATE.equals(verdict.getOutcome())) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
