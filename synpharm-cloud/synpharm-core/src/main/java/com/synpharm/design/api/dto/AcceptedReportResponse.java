package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 生成报告受理响应（契约 {@code AcceptedReport}，HTTP 202）。
 *
 * @author SynPharm Team
 */
@Data
public class AcceptedReportResponse {

    /** 报告 ID（报告记录已登记，正文待生成） */
    private Long reportId;

    private String reportNo;

    /** 统一任务 ID；正文生成管线未落地时为 null */
    private Long jobId;

    /** 报告正文生成状态，登记后为 PENDING */
    private String status;
}
