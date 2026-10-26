package com.synpharm.design.support;

import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.domain.model.EvalDimension;
import com.synpharm.design.domain.model.NumericOperator;
import com.synpharm.design.domain.model.RuleSeverity;
import com.synpharm.design.domain.model.RuleType;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 约束规则自检（{@code CONSTRAINT_RULE_INVALID} 的唯一判定处）。
 *
 * <p>「ruleType 与 params 不匹配」若不在入库前拦掉，规则会在生成阶段被静默忽略 ——
 * 用户以为设了 PAINS 过滤，实际什么都没过滤。所以每种 ruleType 的参数结构都在这里
 * 逐条对齐 {@code 18_constraint_rule.sql} 的尾注。
 *
 * <p>{@code humanReadable} 是硬要求（红线 R2：不做隐藏规则）：一条用户看不懂的约束，
 * 等于一条用户不知道存在的约束。
 *
 * @author SynPharm Team
 */
public final class ConstraintRuleValidator {

    /** 数值型参数键 */
    private static final List<String> NUMERIC_KEYS = List.of("min", "max", "thresholdNum", "thresholdMax");

    private ConstraintRuleValidator() {
    }

    /**
     * 校验单条规则。
     *
     * @return 问题列表；空表示通过
     */
    public static List<String> validate(ConstraintRuleDto rule) {
        List<String> problems = new ArrayList<>();
        if (rule == null) {
            return List.of("规则不能为 null");
        }

        RuleType type = RuleType.parse(rule.getRuleType());
        if (type == null) {
            problems.add("未知的 ruleType: " + rule.getRuleType());
            return problems;
        }
        if (!RuleSeverity.isValid(rule.getSeverity())) {
            problems.add("severity 必须是 HARD 或 SOFT，当前: " + rule.getSeverity());
        }
        if (!StringUtils.hasText(rule.getHumanReadable())) {
            problems.add("humanReadable 必填（红线 R2：不允许存在用户看不到的规则）");
        }
        if (StringUtils.hasText(rule.getDimension()) && !EvalDimension.isValid(rule.getDimension())) {
            problems.add("未知的 dimension: " + rule.getDimension());
        }
        if (StringUtils.hasText(rule.getGroupLogic())
                && !"AND".equalsIgnoreCase(rule.getGroupLogic().trim())
                && !"OR".equalsIgnoreCase(rule.getGroupLogic().trim())) {
            problems.add("groupLogic 必须是 AND 或 OR，当前: " + rule.getGroupLogic());
        }
        if (rule.getWeight() != null && rule.getWeight() < 0) {
            problems.add("weight 不能为负");
        }

        Map<String, Object> params = rule.getParams();
        if (params == null) {
            problems.add("params 不能为空");
            return problems;
        }

        switch (type) {
            case NUMERIC_RANGE -> {
                requireText(params, "field", problems);
                requireNumeric(params, "min", problems, false);
                requireNumeric(params, "max", problems, false);
                requireOrdered(params, "min", "max", problems);
            }
            case SMARTS_REQUIRED, SMARTS_FORBIDDEN -> requireText(params, "smarts", problems);
            case SCAFFOLD_REQUIRED, SCAFFOLD_FORBIDDEN -> requireText(params, "scaffoldSmiles", problems);
            case FILTER_CATALOG -> requireText(params, "catalog", problems);
            case SIMILARITY_RANGE -> {
                requireText(params, "refSmiles", problems);
                requireNumeric(params, "min", problems, false);
                requireNumeric(params, "max", problems, false);
                requireOrdered(params, "min", "max", problems);
            }
            case SIMILARITY_TO_SET -> requireNonEmptyArray(params, "setIds", problems);
            case RING_COUNT -> {
                requireNumeric(params, "min", problems, false);
                requireNumeric(params, "max", problems, false);
                requireOrdered(params, "min", "max", problems);
            }
            case SUBSTITUENT_ALLOWED -> requireNonEmptyArray(params, "allowedSmarts", problems);
            case RULE_SET -> requireText(params, "ruleSetCode", problems);
        }

        validateThresholdTriplet(rule, problems);
        return problems;
    }

    /**
     * 校验一组规则。
     *
     * @return 每条规则的问题（下标与入参一致）
     */
    public static List<List<String>> validateAll(List<ConstraintRuleDto> rules) {
        List<List<String>> all = new ArrayList<>();
        if (rules == null) {
            return all;
        }
        for (ConstraintRuleDto rule : rules) {
            all.add(validate(rule));
        }
        return all;
    }

    /** 阈值三元组：BETWEEN 要求上下界都有且有序，其余算子只要求单边阈值 */
    private static void validateThresholdTriplet(ConstraintRuleDto rule, List<String> problems) {
        NumericOperator operator = NumericOperator.parse(rule.getOperator());
        if (rule.getOperator() == null) {
            // 数值区间类规则可以用 params 里的 min/max 表达，不强制 operator
            return;
        }
        if (operator == null) {
            problems.add("未知的 operator: " + rule.getOperator());
            return;
        }
        if (rule.getThresholdNum() == null) {
            problems.add("operator=" + operator + " 时必须给出 thresholdNum");
            return;
        }
        if (operator == NumericOperator.BETWEEN) {
            if (rule.getThresholdMax() == null) {
                problems.add("operator=BETWEEN 时必须给出 thresholdMax");
            } else if (rule.getThresholdMax().compareTo(rule.getThresholdNum()) < 0) {
                problems.add("BETWEEN 的上界必须不小于下界");
            }
        }
    }

    private static void requireText(Map<String, Object> params, String key, List<String> problems) {
        Object v = params.get(key);
        if (v == null || !StringUtils.hasText(String.valueOf(v))) {
            problems.add("params." + key + " 必填");
        }
    }

    private static void requireNumeric(Map<String, Object> params, String key, List<String> problems,
                                       boolean required) {
        Object v = params.get(key);
        if (v == null) {
            if (required) {
                problems.add("params." + key + " 必填");
            }
            return;
        }
        if (!NUMERIC_KEYS.contains(key)) {
            return;
        }
        try {
            new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            problems.add("params." + key + " 必须是数字，当前: " + v);
        }
    }

    private static void requireOrdered(Map<String, Object> params, String lower, String upper,
                                       List<String> problems) {
        Object a = params.get(lower);
        Object b = params.get(upper);
        if (a == null || b == null) {
            return;
        }
        try {
            if (new BigDecimal(String.valueOf(a).trim()).compareTo(new BigDecimal(String.valueOf(b).trim())) > 0) {
                problems.add("params." + lower + " 不能大于 params." + upper);
            }
        } catch (NumberFormatException ignored) {
            // 类型错误已由 requireNumeric 报告，这里不重复报
        }
    }

    private static void requireNonEmptyArray(Map<String, Object> params, String key, List<String> problems) {
        Object v = params.get(key);
        if (v == null) {
            problems.add("params." + key + " 必填");
            return;
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                problems.add("params." + key + " 不能为空数组");
            }
            return;
        }
        if (v instanceof Object[] arr) {
            if (arr.length == 0) {
                problems.add("params." + key + " 不能为空数组");
            }
            return;
        }
        problems.add("params." + key + " 必须是数组");
    }
}
