package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * risk_acceptance · 风险接受（07 §5.1）。
 *
 * <p>粒度是<b>逐候选逐风险点</b>，不是「整个候选接受一次」：一个候选可能有三个风险，
 * 决策者只愿意接受其中两个，第三个必须补数据。整体接受会把这个区别抹掉。
 *
 * <p>{@code reason} 必填 —— 没有理由的接受等于没记录。
 * {@code mustVerify} 项不允许接受（E7 的校验），只能转 {@code DEFERRED}。
 *
 * @author SynPharm Team
 */
@Data
@TableName("risk_acceptance")
public class RiskAcceptance {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long decisionId;

    private Long candidateId;

    private String riskCode;

    /** 接受时的严重度快照：风险等级之后被改过也能看出当时是按什么接受的 */
    private String severityAtAccept;

    /** 接受理由（必填） */
    private String reason;

    private Long acceptedBy;

    private LocalDateTime acceptedAt;

    /** 接受有效期（可为空：空表示一直有效）；表里有该列，模型不省略以免读回丢字段 */
    private LocalDateTime validUntil;
}
