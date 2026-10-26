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
 * evaluation_result · 评估结果明细。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("evaluation_result")
public class EvaluationResult {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long runId;

    /** 冗余，便于直接按项目查询 */
    private Long projectId;

    /** bigint，非空 */
    private Long moleculeId;

    /** 冗余，便于按结构查询；生物大分子为 NULL */
    private String inchikey;

    /** varchar(40)，非空 */
    private String metricCode;

    /** 数值型指标值 */
    private BigDecimal valueNum;

    /** 文本/等级型指标值 */
    private String valueText;

    /** 布尔型（约束类）结果；NULL=不适用 */
    private Integer passed;

    /** 归一化后 0-1，用于综合得分 */
    private BigDecimal normalized;

    /** 等级：EXCELLENT/GOOD/FAIR/POOR */
    private String grade;

    /** LOW/MEDIUM/HIGH/CRITICAL */
    private String riskLevel;

    /** REAL/SEMI_REAL/HEURISTIC/SIMULATED，生成时从 design_tool 快照 */
    private String realness;

    /** 产出该指标的注册工具（design_tool.id） */
    private Long sourceToolId;

    /** 明细，如命中警报的 SMARTS、相互作用残基 */
    private String detailJson;

    /** 该指标是否算成功（模型不可用/静默兜底时为 0） */
    private Integer success;

    /** varchar(500) */
    private String errorMsg;

    /** int */
    private Integer latencyMs;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** 函数索引载体：风险明细首条 SMARTS */
    // 数据库生成列：只读，禁止写入
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String riskFirstSmarts;

}
