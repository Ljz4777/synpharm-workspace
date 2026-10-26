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
 * design_report · 设计报告与审批。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_report")
public class DesignReport {

    /** 报告ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 报告编号 R+yyMMddHHmmss+随机 */
    private String reportNo;

    /** 所属项目 */
    private Long projectId;

    /** 所属轮次 */
    private Long roundId;

    /** 数据来源评估任务（保证可复现） */
    private Long evaluationRunId;

    /** 创建人 */
    private Long userId;

    /** 报告标题 */
    private String title;

    /** TOP_N/ROUND_SUMMARY/ADHOC */
    private String reportType;

    /** Top-N 的 N */
    private Integer topN;

    /** 结构化正文：候选+指标+血缘+风险+实测准确率 */
    private String bodyJson;

    /** 渲染后的 Markdown 正文 */
    private String bodyMd;

    /** 生成时的能力边界声明（哪些引擎不可用、realness 分布） */
    private String capabilityNote;

    /** PENDING/APPROVED/REVISE/REJECTED */
    private String decision;

    /** 审批人（sys_user.id） */
    private Long decisionBy;

    /** 审批时间 */
    private LocalDateTime decisionAt;

    /** 审批意见（REVISE/REJECTED 时必填） */
    private String decisionNote;

    /** 修订来源报告（REVISE 后重出） */
    private Long revisionOf;

    /** 版本号，修订 +1 */
    private Integer versionNo;

    /** PENDING/GENERATING/SUCCEEDED/PARTIAL/FAILED */
    private String status;

    /** 生成该报告的统一任务 */
    private Long jobId;

    /** 若由 ReporterAgent 生成，关联智能体会话 */
    private Long agentSessionId;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
