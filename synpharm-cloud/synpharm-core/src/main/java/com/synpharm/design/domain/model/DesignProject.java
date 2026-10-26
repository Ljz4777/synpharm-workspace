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
 * design_project · 药物设计项目。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_project")
public class DesignProject {

    /** 项目ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目编号 P+yyMMddHHmmss+随机 */
    private String projectNo;

    /** 归属用户 */
    private Long userId;

    /** 项目名称 */
    private String name;

    /** 设计目标（自然语言） */
    private String goalText;

    /** 结构化目标指标，如 {"selectivity_index":{">":3}} */
    private String goalMetricJson;

    /** 疾病（design_library_item.category=DISEASE） */
    private Long diseaseId;

    /** 分子形式（design_library_item.category=MODALITY） */
    private Long modalityId;

    /** 作用机制（design_library_item.category=MOA）：INHIBITOR/AGONIST/ANTAGONIST/DEGRADER/BRIDGING */
    private String moaCode;

    /** 当前生效的约束集（见 17_constraint_set） */
    private Long activeConstraintSetId;

    /**
     * 当前生效的基线快照（baseline_snapshot.id）。
     *
     * <p>冻结后所有生成任务<b>只读快照</b>，不读本表的当前状态 —— 用户中途改约束不影响已提交任务。
     */
    private Long currentBaselineId;

    /**
     * 项目版本号；约束变更即 +1（编辑设计铁律三）。
     *
     * <p>变更后旧 <code>baseline_snapshot</code> 置 SUPERSEDED，所有候选置 needs_re_eval。
     */
    private Integer projectVersion;

    /** 当前轮次（工作台恢复用，避免每次 MAX() 推导） */
    private Long currentRoundId;

    /** GPU 小时配额；NULL = 不限 */
    private BigDecimal quotaGpuHours;

    /** 已消耗 GPU 小时 */
    private BigDecimal quotaGpuUsed;

    /** 湿实验名额；NULL = 不限 */
    private Integer quotaWetLab;

    /** 已用湿实验名额 */
    private Integer quotaWetUsed;

    /** 最少候选数闸门：生成 S7 与编辑后置闸门共用，低于此值告警或阻断 */
    private Integer minCandidates;

    /** 决策名额档位：RATIO=按候选数比例 / ABSOLUTE=固定个数（07 文档 B2） */
    private String decisionCapacityMode;

    /**
     * 决策名额参数。
     *
     * <p>RATIO 时是 0~1 的比例（0.2 = 20%），ABSOLUTE 时是正整数个。
     * 无论哪种模式，最终名额仍受 {@code quotaWetLab} 余额上限约束 ——
     * 档位只决定"想推多少"，预算决定"能推多少"。
     */
    private BigDecimal decisionCapacityValue;

    /** DRAFT/READY/ACTIVE/COMPLETED/CANCELLED/ARCHIVED */
    private String status;

    /** 结项小结 */
    private String summary;

    /** 首次进入 ACTIVE 的时间 */
    private LocalDateTime startedAt;

    /** 结项时间 */
    private LocalDateTime closedAt;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
