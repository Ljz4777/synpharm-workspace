package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_run · 评估任务。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("evaluation_run")
public class EvaluationRun {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 评估任务编号 E+yyMMddHHmmss+随机 */
    private String runNo;

    /** bigint，非空 */
    private Long projectId;

    /** 属于哪一轮 */
    private Long roundId;

    /** 统一任务（design_job.id） */
    private Long jobId;

    /** bigint，非空 */
    private Long userId;

    /** ALL/SELECTED/INCREMENTAL */
    private String scope;

    /** scope=SELECTED 时的候选 id 列表 */
    private String candidateIds;

    /** 启用的指标码列表（逗号分隔） */
    private String enabledMetrics;

    /** 本次使用的权重快照（防止后续改权重导致历史不可复现） */
    private String metricWeights;

    /** int，非空 */
    private Integer totalCount;

    /** int，非空 */
    private Integer evaluatedCount;

    /** int，非空 */
    private Integer rejectedCount;

    /** int，非空 */
    private Integer qualifiedCount;

    /** int，非空 */
    private Integer topCount;

    /** PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED */
    private String status;

    /** decimal(5,2)，非空 */
    private BigDecimal progress;

    /** 指标分布统计（均值/中位/最大/最小） */
    private String metricStats;

    /** varchar(1000) */
    private String errorMsg;

    /** int */
    private Integer latencyMs;

    /** datetime */
    private LocalDateTime startedAt;

    /** datetime */
    private LocalDateTime completedAt;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
