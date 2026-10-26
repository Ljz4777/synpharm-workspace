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
 * design_job · 统一异步任务。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_job")
public class DesignJob {

    /** 任务ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外任务编号 J+yyMMddHHmmss+随机，即需求中的 job_id */
    private String jobNo;

    /** GENERATION/EVALUATION/POCKET_DETECT/STRUCTURE_FETCH/REPORT/FEEDBACK_CALIBRATE/EXPORT */
    private String jobType;

    /** 所属项目（用户级任务可为空） */
    private Long projectId;

    /** 所属轮次 */
    private Long roundId;

    /** 针对单候选的任务（如单分子全量评估） */
    private Long candidateId;

    /** 发起人 */
    private Long userId;

    /** 执行工具（design_tool.id），用于能力与真实性追溯 */
    private Long toolId;

    /** 业务主表类型：GENERATION_RUN/EVALUATION_RUN/REPORT */
    private String bizType;

    /** 业务主表 ID（与 biz_type 组合定位） */
    private Long bizId;

    /** 入参快照，保证任务可重放（红线 R5 可追溯） */
    private String paramsJson;

    /** PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED */
    private String status;

    /** 进度百分比 0-100 */
    private BigDecimal progress;

    /** 总条目数 */
    private Integer totalCount;

    /** 已完成条目数 */
    private Integer doneCount;

    /** 轻量结果摘要（大结果落业务表或对象存储） */
    private String resultJson;

    /** 失败错误码（见 02-接口层详细设计.md §2） */
    private String errorCode;

    /** 失败详情（PARTIAL 时记录逐项原因） */
    private String errorMsg;

    /** 幂等键；同键未终态时拒绝重复提交 */
    private String idempotentKey;

    /** 投递的 RabbitMQ 队列名 */
    private String queueName;

    /** 重试次数 */
    private Integer retryCount;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
