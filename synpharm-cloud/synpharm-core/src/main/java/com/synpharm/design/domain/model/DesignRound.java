package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * design_round · 设计迭代轮次。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_round")
public class DesignRound {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long projectId;

    /** 第几轮，从 1 开始 */
    private Integer roundNo;

    /** 本轮策略说明（人工或 PlannerAgent 产出） */
    private String strategyNote;

    /** PLANNED/GENERATING/EDITING/EVALUATING/REVIEWING/CLOSED */
    private String status;

    /** 本轮结论 */
    private String conclusionText;

    /** 本轮指标快照，如 {"top1_binding":8.2,"pass_rate":0.35} */
    private String metricsJson;

    /** datetime，非空 */
    private LocalDateTime openedAt;

    /** datetime */
    private LocalDateTime closedAt;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
