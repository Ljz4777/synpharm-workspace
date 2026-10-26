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
 * design_library_item · 设计知识库（多级）。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_library_item")
public class DesignLibraryItem {

    /** 知识项ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** DISEASE/TARGET/MODALITY/CONSTRAINT_TEMPLATE/OBJECTIVE/ASSAY */
    private String category;

    /** 分类内唯一编码，如 NSCLC / EGFR / ANTIBODY */
    private String code;

    /** 显示名（中文） */
    private String name;

    /** 英文名 */
    private String nameEn;

    /** 上级项（自引用，支持 疾病→亚型→靶点 多级） */
    private Long parentId;

    /** 层级，从 1 开始 */
    private Integer levelNo;

    /** 物化路径 /DISEASE/ONCOLOGY/NSCLC，前缀匹配取子树 */
    private String path;

    /** 分类扩展属性，结构随 category 而定 */
    private String metaJson;

    /** 同级排序 */
    private Integer sortNo;

    /** 1=可选；0=停用（历史数据仍可读） */
    private Integer enabled;

    /** 1=内置数据，版本升级时可覆盖 */
    private Integer builtin;

    /** 备注 */
    private String remark;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
