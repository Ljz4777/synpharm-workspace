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
 * project_target · 项目-靶点绑定。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("project_target")
public class ProjectTarget {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    private Long projectId;

    /** 靶点ID（见 14_target） */
    private Long targetId;

    /** 1=主靶点（生成以它为准） */
    private Integer isPrimary;

    /** WILD_TYPE/MUTANT/ANTI_TARGET/OFF_TARGET */
    private String role;

    /** int，非空 */
    private Integer sortNo;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

    /** 仅主靶点非空，用于 uk_primary_target 保证一项目一主靶点 */
    // 数据库生成列：只读，禁止写入
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long primaryProjectId;

}
