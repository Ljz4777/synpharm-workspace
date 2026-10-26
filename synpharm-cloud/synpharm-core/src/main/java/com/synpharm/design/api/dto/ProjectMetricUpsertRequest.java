package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 项目指标的新增/修改请求（{@code POST|PUT /projects/{id}/metrics}）。
 *
 * <p>指标是「希望达到」的目标，不是「必须满足」的约束 —— 后者在约束集里。
 * 两者的容差语义也不同：约束的容差决定是否淘汰，指标的容差决定是否扣分。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectMetricUpsertRequest {

    /** 指标码，须存在于 metric_definition（如 QED / KD / TM） */
    private String metricCode;

    /** 评估维度；留空则取指标定义里的维度 */
    private String dimension;

    /** 加权权重，默认 1.0 */
    private BigDecimal weight;

    /** 期望值（软目标，不影响达标判定） */
    private BigDecimal targetValue;

    /** 达标阈值；与 operator 共同构成硬边界 */
    private BigDecimal threshold;

    /** GT / GTE / LT / LTE / EQ / BETWEEN；默认 GTE */
    private String operator;

    /** 容差带宽；配合 boundaryPolicy=WITHIN_TOLERANCE 生效 */
    private BigDecimal tolerance;

    /** STRICT / WITHIN_TOLERANCE / ESCALATE_TO_SOFT；默认 STRICT */
    private String boundaryPolicy;

    /** 排序优先级，越小越靠前；默认 100 */
    private Integer priority;

    /** 是否启用；默认 true */
    private Boolean enabled;
}
