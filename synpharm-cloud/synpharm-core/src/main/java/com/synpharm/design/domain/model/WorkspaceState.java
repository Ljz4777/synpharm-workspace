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
 * workspace_state · 工作台视图状态。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("workspace_state")
public class WorkspaceState {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户（工作台是"我的"视图） */
    private Long userId;

    /** NULL = 用户级最近项目 */
    private Long projectId;

    /** 上次打开的轮次 */
    private Long lastRoundId;

    /** 上次选中的候选 */
    private Long lastMolId;

    /** CANVAS/CANDIDATES/DIFF/POCKET */
    private String activeTab;

    /** 已打开的分子页签列表（IDE 多标签） */
    private String openMolIds;

    /** 面板展开/折叠/宽度、表格排序与筛选 */
    private String panelLayoutJson;

    /** datetime，非空 */
    private LocalDateTime lastVisitedAt;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
