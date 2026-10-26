package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 否决规则新建/修改请求（07 §8，E2）。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionVetoRuleRequest {

    /** MUST_VERIFY_UNVERIFIED / BUDGET_EXCEEDED / FTO_RISK_HIGH / DATA_INCOMPLETE / HARD_RULE_VIOLATED / MANUAL_VETO */
    @NotBlank(message = "ruleCode 不能为空")
    @Size(max = 40, message = "ruleCode 最长 40 字符")
    private String ruleCode;

    /** BLOCK / WARN；缺省 BLOCK */
    private String severity;

    private String dimension;

    private BigDecimal thresholdNum;

    /** 规则参数 JSON 文本；ruleCode 决定怎么解释 */
    private String paramsJson;

    /** 用户可见的判定说明，必填（红线 R2） */
    @NotBlank(message = "humanReadable 不能为空：不允许存在用户看不到的规则")
    @Size(max = 500, message = "humanReadable 最长 500 字符")
    private String humanReadable;

    private Boolean enabled;

    private Integer sortNo;
}