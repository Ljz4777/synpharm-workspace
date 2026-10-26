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
 * binding_pocket · 结合口袋。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("binding_pocket")
public class BindingPocket {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long structureId;

    /** 结构内的口袋序号（按 score 降序） */
    private Integer pocketNo;

    /** P2RANK/FPOCKET/LIGAND_BASED/MANUAL */
    private String method;

    /** 方法原始打分 */
    private BigDecimal score;

    /** 可成药性 0-1 */
    private BigDecimal druggability;

    /** 空腔体积 Å³ */
    private BigDecimal volume;

    /** 中心 X */
    private BigDecimal centerX;

    /** 中心 Y */
    private BigDecimal centerY;

    /** 中心 Z */
    private BigDecimal centerZ;

    /** [{"chain":"A","resno":745,"resname":"LYS"}] */
    private String residuesJson;

    /** 1=当前使用的设计口袋 */
    private Integer isSelected;

    /** 人工备注（如"已知 ATP 位点"） */
    private String note;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

    /** 仅选定口袋非空，用于 uk_structure_selected */
    // 数据库生成列：只读，禁止写入
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long selectedStructureId;

}
