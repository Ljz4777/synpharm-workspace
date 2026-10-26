package com.synpharm.design.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 风险接受记录（D7）。
 *
 * <p>{@code severityAtAccept} 是接受当时的严重度快照：风险等级事后被改过，
 * 也能看出当时是按什么接受下来的。
 *
 * @author SynPharm Team
 */
@Data
public class RiskAcceptanceResponse {

    private Long id;
    private Long candidateId;
    private String riskCode;
    private String severityAtAccept;
    private String reason;
    private Long acceptedBy;
    private LocalDateTime acceptedAt;
    /** null = 本决策内长期有效 */
    private LocalDate validUntil;
}
