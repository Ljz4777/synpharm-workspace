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
 * metric_definition · 评估指标定义。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("metric_definition")
public class MetricDefinition {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 指标码，如 MOL_WT / QED / SA_SCORE / DTI_CONFIDENCE */
    private String metricCode;

    /** 8 大维度：AFFINITY/STABILITY/EXPRESSION/ADMET/SYNTHESIZABILITY/DEVELOPABILITY/SELECTIVITY/IMMUNOGENICITY */
    private String dimension;

    /** 中文名，如"分子量" */
    private String metricName;

    /** CONSTRAINT/PROPERTY/DRUGLIKENESS/SAFETY/SYNTHESIS/DIVERSITY/ACTIVITY/SELECTIVITY/DOCKING/ADMET */
    private String category;

    /** NUMERIC/BOOLEAN/GRADE/JSON */
    private String valueType;

    /** 单位，如 Da / Å / kcal/mol */
    private String unit;

    /** HIGHER 越大越好 / LOWER 越小越好 / NONE */
    private String better;

    /** 归一化下限 */
    private BigDecimal minValue;

    /** 归一化上限 */
    private BigDecimal maxValue;

    /** 综合得分权重（0=仅展示不参与打分） */
    private BigDecimal weight;

    /** tinyint，非空 */
    private Integer enabled;

    /** int，非空 */
    private Integer sortNo;

    /** varchar(300) */
    private String remark;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

    /** 适用分子形式：["SMALL_MOLECULE","ANTIBODY","PEPTIDE"] */
    private String appliesTo;

}
