package com.synpharm.design.support.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小评估的内部数据结构。
 *
 * <p>与 {@code GenerationDtos} 同一取舍：互相引用且都很小，放一起比拆十个文件好读。
 *
 * @author SynPharm Team
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    /**
     * 单个指标的评估结果。
     *
     * @param code       指标码
     * @param value      原始值；{@code null} 表示算不出来
     * @param normalized 归一化到 0~1；{@code null} 表示该指标没有「越大越好/越小越好」语义
     *                   （如 MOL_WT 只是描述量），或值缺失
     * @param passed     是否达标；{@code null} 表示未配置判定条件，无法判定
     * @param risk       风险等级：HIGH / MEDIUM / LOW
     * @param note       说明（未判定的原因、越界说明等）
     */
    public record MetricScore(
            String code,
            BigDecimal value,
            BigDecimal normalized,
            Boolean passed,
            String risk,
            String note
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", value);
            m.put("normalized", normalized);
            m.put("passed", passed);
            if (risk != null) {
                m.put("risk", risk);
            }
            if (note != null) {
                m.put("note", note);
            }
            return m;
        }
    }

    /**
     * 一次评估的完整产出。
     *
     * @param scores         指标 → 评分
     * @param hardPassed     全部可判定的硬约束是否通过
     * @param totalScore     综合分 0~100；无任何可归一化指标时为 {@code null}
     * @param weightCoverage 可算权重占比 0~1；{@code totalScore} 只代表这部分
     * @param missingMetrics 权重里有但算不出来的指标（诚实记账）
     * @param skippedRules   未能判定的规则码（没检查 ≠ 检查通过）
     * @param violations     硬约束违例明细
     * @param engineVersions 各指标引擎版本
     */
    public record Outcome(
            Map<String, MetricScore> scores,
            boolean hardPassed,
            BigDecimal totalScore,
            BigDecimal weightCoverage,
            List<String> missingMetrics,
            List<String> skippedRules,
            List<String> violations,
            Map<String, Object> engineVersions
    ) {
        public Map<String, Object> scoresAsMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            scores.forEach((k, v) -> m.put(k, v.toMap()));
            return m;
        }
    }

    /** 参与评分的指标配置（来自基线块6 + metric_definition 元数据） */
    public record ScoredMetric(
            String code,
            BigDecimal weight,
            String better,
            BigDecimal minValue,
            BigDecimal maxValue,
            String operator,
            BigDecimal threshold,
            BigDecimal tolerance,
            String boundaryPolicy
    ) {
    }

    /** 一条硬约束的评估视图 */
    public record HardRuleView(
            String code,
            String metricCode,
            String label,
            String operator,
            BigDecimal min,
            BigDecimal max,
            BigDecimal tolerance,
            String policy
    ) {
    }

    /** 评估上下文：全部来自冻结的基线快照，不读项目当前状态 */
    public record EvalContext(
            long projectId,
            long baselineSnapshotId,
            List<ScoredMetric> metrics,
            List<HardRuleView> hardRules,
            List<String> skippedRuleCodes
    ) {
        public static EvalContext empty() {
            return new EvalContext(0, 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }
}
