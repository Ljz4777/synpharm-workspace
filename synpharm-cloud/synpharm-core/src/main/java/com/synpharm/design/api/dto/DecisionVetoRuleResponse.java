package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 否决规则响应（07 §5.1）。
 *
 * <p>{@code humanReadable} 必填是红线 R2：不允许存在用户看不到的规则。
 * {@code projectId = 0} 的规则是全局默认，只读。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionVetoRuleResponse {

    private Long id;
    private Long projectId;
    /** 0 表示全局默认规则 */
    private Boolean global;
    private String ruleCode;
    /** BLOCK 不可绕过 / WARN 可接受但需留痕 */
    private String severity;
    private String dimension;
    private BigDecimal thresholdNum;
    private String paramsJson;
    private String humanReadable;
    private Boolean enabled;
    private Integer sortNo;
    private LocalDateTime createdAt;
}