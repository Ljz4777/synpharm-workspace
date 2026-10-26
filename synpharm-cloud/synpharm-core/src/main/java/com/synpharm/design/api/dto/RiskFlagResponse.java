package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 候选的风险标记（来自项目风险关注点，随证据包冻结）。
 *
 * <p>决策页要能「逐候选逐风险点」地接受风险（D7），就必须先看得见有哪些风险点。
 * 只给一个「接受」按钮而不给清单，等于让人盲签。
 *
 * @author SynPharm Team
 */
@Data
public class RiskFlagResponse {

    private String riskCode;
    /** HIGH / MEDIUM / LOW …；接受记录里也会存一份当时的值 */
    private String severity;
    /** true = 必须实测验证，不接受「接受风险」这条路 */
    private Boolean mustVerify;
    /** 出处（项目风险关注点 / 基线 / 证据推导） */
    private String source;
}
