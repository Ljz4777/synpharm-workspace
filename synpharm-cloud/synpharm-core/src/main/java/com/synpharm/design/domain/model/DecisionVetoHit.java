package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * decision_veto_hit · 否决命中明细（07 §5.1）。
 *
 * <p>每条命中都要能指回证据包里的具体字段（{@code evidenceRef}）：
 * 只说「被否决了」而不说「依据哪一条、哪份数据」，用户无法判断该补数据还是该换分子。
 *
 * @author SynPharm Team
 */
@Data
@TableName("decision_veto_hit")
public class DecisionVetoHit {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long decisionId;

    private Long candidateId;

    private String ruleCode;

    /** BLOCK / WARN */
    private String severity;

    private String message;

    /** 指向证据包里具体字段，便于追溯；如 {@code pendingItems[0]} */
    private String evidenceRef;

    private LocalDateTime createdAt;
}
