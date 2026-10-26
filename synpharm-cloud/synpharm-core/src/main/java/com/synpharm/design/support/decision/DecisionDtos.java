package com.synpharm.design.support.decision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策域内部数据结构（07 §6 / §7）。
 *
 * <p>这些 record 只在装配、复核、排序三处之间传递，不作为对外 DTO：
 * 对外形状由 {@code api/dto} 决定，两者分开是为了避免「内部字段一改，接口就破」。
 *
 * @author SynPharm Team
 */
public final class DecisionDtos {

    private DecisionDtos() {
    }

    /** 一条约束的复核结果（D2） */
    public record ConstraintVerdict(
            Long ruleId,
            String label,
            String metricCode,
            String operator,
            BigDecimal thresholdNum,
            BigDecimal thresholdMax,
            BigDecimal tolerance,
            String policy,
            String outcome,
            boolean hardPassed,
            BigDecimal deviation,
            String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", ruleId);
            m.put("label", label);
            m.put("metricCode", metricCode);
            m.put("operator", operator);
            m.put("thresholdNum", thresholdNum);
            m.put("thresholdMax", thresholdMax);
            m.put("tolerance", tolerance);
            m.put("policy", policy);
            m.put("outcome", outcome);
            m.put("hardPassed", hardPassed);
            m.put("deviation", deviation);
            m.put("reason", reason);
            return m;
        }
    }

    /** 风险标记：来自项目风险关注点 */
    public record RiskFlag(String riskCode, String severity, boolean mustVerify, String source) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("riskCode", riskCode);
            m.put("severity", severity);
            m.put("mustVerify", mustVerify);
            m.put("source", source);
            return m;
        }
    }

    /** 客观遗漏项：由 mustVerify 与实际实验数据推导，不是决策者的意见 */
    public record PendingItem(String item, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item);
            m.put("reason", reason);
            return m;
        }
    }

    /** 血缘一步：只存引用，不拷贝结构 */
    public record LineageStep(Long molId, String editReason, String stage) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("molId", molId);
            m.put("editReason", editReason);
            m.put("stage", stage);
            return m;
        }
    }

    /** 一个指标点在本次决策里的取值 */
    public record MetricPoint(
            String code,
            BigDecimal weight,
            BigDecimal value,
            BigDecimal normalized) {

        public boolean hasValue() {
            return value != null;
        }
    }

    /** 证据包草稿 + 参与排序的指标点 */
    public record AssembledBundle(
            List<LineageStep> lineage,
            List<ConstraintVerdict> verdicts,
            List<RiskFlag> riskFlags,
            List<PendingItem> pendingItems,
            List<MetricPoint> metrics,
            int coversMetrics,
            int totalMetrics,
            List<String> missingMetrics,
            String completeness,
            String contentHash) {
    }

    /**
     * 参与 hash 计算的内容（不含时间）。
     * 单独抽出来是为了让「同一份冻结快照装配两次得到同一 hash」这条性质可被单测直接验证。
     */
    public record Assembly(
            List<LineageStep> lineage,
            List<ConstraintVerdict> verdicts,
            List<RiskFlag> riskFlags,
            List<PendingItem> pendingItems,
            List<MetricPoint> metrics,
            int coversMetrics,
            int totalMetrics,
            List<String> missingMetrics,
            String completeness) {

        public List<Map<String, Object>> lineageMaps() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (LineageStep s : lineage) {
                list.add(s.toMap());
            }
            return list;
        }

        public List<Map<String, Object>> verdictMaps() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ConstraintVerdict v : verdicts) {
                list.add(v.toMap());
            }
            return list;
        }

        public List<Map<String, Object>> riskFlagMaps() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (RiskFlag r : riskFlags) {
                list.add(r.toMap());
            }
            return list;
        }

        public List<Map<String, Object>> pendingItemMaps() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (PendingItem p : pendingItems) {
                list.add(p.toMap());
            }
            return list;
        }

        /** 指标点 → {@code [{code, weight, value}]}，顺序固定，保证 hash 稳定 */
        public List<Map<String, Object>> metricMaps() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (MetricPoint p : metrics) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", p.code());
                m.put("weight", p.weight());
                m.put("value", p.value());
                list.add(m);
            }
            return list;
        }
    }

    /** 一次否决命中 */
    public record VetoHit(String ruleCode, String severity, String message, String evidenceRef) {
    }

    /** 决策候选的最终形态（D4 + D5 之后） */
    public record RankedCandidate(
            Long candidateId,
            String bucket,
            Integer rankNo,
            BigDecimal weightedScore,
            int coversMetrics,
            List<String> missingMetrics,
            String outcome,
            String rejectReason,
            boolean blocked,
            int mustVerifyCount,
            List<VetoHit> vetoes) {
    }

    /** 排序 + 名额分配的产物 */
    public record RankOutcome(
            List<RankedCandidate> candidates,
            int selectedCount,
            int backupCount,
            int rejectedCount,
            int capacity,
            List<String> warnings) {
    }
}
