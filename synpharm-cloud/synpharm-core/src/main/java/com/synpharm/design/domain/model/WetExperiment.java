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
 * wet_experiment · 湿实验反馈。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("wet_experiment")
public class WetExperiment {

    /** 实验记录ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 实验编号 W+yyMMddHHmmss+随机 */
    private String experimentNo;

    /** 所属项目 */
    private Long projectId;

    /** 所属轮次 */
    private Long roundId;

    /** 被验证的候选（对照组/外部样品可为空） */
    private Long candidateId;

    /** 由哪份报告驱动（送样来源） */
    private Long reportId;

    /** IC50/EC50/KD_SPR/KD_BLI/ADCC/CDC/THERMAL_SHIFT/EXPRESSION_YIELD/SERUM_STABILITY/IMMUNOGENICITY_TCELL */
    private String assayType;

    /** 实验方案/条件摘要 */
    private String assayProtocol;

    /** WILD_TYPE/MUTANT/ANTI_TARGET/OFF_TARGET，对齐 project_target.role */
    private String targetRole;

    /** PLANNED/RUNNING/SUCCEEDED/FAILED/INCONCLUSIVE */
    private String resultStatus;

    /** 数值结果（按 unit 解释） */
    private BigDecimal resultNum;

    /** 非数值结果/备注 */
    private String resultText;

    /** 单位：nM / kcal/mol / °C / mg/L */
    private String unit;

    /** 不等式算子：GT/GTE/LT/LTE/EQ（如 >10000 nM） */
    private String operator;

    /** 是否命中（按阈值判定），用于回算命中率 */
    private Integer isHit;

    /** 实验前系统给出的预测值（配对比较用） */
    private BigDecimal predictedValue;

    /** 预测值对应指标码（metric_definition.metric_code） */
    private String predictedMetric;

    /** 预测-实测偏差（result_num - predicted_value） */
    private BigDecimal errorDelta;

    /** 是否已回灌用于模型校准 */
    private Integer feedbackToModel;

    /** 回灌批次号 B+yyMMddHHmmss */
    private String feedbackBatch;

    /** 回灌时间 */
    private LocalDateTime feedbackAt;

    /** 产出的校准工件 ID（版本化，可回滚） */
    private String calibrationId;

    /** 原始数据/仪器导出（file_asset.id） */
    private Long dataAssetId;

    /** 实验单位 */
    private String labName;

    /** 录入人 */
    private Long operatorId;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
