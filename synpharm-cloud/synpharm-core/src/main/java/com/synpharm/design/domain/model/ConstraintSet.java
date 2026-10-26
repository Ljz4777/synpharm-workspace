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
 * constraint_set · 设计约束集。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("constraint_set")
public class ConstraintSet {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 约束集编号 CS+yyMMddHHmmss+随机 */
    private String setNo;

    /** 归属用户（可跨项目复用） */
    private Long userId;

    /** 如"EGFR 抑制剂类药性+PAINS" */
    private String name;

    /** 说明 */
    private String description;

    /** 顶层逻辑，固定 AND */
    private String logic;

    /** DRAFT/ACTIVE/ARCHIVED */
    private String status;

    /** 版本号，激活后修改须 +1 */
    private Integer version;

    /** 版本来源（clone 自哪个约束集） */
    private Long parentId;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
