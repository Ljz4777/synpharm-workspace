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
 * target · 靶点库。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("target")
public class Target {

    /** 靶点ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** UniProt 登录号，如 P00533 */
    private String uniprotId;

    /** 英文名，如 EGFR */
    private String name;

    /** 中文名 */
    private String chineseName;

    /** 基因名 */
    private String geneName;

    /** 物种 */
    private String organism;

    /** 靶点类型（前端筛选项） */
    private String targetType;

    /** 蛋白家族 */
    private String family;

    /** 主要通路 */
    private String pathway;

    /** 疾病领域（前端筛选项） */
    private String diseaseArea;

    /** 相关疾病 */
    private String relatedDiseases;

    /** 已知药物/干预方式 */
    private String knownDrugs;

    /** 蛋白序列（DTI 预测必需；缺失则 TARGET_SEQUENCE_REQUIRED） */
    private String sequence;

    /** 序列长度 */
    private Integer sequenceLength;

    /** 已知 PDB ID，逗号分隔 */
    private String pdbIds;

    /** supported/beta/planned（对齐前端 Target.status） */
    private String status;

    /** 数据来源 */
    private String source;

    /** 功能描述 */
    private String description;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
