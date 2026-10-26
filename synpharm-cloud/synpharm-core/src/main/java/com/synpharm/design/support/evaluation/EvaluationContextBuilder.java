package com.synpharm.design.support.evaluation;

import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import com.synpharm.design.support.MetricFieldMapper;
import com.synpharm.design.support.generation.HardRuleParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 从冻结的基线快照装配评估上下文。
 *
 * <p><b>只读快照，不读项目当前状态</b>：评估结果要与编辑、决策对得上，
 * 就必须用同一把尺子。用户中途改了权重，不能让「昨天冻结的分数」和
 * 「今天的 delta」不可比。
 *
 * <p>块6 里的指标可能没有 {@code better} / 归一化区间（`project_metric` 只存项目级
 * 权重与阈值），这些元数据来自 {@code metric_definition} 字典，按指标码补齐。
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class EvaluationContextBuilder {

    private final JsonCodec jsonCodec;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final HardRuleParser hardRuleParser;

    public EvaluationDtos.EvalContext build(BaselineSnapshot snapshot) {
        List<EvaluationDtos.ScoredMetric> metrics = parseMetrics(snapshot.getMetricsJson());
        List<EvaluationDtos.HardRuleView> hardRules = parseHardRules(snapshot.getHardRulesJson());
        return new EvaluationDtos.EvalContext(
                snapshot.getProjectId() == null ? 0 : snapshot.getProjectId(),
                snapshot.getId() == null ? 0 : snapshot.getId(),
                metrics, hardRules, List.of());
    }

    // ------------------------------------------------------------------ 块6

    private List<EvaluationDtos.ScoredMetric> parseMetrics(String json) {
        List<EvaluationDtos.ScoredMetric> metrics = new ArrayList<>();
        Map<String, Object> root = jsonCodec.readMap(json);
        if (root == null || !(root.get("items") instanceof List<?> items)) {
            return metrics;
        }

        Map<String, MetricDefinition> defs = definitions();

        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            String code = stringOf(m, "metricCode");
            if (code == null || code.isBlank()) {
                continue;
            }
            MetricDefinition def = defs.get(code);

            // 快照自带 better；缺失时退回字典。两边都没有就是「无方向」，
            // MinimalEvaluator 会据此把该指标排除在综合分之外
            String better = firstNonBlank(stringOf(m, "better"), def == null ? null : def.getBetter());
            BigDecimal min = def == null ? null : def.getMinValue();
            BigDecimal max = def == null ? null : def.getMaxValue();

            metrics.add(new EvaluationDtos.ScoredMetric(
                    code,
                    decimalOf(m, "weight"),
                    better,
                    min,
                    max,
                    stringOf(m, "operator"),
                    decimalOf(m, "threshold"),
                    decimalOf(m, "tolerance"),
                    stringOf(m, "boundaryPolicy")));
        }
        return metrics;
    }

    private Map<String, MetricDefinition> definitions() {
        List<MetricDefinition> all = metricDefinitionMapper.selectList(null);
        Map<String, MetricDefinition> map = new HashMap<>();
        for (MetricDefinition def : all) {
            if (def.getMetricCode() != null) {
                map.putIfAbsent(def.getMetricCode(), def);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------ 块4

    private List<EvaluationDtos.HardRuleView> parseHardRules(String json) {
        List<EvaluationDtos.HardRuleView> views = new ArrayList<>();
        for (GenerationDtosView rule : toViews(json)) {
            // 指标码优先取 params.field（用户最常这么写），退而认领结构化 dimension
            String metricCode = MetricFieldMapper.mapField(rule.field());
            if (metricCode == null) {
                metricCode = MetricFieldMapper.asMetricCode(rule.dimension());
            }
            String label = rule.humanReadable() != null ? rule.humanReadable()
                    : metricCode != null ? metricCode : "rule#" + rule.ruleId();
            views.add(new EvaluationDtos.HardRuleView(
                    String.valueOf(rule.ruleId()),
                    metricCode,
                    label,
                    rule.operator(),
                    rule.min(),
                    rule.max(),
                    rule.tolerance(),
                    rule.boundaryPolicy()));
        }
        return views;
    }

    /** 把 HardRuleParser 的产出整理成本类需要的扁平视图 */
    private List<GenerationDtosView> toViews(String json) {
        List<GenerationDtosView> out = new ArrayList<>();
        for (var rule : hardRuleParser.parse(json)) {
            Map<String, Object> params = rule.params();
            String field = params == null ? null : String.valueOf(params.get("field"));
            if ("null".equals(field)) {
                field = null;
            }
            BigDecimal min = params == null ? null : decimalOf(params, "min");
            BigDecimal max = params == null ? null : decimalOf(params, "max");
            // 结构化阈值为空时用 params 的区间补；两边都有以结构化为准
            String operator = rule.operator();
            if (operator == null && (min != null || max != null)) {
                operator = min != null && max != null ? "BETWEEN" : min != null ? "GTE" : "LTE";
            }
            BigDecimal thresholdNum = rule.thresholdNum() != null ? rule.thresholdNum() : min;
            BigDecimal thresholdMax = rule.thresholdMax() != null ? rule.thresholdMax() : max;
            out.add(new GenerationDtosView(
                    rule.ruleId(), rule.dimension(), field, operator,
                    thresholdNum, thresholdMax, rule.tolerance(),
                    rule.boundaryPolicy(), rule.humanReadable()));
        }
        return out;
    }

    private record GenerationDtosView(
            Long ruleId, String dimension, String field, String operator,
            BigDecimal min, BigDecimal max, BigDecimal tolerance,
            String boundaryPolicy, String humanReadable) {
    }

    // ------------------------------------------------------------------ 工具

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal decimalOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
