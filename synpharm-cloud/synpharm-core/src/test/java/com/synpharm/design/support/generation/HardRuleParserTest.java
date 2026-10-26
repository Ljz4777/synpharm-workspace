package com.synpharm.design.support.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.synpharm.common.support.JsonCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 基线块4 硬约束解析。
 *
 * <p><b>这是一条回归测试</b>：曾经因为 {@code paramsJson} 二次编码没解开，
 * S6 把所有 {@code NUMERIC_RANGE} 约束当作「字段映射不上」静默跳过，
 * 于是甲醛（MW=30）能通过「MW 150~600」并被判为合格候选。
 * 该 bug 全程无异常、无日志，只有一条 SKIPPED 提示，只能靠这个单测兜住。
 *
 * @author SynPharm Team
 */
class HardRuleParserTest {

    private final HardRuleParser parser =
            new HardRuleParser(new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    @DisplayName("paramsJson 为 JSON 文本（真实快照形态）时必须能解出 field/min/max")
    void parsesDoubleEncodedParams() {
        // 照抄 baseline_snapshot.hard_rules_json 的真实落地形态
        String json = """
                {"items": [{"ruleId": 47, "ruleType": "NUMERIC_RANGE", "humanReadable": "MW 150~600",
                  "weight": 10, "boundaryPolicy": "STRICT", "dimension": null, "operator": null,
                  "thresholdNum": null, "thresholdMax": null, "tolerance": null,
                  "paramsJson": "{\\"max\\": 600, \\"min\\": 150, \\"field\\": \\"MolWt\\"}"}],
                 "logic": "AND", "setNo": "CS1"}
                """;

        List<GenerationDtos.HardRule> rules = parser.parse(json);

        assertThat(rules).hasSize(1);
        GenerationDtos.HardRule rule = rules.get(0);
        assertThat(rule.ruleType()).isEqualTo("NUMERIC_RANGE");
        assertThat(rule.params()).isNotNull();
        assertThat(rule.params()).containsEntry("field", "MolWt");
        assertThat(new BigDecimal(String.valueOf(rule.params().get("min"))))
                .isEqualByComparingTo("150");
        assertThat(new BigDecimal(String.valueOf(rule.params().get("max"))))
                .isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("paramsJson 已是 Map（结构更正的快照）时同样能解出")
    void parsesInlineMap() {
        String json = """
                {"items": [{"ruleId": 1, "ruleType": "NUMERIC_RANGE",
                  "paramsJson": {"field": "LogP", "max": 5}}]}
                """;

        List<GenerationDtos.HardRule> rules = parser.parse(json);

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).params()).containsEntry("field", "LogP");
    }

    @Test
    @DisplayName("没有参数时返回 null，而不是空 Map（否则判空永远不成立）")
    void returnsNullWhenParamsMissing() {
        assertThat(parser.paramsOf(Map.of())).isNull();
        assertThat(parser.paramsOf(Map.of("paramsJson", ""))).isNull();
        assertThat(parser.paramsOf(Map.of("paramsJson", "   "))).isNull();
    }

    @Test
    @DisplayName("结构字段（dimension/operator/threshold）与 ruleType 一并透出")
    void parsesStructuredFields() {
        String json = """
                {"items": [{"ruleId": 9, "dimension": "affinity", "operator": "GTE",
                  "thresholdNum": 7.5, "thresholdMax": 9.0, "tolerance": 0.2,
                  "boundaryPolicy": "TOLERANT", "humanReadable": "KD ≥ 7.5",
                  "ruleType": "NUMERIC_RANGE", "paramsJson": "{}"}]}
                """;

        GenerationDtos.HardRule rule = parser.parse(json).get(0);

        assertThat(rule.ruleId()).isEqualTo(9L);
        assertThat(rule.dimension()).isEqualTo("affinity");
        assertThat(rule.operator()).isEqualTo("GTE");
        assertThat(rule.thresholdNum()).isEqualByComparingTo("7.5");
        assertThat(rule.thresholdMax()).isEqualByComparingTo("9.0");
        assertThat(rule.tolerance()).isEqualByComparingTo("0.2");
        assertThat(rule.boundaryPolicy()).isEqualTo("TOLERANT");
        assertThat(rule.humanReadable()).isEqualTo("KD ≥ 7.5");
    }

    @Test
    @DisplayName("空 / 非法 JSON 返回空列表，由调用方决定拒绝（不静默造规则）")
    void toleratesBadInput() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("{}")).isEmpty();
        assertThat(parser.parse("{\"items\": \"not-a-list\"}")).isEmpty();
    }
}
