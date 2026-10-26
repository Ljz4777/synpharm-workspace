package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命中判定（{@code wet_experiment.is_hit}）的<strong>单一来源</strong>。
 *
 * <p><b>判定口径</b>（设计文档只写了「按 is_hit 阈值判定」，未定义阈值来源，
 * 本类把口径固定下来并在《设计域详细设计》中登记）：
 * <ol>
 *   <li>由 {@code predicted_metric} 查 {@code metric_definition}，得到该指标的<b>维度</b>；</li>
 *   <li>取项目 {@code goal_metric_json} 中<b>同维度</b>的优化目标 {@code {operator, threshold, unit}}；</li>
 *   <li>用目标的 {@code operator} 判定 {@code result_num} 是否满足阈值。</li>
 * </ol>
 *
 * <p><b>判不出来就是 null，不猜</b>：缺数值结果、未记录预测指标、指标未注册、
 * 项目未设该维度目标、目标缺阈值 —— 一律返回 {@code null}（{@code is_hit} 本来就可空），
 * 因为把「未知」写成 {@code false} 会直接污染下游的命中率统计。
 *
 * <p><b>不等式结果只认「可证伪」的方向</b>：实验结果是 {@code >10000 nM} 这类有界值而非精确值时，
 * 真值只落在区间内。只有当区间与阈值的关系能<b>证明</b>结论时才给判定，否则仍是 null
 * （例：要求 {@code <=100}、实测 {@code >10000} ⇒ 可证不命中；要求 {@code <=100}、实测 {@code >50} ⇒ 未知）。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitJudge {

    private static final Set<String> CRITERION_OPERATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ");

    private final MetricDefinitionMapper metricDefinitionMapper;

    /**
     * 判定结果。
     *
     * @param hit    命中与否；{@code null} = 不可判定
     * @param reason 不可判定的原因（进日志，便于排查「为什么 is_hit 是空的」）
     */
    public record Verdict(Boolean hit, String reason) {
    }

    /**
     * 判定一条实验是否命中。
     *
     * @param goalMetric 项目优化目标（{@code goal_metric_json} 已解析结果，可为 null）
     * @param resultNum  实测数值
     * @param valueOp    实验结果的算子（GT/GTE/LT/LTE/EQ，空视作精确值）
     * @param metricCode 预测指标码
     */
    public Verdict judge(Map<String, Object> goalMetric, BigDecimal resultNum, String valueOp, String metricCode) {
        if (resultNum == null) {
            return new Verdict(null, "无数值结果");
        }
        if (!StringUtils.hasText(metricCode)) {
            return new Verdict(null, "未记录预测指标，无法确定判定维度");
        }
        MetricDefinition metric = findMetric(metricCode);
        if (metric == null) {
            return new Verdict(null, "指标未在 metric_definition 注册: " + metricCode);
        }
        if (goalMetric == null || goalMetric.isEmpty()) {
            return new Verdict(null, "项目未配置优化目标(goal_metric_json)");
        }
        Object entryRaw = findDimensionEntry(goalMetric, metric.getDimension());
        if (!(entryRaw instanceof Map<?, ?> entry)) {
            return new Verdict(null, "项目未设该维度的优化目标: " + metric.getDimension());
        }
        Object thresholdRaw = entry.get("threshold");
        Object operatorRaw = entry.get("operator");
        if (!(thresholdRaw instanceof Number) || !StringUtils.hasText(operatorRaw == null ? null : operatorRaw.toString())) {
            return new Verdict(null, "优化目标缺少 threshold 或 operator: " + metric.getDimension());
        }
        BigDecimal threshold = new BigDecimal(thresholdRaw.toString());
        String criterionOp = operatorRaw.toString().trim().toUpperCase();

        // 目标算子不认识 ≠ 关系不可证明：两者都会返回 null，但排查方向完全不同，reason 要说清是哪一个
        String unknownCriterion = CRITERION_OPERATORS.contains(criterionOp)
                ? ""
                : "未知的优化目标算子 " + criterionOp + "（检查项目 goal_metric_json）；";

        Boolean hit = compare(resultNum, valueOp, criterionOp, threshold);
        if (hit == null) {
            return new Verdict(null, unknownCriterion + "实测 " + describeValue(resultNum, valueOp) + " 与阈值 "
                    + criterionOp + " " + threshold + " 的关系不可证明");
        }
        return new Verdict(hit, null);
    }

    /** 把实测值还原成「= 100」「> 100」「>= 100」这类可读形式（日志用） */
    private String describeValue(BigDecimal resultNum, String valueOp) {
        String op = valueOp == null ? null : valueOp.trim().toUpperCase();
        if (!StringUtils.hasText(op) || "EQ".equals(op) || "=".equals(op)) {
            return "= " + resultNum;
        }
        return switch (op) {
            case "GT" -> "> " + resultNum;
            case "GTE" -> ">= " + resultNum;
            case "LT" -> "< " + resultNum;
            case "LTE" -> "<= " + resultNum;
            default -> op + " " + resultNum;
        };
    }

    // ------------------------------------------------------------------ 内部

    private MetricDefinition findMetric(String metricCode) {
        List<MetricDefinition> found = metricDefinitionMapper.selectList(
                Wrappers.lambdaQuery(MetricDefinition.class)
                        .eq(MetricDefinition::getMetricCode, metricCode)
                        .last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /** {@code goal_metric_json} 以维度为键：{@code {"AFFINITY":{"operator":"LTE","threshold":100,"unit":"nM"}}} */
    private Object findDimensionEntry(Map<String, Object> goal, String dimension) {
        if (dimension == null) {
            return null;
        }
        Object exact = goal.get(dimension);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Object> e : goal.entrySet()) {
            if (dimension.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 判定 {@code value}（可能有界）是否满足 {@code criterionOp threshold}。
     *
     * <p>真值 v 所在区间：{@code GT} ⇒ {@code (v,∞)}、{@code GTE} ⇒ {@code [v,∞)}、
     * {@code LT} ⇒ {@code (-∞,v)}、{@code LTE} ⇒ {@code (-∞,v]}。
     * 只有「区间内所有取值都满足」或「都不满足」时才给结论 ——
     * <strong>半开区间的端点恰好落在阈值上时通常是不可判定的</strong>
     * （例：实测 {@code ≥100}、要求 {@code >100}，真值可能就是 100）。
     *
     * @return {@code true}/{@code false} = 可判定；{@code null} = 不可证明
     */
    private Boolean compare(BigDecimal value, String valueOp, String criterionOp, BigDecimal threshold) {
        String op = valueOp == null ? null : valueOp.trim().toUpperCase();
        if (!StringUtils.hasText(op) || "EQ".equals(op) || "=".equals(op)) {
            return satisfies(value, criterionOp, threshold);
        }
        boolean atLeast = !lt(value, threshold);      // value >= threshold
        boolean above = gt(value, threshold);         // value >  threshold
        boolean atMost = !gt(value, threshold);       // value <= threshold
        boolean below = lt(value, threshold);         // value <  threshold

        return switch (op) {
            // v > value：下界严格，端点落上阈值即可证
            case "GT" -> switch (criterionOp) {
                case "GT", "GTE" -> atLeast ? Boolean.TRUE : null;
                case "LT", "LTE", "EQ" -> atLeast ? Boolean.FALSE : null;
                default -> unknownOperator(criterionOp);
            };
            // v >= value：下界非严格，端点等于阈值时留给「未知」
            case "GTE" -> switch (criterionOp) {
                case "GT" -> above ? Boolean.TRUE : null;
                case "GTE" -> atLeast ? Boolean.TRUE : null;
                case "LT" -> atLeast ? Boolean.FALSE : null;
                case "LTE", "EQ" -> above ? Boolean.FALSE : null;
                default -> unknownOperator(criterionOp);
            };
            // v < value：上界严格
            case "LT" -> switch (criterionOp) {
                case "LT", "LTE" -> atMost ? Boolean.TRUE : null;
                case "GT", "GTE", "EQ" -> atMost ? Boolean.FALSE : null;
                default -> unknownOperator(criterionOp);
            };
            // v <= value：上界非严格
            case "LTE" -> switch (criterionOp) {
                case "LT" -> below ? Boolean.TRUE : null;
                case "LTE" -> atMost ? Boolean.TRUE : null;
                case "GT" -> atMost ? Boolean.FALSE : null;
                case "GTE", "EQ" -> below ? Boolean.FALSE : null;
                default -> unknownOperator(criterionOp);
            };
            default -> {
                log.warn("未知的实测算子 {}，按不可判定处理", valueOp);
                yield null;
            }
        };
    }

    private boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    private boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    private Boolean satisfies(BigDecimal value, String criterionOp, BigDecimal threshold) {
        int cmp = value.compareTo(threshold);
        return switch (criterionOp) {
            case "GT" -> cmp > 0;
            case "GTE" -> cmp >= 0;
            case "LT" -> cmp < 0;
            case "LTE" -> cmp <= 0;
            case "EQ" -> cmp == 0;
            default -> unknownOperator(criterionOp);
        };
    }

    private Boolean unknownOperator(String op) {
        log.warn("未知的优化目标算子 {}，按不可判定处理", op);
        return null;
    }
}
