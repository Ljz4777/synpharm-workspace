package com.synpharm.design.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 报告响应（契约 {@code Report}）。
 *
 * @author SynPharm Team
 */
@Data
public class ReportResponse {

    private Long id;

    /** 报告编号 R+yyMMddHHmmss+随机 */
    private String reportNo;

    private Long projectId;

    private Long roundId;

    private Long evaluationRunId;

    private String title;

    /** TOP_N / ROUND_SUMMARY / ADHOC */
    private String reportType;

    private Integer topN;

    private Map<String, Object> bodyJson;

    private String bodyMd;

    /**
     * 生成时的能力边界声明（哪些引擎不可用、realness 分布）。
     *
     * <p>红线 R1/R2 要求：报告必须自带能力边界，不能让读者以为所有指标都算过。
     */
    private String capabilityNote;

    private String decision;

    private Long decisionBy;

    private LocalDateTime decisionAt;

    private String decisionNote;

    /** 修订来源报告（REVISE 后重出） */
    private Long revisionOf;

    private Integer versionNo;

    private String status;

    private Long jobId;

    private LocalDateTime createdAt;
}
