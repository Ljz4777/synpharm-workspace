package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * project_risk_item · 项目风险关注点。
 *
 * <p>需求分析阶段「风险关注点清单」的落地（06 文档 §3.3）。
 *
 * <p><b>与「评估指标」的区别</b>：指标是「要达标的数」，风险点是「必须排查的事」。
 * 有些风险能被指标覆盖（AGGREGATION 对应聚集风险指标），有些不能 ——
 * 后者（如 IP_INFRINGEMENT、OFF_TARGET）只能靠人工或湿实验确认，
 * 这就是 <code>must_verify</code> 单独存在的原因。
 *
 * @author SynPharm Team
 */
@Data
@TableName("project_risk_item")
public class ProjectRiskItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** IMMUNOGENICITY / POLYSPECIFICITY / PTM_SITE / AGGREGATION / ... */
    private String riskCode;

    /** 关联评估维度（可空） */
    private String dimension;

    private String description;

    /** LOW / MEDIUM / HIGH / CRITICAL */
    private String severity;

    /** 是否必须湿实验验证 */
    private Integer mustVerify;

    /** USER / AUTO（按 modality 自动带出） */
    private String source;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
