package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 项目风险关注点（{@code GET /projects/{id}/risks}）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectRiskResponse {

    private Long id;

    private Long projectId;

    private String riskCode;

    private String dimension;

    private String description;

    /** LOW / MEDIUM / HIGH / CRITICAL */
    private String severity;

    private Boolean mustVerify;

    private String source;
}
