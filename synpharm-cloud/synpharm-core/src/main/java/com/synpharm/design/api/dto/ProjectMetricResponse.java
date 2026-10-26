package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 项目指标（{@code GET /projects/{id}/metrics}）。
 *
 * <p>{@code metricName} / {@code unit} / {@code better} 来自指标字典，
 * 让前端不必再查一次字典就能直接渲染一行。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectMetricResponse {

    private Long id;

    private Long projectId;

    private String metricCode;

    /** 中文名，来自 metric_definition；字典缺失时为 null */
    private String metricName;

    private String dimension;

    private String unit;

    /** HIGHER / LOWER，决定好坏方向 */
    private String better;

    private BigDecimal weight;

    private BigDecimal targetValue;

    private BigDecimal threshold;

    private String operator;

    private BigDecimal tolerance;

    private String boundaryPolicy;

    private Integer priority;

    private Boolean enabled;

    /** 人类可读判定式，如「≥ 0.5（容差 0.05，容差内需人工确认）」 */
    private String boundaryText;
}
