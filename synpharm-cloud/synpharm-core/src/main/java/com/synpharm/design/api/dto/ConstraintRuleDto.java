package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 约束规则（契约 {@code ConstraintRule}），请求与响应复用。
 *
 * @author SynPharm Team
 */
@Data
public class ConstraintRuleDto {

    private Long id;

    /** 逻辑分组，同组内按 {@code groupLogic} 组合 */
    private String groupKey;

    private String groupLogic;

    @NotBlank(message = "ruleType 不能为空")
    private String ruleType;

    @NotBlank(message = "severity 不能为空")
    private String severity;

    /** SOFT 规则的扣分权重 */
    private Integer weight;

    /** 结构随 ruleType 而定，见 18_constraint_rule.sql 尾注 */
    private Map<String, Object> params;

    private String dimension;

    private String operator;

    private BigDecimal thresholdNum;

    private BigDecimal thresholdMax;

    @Size(max = 20, message = "unit 最长 20 字符")
    private String unit;

    /** 人类可读描述，直接展示给用户（红线 R2） */
    @Size(max = 500, message = "humanReadable 最长 500 字符")
    private String humanReadable;

    private Integer sortNo;

    private Boolean enabled;
}
