package com.synpharm.design.support.generation;

import com.synpharm.common.support.JsonCodec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 基线块4 硬约束的解析器（{@code baseline_snapshot.hard_rules_json} → {@link GenerationDtos.HardRule}）。
 *
 * <p><b>为什么单独成类</b>：这段逻辑踩过一个代价很高的坑 —— 快照里的 {@code paramsJson}
 * 是<b>二次编码</b>的（约束表的 {@code params} 本是 JSON 列，装配快照时又整体塞进
 * {@code hard_rules_json}，落地形态是「一个字符串，里面装着 JSON 文本」）。
 * 只按 Map 取值会静默拿到空 Map，于是所有 {@code NUMERIC_RANGE} 约束在 S6 都被当作
 * 「字段映射不上」跳过 —— 系统显示「生成成功、硬约束已执行」，而实际上一条都没判定。
 * 这类静默失效没有任何报错路径可查，只能靠独立可测的解析器 + 单测锁住。
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class HardRuleParser {

    private final JsonCodec jsonCodec;

    /**
     * @param json 基线快照的 {@code hard_rules_json}，形如 {@code {"items":[...],"logic":"AND"}}
     * @return 解析出的规则；结构不合法时返回空列表（由调用方决定是否拒绝）
     */
    public List<GenerationDtos.HardRule> parse(String json) {
        List<GenerationDtos.HardRule> rules = new ArrayList<>();
        Map<String, Object> root = jsonCodec.readMap(json);
        if (root == null) {
            return rules;
        }
        Object items = root.get("items");
        if (!(items instanceof List<?> list)) {
            return rules;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            rules.add(new GenerationDtos.HardRule(
                    longOf(m, "ruleId"),
                    stringOf(m, "dimension"),
                    stringOf(m, "operator"),
                    decimalOf(m, "thresholdNum"),
                    decimalOf(m, "thresholdMax"),
                    decimalOf(m, "tolerance"),
                    stringOf(m, "boundaryPolicy"),
                    stringOf(m, "humanReadable"),
                    stringOf(m, "ruleType"),
                    paramsOf(m)));
        }
        return rules;
    }

    /**
     * 取一条规则的参数体，兼容三种落地形态：Map、JSON 文本、缺失。
     *
     * <p>拿不到就是 {@code null}，<b>不返回空 Map</b> —— 「没有参数」和「参数为空」
     * 必须能区分，否则判空永远不成立（这正是当初那个坑的直接成因）。
     */
    Map<String, Object> paramsOf(Map<String, Object> rule) {
        Object value = rule == null ? null : rule.get("paramsJson");
        if (value instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) raw;
            return cast;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : jsonCodec.readMap(text);
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long longOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
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
