package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * 决策候选明细响应（07 §5.1）。
 *
 * <p>{@code rankNo} 为空的候选在 FAIL 桶 —— 给淘汰对象编名次会把「没通过」
 * 伪装成「排在后面」。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionCandidateResponse {

    private Long candidateId;
    private String moleculeName;

    /**
     * 生成阶段产出的分子通常没有名字（{@code molecule_name} 允许为空）。
     * 只给 id 的话，界面上两行都是「候选 #101 / #102」，用户无法分辨 ——
     * 所以把结构信息带出来，至少能看清「是哪个分子」。
     */
    private String smiles;

    private BigDecimal mw;

    /** FULL_PASS / TOLERANCE / FAIL */
    private String bucket;

    private Integer rankNo;
    private BigDecimal weightedScore;
    private Integer coversMetrics;
    private List<String> missingMetrics;

    /** SELECTED / BACKUP / REJECTED / DEFERRED */
    private String outcome;

    /** FILTER_REASON 码（stage=DECISION） */
    private String rejectReason;

    private Boolean manualOverride;
    private String overrideReason;

    /** 已接受的风险点（D7）；空表示没有接受过任何风险 */
    private List<RiskAcceptanceResponse> acceptances;

    /** 证据包里的风险点清单；「接受风险」要照着它来点 */
    private List<RiskFlagResponse> risks;

    private List<VetoHitResponse> vetoes;
}