package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * decision_veto_rule · 业务性否决规则（07 §5.1）。
 *
 * <p><b>与 {@code constraint_rule} 是两层，不要混</b>：约束规则判的是「这个分子本身行不行」
 * （技术性淘汰，在生成/编辑阶段就会筛掉）；否决规则判的是「这个项目现在能不能选它」
 * （业务性否决，例如必测项未验证、预算超支、FTO 风险）。同一个分子完全可能技术上达标、
 * 业务上不能选。
 *
 * <p>{@code humanReadable} 是红线 R2：不允许存在用户看不到的规则。
 * {@code projectId = 0} 表示全局默认规则，用户不可改。
 *
 * @author SynPharm Team
 */
@Data
@TableName("decision_veto_rule")
public class DecisionVetoRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 0 = 全局默认规则（只读）；否则为项目级自定义规则 */
    private Long projectId;

    /** MUST_VERIFY_UNVERIFIED / BUDGET_EXCEEDED / FTO_RISK_HIGH / DATA_INCOMPLETE / HARD_RULE_VIOLATED / MANUAL_VETO */
    private String ruleCode;

    /** BLOCK 不可绕过 / WARN 可接受但需留痕 */
    private String severity;

    private String dimension;

    private BigDecimal thresholdNum;

    /** 规则参数；{@code ruleCode} 决定怎么解释 */
    private String paramsJson;

    /** 用户可见的判定说明，必填 */
    private String humanReadable;

    private Integer enabled;

    private Integer sortNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
