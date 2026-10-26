package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.DesignLibraryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * design_library_item 数据访问接口。
 *
 * <p>简单查询走 BaseMapper 条件构造器；复杂投影/批量再补 XML。
 *
 * @author SynPharm Team
 */
@Mapper
public interface DesignLibraryItemMapper extends BaseMapper<DesignLibraryItem> {

    /**
     * 节点换父后，整体平移其子树（物化路径 + 层级）。
     *
     * <p>用物化路径前缀匹配一次更新整棵子树，而不是逐层递归查询 —— 树最深 3~4 层，
     * 但递归实现要为每层发一条 SELECT，且并发下容易读到半成品状态。
     *
     * <p>{@code oldPath} 必须由调用方用 {@code LikePatterns.escape} 转义：{@code code}
     * 允许出现 {@code _}（种子数据里就有 {@code KD_SPR}），未转义时 {@code _} 会当通配符
     * 匹配到兄弟节点，把别人的子树也一起改掉。
     *
     * <p>{@code oldPath} 与 {@code oldPathLike} 必须分开传：前者参与
     * {@code LENGTH}/{@code SUBSTRING} 的**算术**，必须是原始路径；后者只用于
     * {@code LIKE}，必须是转义后的模式。合成一个参数会出真实的数据损坏 —— 路径里每有
     * 一个 {@code _} 转义后就多一个反斜杠，{@code LENGTH} 随之偏大，{@code SUBSTRING}
     * 会多切掉几个字符，把子节点的路径前缀生生截断。
     *
     * @param category    分类（限定在同一棵树内）
     * @param oldPath     节点原来的物化路径（原始值，用于长度计算）
     * @param oldPathLike 同上路径的 LIKE 转义版本（用于前缀匹配）
     * @param newPath     节点新的物化路径
     * @param levelDelta  层级增量（子节点 {@code level_no} 同步平移）
     * @return 受影响行数
     */
    @Update("UPDATE design_library_item "
            + "SET path = CONCAT(#{newPath}, SUBSTRING(path, LENGTH(#{oldPath}) + 1)), "
            + "    level_no = level_no + #{levelDelta}, "
            + "    updated_at = NOW() "
            + "WHERE category = #{category} AND deleted = 0 "
            + "  AND path LIKE CONCAT(#{oldPathLike}, '/%')")
    int updateSubtreePath(@Param("category") String category,
                          @Param("oldPath") String oldPath,
                          @Param("oldPathLike") String oldPathLike,
                          @Param("newPath") String newPath,
                          @Param("levelDelta") int levelDelta);
}
