package com.synpharm.design.support;

import com.synpharm.design.api.dto.ConstraintRuleDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束规则自检测试。
 *
 * <p>这些用例的价值不在「参数校验」本身，而在于：校验漏掉的规则会在生成阶段被**静默忽略**，
 * 用户以为设了 PAINS 过滤，实际什么都没过滤 —— 这种错误不会有任何报错。
 *
 * @author SynPharm Team
 */
class ConstraintRuleValidatorTest {

    private static ConstraintRuleDto rule(String type, Map<String, Object> params) {
        ConstraintRuleDto dto = new ConstraintRuleDto();
        dto.setRuleType(type);
        dto.setSeverity("HARD");
        dto.setParams(params);
        dto.setHumanReadable("人类可读描述");
        return dto;
    }

    private static ConstraintRuleDto rule(String type, Map<String, Object> params, String humanReadable) {
        ConstraintRuleDto dto = rule(type, params);
        dto.setHumanReadable(humanReadable);
        return dto;
    }

    @Test
    @DisplayName("合法规则：无问题")
    void acceptsValidRule() {
        assertThat(ConstraintRuleValidator.validate(
                rule("NUMERIC_RANGE", Map.of("field", "MolWt", "min", 200, "max", 550)))).isEmpty();
    }

    @Test
    @DisplayName("未知 ruleType：直接拒绝")
    void rejectsUnknownRuleType() {
        assertThat(ConstraintRuleValidator.validate(rule("NOT_A_TYPE", Map.of())))
                .anyMatch(p -> p.contains("未知的 ruleType"));
    }

    @Test
    @DisplayName("humanReadable 缺失：拒绝（红线 R2 不做隐藏规则）")
    void rejectsMissingHumanReadable() {
        assertThat(ConstraintRuleValidator.validate(
                rule("SMARTS_REQUIRED", Map.of("smarts", "S(=O)(=O)N"), "  ")))
                .anyMatch(p -> p.contains("humanReadable"));
    }

    @Test
    @DisplayName("NUMERIC_RANGE 缺 field：拒绝")
    void rejectsNumericRangeWithoutField() {
        assertThat(ConstraintRuleValidator.validate(
                rule("NUMERIC_RANGE", Map.of("min", 1, "max", 2))))
                .contains("params.field 必填");
    }

    @Test
    @DisplayName("NUMERIC_RANGE min 大于 max：拒绝")
    void rejectsInvertedRange() {
        assertThat(ConstraintRuleValidator.validate(
                rule("NUMERIC_RANGE", Map.of("field", "MolWt", "min", 550, "max", 200))))
                .anyMatch(p -> p.contains("不能大于"));
    }

    @Test
    @DisplayName("NUMERIC_RANGE 非数字阈值：拒绝")
    void rejectsNonNumericThreshold() {
        assertThat(ConstraintRuleValidator.validate(
                rule("NUMERIC_RANGE", Map.of("field", "MolWt", "min", "abc"))))
                .anyMatch(p -> p.contains("必须是数字"));
    }

    @Test
    @DisplayName("SMARTS_REQUIRED 缺 smarts：拒绝")
    void rejectsSmartsWithoutPattern() {
        assertThat(ConstraintRuleValidator.validate(rule("SMARTS_REQUIRED", Map.of())))
                .contains("params.smarts 必填");
    }

    @Test
    @DisplayName("SCAFFOLD_* 用 scaffoldSmiles 而不是 smarts")
    void scaffoldTypesRequireScaffoldSmiles() {
        assertThat(ConstraintRuleValidator.validate(
                rule("SCAFFOLD_FORBIDDEN", Map.of("smarts", "C1CCCCC1"))))
                .contains("params.scaffoldSmiles 必填");
        assertThat(ConstraintRuleValidator.validate(
                rule("SCAFFOLD_FORBIDDEN", Map.of("scaffoldSmiles", "C1CCCCC1")))).isEmpty();
    }

    @Test
    @DisplayName("SUBSTITUENT_ALLOWED 空数组：拒绝")
    void rejectsEmptyAllowedList() {
        assertThat(ConstraintRuleValidator.validate(
                rule("SUBSTITUENT_ALLOWED", Map.of("allowedSmarts", List.of()))))
                .contains("params.allowedSmarts 不能为空数组");
    }

    @Test
    @DisplayName("SIMILARITY_TO_SET 要求数组而非标量")
    void similarityToSetRequiresArray() {
        assertThat(ConstraintRuleValidator.validate(
                rule("SIMILARITY_TO_SET", Map.of("setIds", 7))))
                .contains("params.setIds 必须是数组");
    }

    @Test
    @DisplayName("operator=BETWEEN 缺上界：拒绝")
    void betweenRequiresUpperBound() {
        ConstraintRuleDto dto = rule("NUMERIC_RANGE", Map.of("field", "MolWt"));
        dto.setOperator("BETWEEN");
        dto.setThresholdNum(new BigDecimal("200"));

        assertThat(ConstraintRuleValidator.validate(dto))
                .contains("operator=BETWEEN 时必须给出 thresholdMax");
    }

    @Test
    @DisplayName("operator=BETWEEN 上下界颠倒：拒绝")
    void betweenRejectsInvertedBounds() {
        ConstraintRuleDto dto = rule("NUMERIC_RANGE", Map.of("field", "MolWt"));
        dto.setOperator("BETWEEN");
        dto.setThresholdNum(new BigDecimal("550"));
        dto.setThresholdMax(new BigDecimal("200"));

        assertThat(ConstraintRuleValidator.validate(dto))
                .contains("BETWEEN 的上界必须不小于下界");
    }

    @Test
    @DisplayName("单边算子只需下界")
    void singleSidedOperatorNeedsOneBound() {
        ConstraintRuleDto dto = rule("RING_COUNT", Map.of("min", 1, "max", 5));
        dto.setOperator("LTE");
        dto.setThresholdNum(new BigDecimal("5"));

        assertThat(ConstraintRuleValidator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("severity / dimension / groupLogic 非法值：逐条报出")
    void reportsInvalidEnumerations() {
        ConstraintRuleDto dto = rule("RING_COUNT", Map.of("min", 1, "max", 5));
        dto.setSeverity("MAYBE");
        dto.setDimension("MAGIC");
        dto.setGroupLogic("XOR");

        assertThat(ConstraintRuleValidator.validate(dto))
                .anyMatch(p -> p.contains("severity"))
                .anyMatch(p -> p.contains("dimension"))
                .anyMatch(p -> p.contains("groupLogic"));
    }

    @Test
    @DisplayName("validateAll 下标与入参一一对应")
    void validateAllKeepsIndexAlignment() {
        List<List<String>> all = ConstraintRuleValidator.validateAll(List.of(
                rule("SMARTS_REQUIRED", Map.of("smarts", "C")),
                rule("SMARTS_REQUIRED", Map.of()),
                rule("RING_COUNT", Map.of("min", 1, "max", 5))));

        assertThat(all).hasSize(3);
        assertThat(all.get(0)).isEmpty();
        assertThat(all.get(1)).isNotEmpty();
        assertThat(all.get(2)).isEmpty();
    }
}
