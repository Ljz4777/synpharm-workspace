package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * project_metric · 项目启用的评估指标与目标。
 *
 * <p>需求分析阶段「评估指标清单」的落地（06 文档 §3.3）。冻结时会被复制进
 * <code>baseline_snapshot.metrics_json</code>。
 *
 * <p><b>与 constraint_rule 的关系</b>：
 * <ul>
 *   <li><code>constraint_rule</code> 是<b>约束</b> —— 候选分子必须满足，不满足即淘汰/扣分；</li>
 *   <li><code>project_metric</code> 是<b>目标</b> —— 希望达到的水平，用于综合分与排序。</li>
 * </ul>
 * 两者的 <code>tolerance</code> / <code>boundary_policy</code> 语义相同，但<b>判定硬性达标时以
 * constraint_rule 为准</b>；project_metric 的边界只影响打分与软目标。
 *
 * @author SynPharm Team
 */
@Data
@TableName("project_metric")
public class ProjectMetric {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** metric_definition.metric_code */
    private String metricCode;

    /** 冗余自 metric_definition.dimension，便于按维度聚合 */
    private String dimension;

    /** 综合分权重 */
    private BigDecimal weight;

    /** 期望值（软目标） */
    private BigDecimal targetValue;

    /** 达标阈值 */
    private BigDecimal threshold;

    /** GT/GTE/LT/LTE/EQ */
    private String operator;

    /** 方法学误差带；NULL = 无容差 */
    private BigDecimal tolerance;

    /** STRICT / WITHIN_TOLERANCE / ESCALATE_TO_SOFT */
    private String boundaryPolicy;

    private Integer priority;

    private Integer enabled;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
