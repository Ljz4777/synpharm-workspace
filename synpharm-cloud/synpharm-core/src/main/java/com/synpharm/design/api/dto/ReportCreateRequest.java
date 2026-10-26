package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成报告请求（契约 {@code ReportCreateRequest}）。
 *
 * @author SynPharm Team
 */
@Data
public class ReportCreateRequest {

    @NotNull(message = "projectId 不能为空")
    private Long projectId;

    private Long roundId;

    private Long evaluationRunId;

    /** TOP_N / ROUND_SUMMARY / ADHOC */
    @NotNull(message = "reportType 不能为空")
    private String reportType;

    @Min(value = 1, message = "topN 至少为 1")
    private Integer topN;

    private Boolean includeLineage;

    private Boolean includeExperimentAccuracy;
}
