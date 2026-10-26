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
 * generation_run · 分子生成任务。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("generation_run")
public class GenerationRun {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 生成任务编号 G+yyMMddHHmmss+随机 */
    private String runNo;

    /** bigint，非空 */
    private Long projectId;

    /** bigint，非空 */
    private Long roundId;

    /** 统一任务（design_job.id） */
    private Long jobId;

    /** bigint，非空 */
    private Long userId;

    /** 使用的约束集（仅存 ID；规则内容另存快照） */
    private Long constraintSetId;

    /** 约束的人可读快照，保证历史可解释（红线 R2） */
    private String constraintSnapshot;

    /** 策略名列表，逗号分隔，如 "R_GROUP,FRAGMENT_RECOMBINE" */
    private String strategies;

    /** 各策略参数与配额 */
    private String strategyParams;

    /** 种子来源，如 {"type":"POCKET_LIGAND","pocketId":12} */
    private String seedJson;

    /** 口袋引导时使用的口袋（16_binding_pocket） */
    private Long pocketId;

    /** 目标产出数 */
    private Integer targetCount;

    /** 原始生成数 */
    private Integer generatedCount;

    /** 去重后数 */
    private Integer dedupedCount;

    /** 被 hard 规则淘汰数 */
    private Integer rejectedCount;

    /** 最终落库候选数 */
    private Integer finalCount;

    /** PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED */
    private String status;

    /** decimal(5,2)，非空 */
    private BigDecimal progress;

    /** 失败/部分失败的逐策略原因 */
    private String errorMsg;

    /** 只读的需求快照（S0 唯一输入） */
    private Long baselineSnapshotId;

    /** S3 完整 generation_plan（JSON） */
    private String planJson;

    /** 计划哈希（回放凭证） */
    private String planHash;

    /** 主策略失败时的降级策略 */
    private String fallbackStrategy;

    /** 采样配额（由 S2 空间规模决定，不人工拍脑袋） */
    private Integer nSamples;

    /** 分片大小 */
    private Integer batchSize;

    /** 全局种子 */
    private Long seed;

    /** 预估 GPU 小时 */
    private BigDecimal estimatedGpuHours;

    /** S3 选策略的理由 */
    private String rationale;

    /** S3 计划置信度 */
    private BigDecimal planConfidence;

    /** S8 回放包 */
    private Long outputSnapshotId;

    /** S7 有效簇数（&lt;3 严重告警） */
    private Integer validClusterCount;

    /** 闸门阈值（来自 baseline 块7） */
    private Integer minCandidates;

    /** 幂等键：同键重复提交返回同一批次 */
    private String idempotencyKey;

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
