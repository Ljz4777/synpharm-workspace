package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evidence_bundle · 证据包（07 §5.1）。
 *
 * <p><b>只装引用与摘要，不装决策产物</b>（A2 口径）：包里有的是「依据哪份冻结快照、
 * 血缘怎么来的、逐条约束判成什么样、还缺什么」，没有的是「选它还是不选它」。
 * 后者属于 {@code decision_candidate}。混在一起的话，回放时无法区分
 * 「当时的证据就是这样」与「当时的结论是这样」。
 *
 * <p>{@code contentHash} 覆盖除时间外的全部内容 —— 同一份冻结快照装配两次必须同哈希，
 * 这是 {@code inputSnapshotHash} 能当回放输入指纹的前提。
 *
 * @author SynPharm Team
 */
@Data
@TableName("evidence_bundle")
public class EvidenceBundle {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 证据包编号 EB+yyMMddHHmmss+随机 */
    private String bundleNo;

    private Long projectId;

    private Long roundId;

    private Long candidateId;

    /** 需求基线：判定阈值来源 */
    private Long baselineSnapshotId;

    /** 评估冻结快照：分数来源。决策只读它，不读候选当前状态 */
    private Long evaluationFreezeId;

    /** root→当前 的 id + editReason，只存引用 */
    private String lineageJson;

    /** [{ruleId, operator, threshold, tolerance, policy, outcome, deviation, reason}] */
    private String constraintVerdictJson;

    /** [{riskCode, severity, mustVerify, source}] */
    private String riskFlagsJson;

    /** 客观遗漏项（由 must_verify 与实际数据推导），不是决策者的意见 */
    private String pendingItemsJson;

    /** 已覆盖指标数。缺数据不补零，UI 必须一并显示 */
    private Integer coversMetrics;

    /** OK / PARTIAL / INCOMPLETE */
    private String completeness;

    /** 以上内容 sha256（不含时间，保证幂等） */
    private String contentHash;

    /** FROZEN / SUPERSEDED */
    private String status;

    /** 冻结时间。这张表没有 created_at —— 冻结时间就是它被创建的时刻 */
    private LocalDateTime frozenAt;
}
