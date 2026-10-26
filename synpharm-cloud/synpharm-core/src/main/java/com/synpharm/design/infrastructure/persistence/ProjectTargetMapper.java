package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.ProjectTarget;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * project_target 数据访问接口。
 *
 * <p>简单查询走 BaseMapper 条件构造器；复杂投影/批量再补 XML。
 *
 * @author SynPharm Team
 */
@Mapper
public interface ProjectTargetMapper extends BaseMapper<ProjectTarget> {

    /**
     * 物理删除绑定行。
     *
     * <p>为什么不用 {@code deleteById}：{@code deleted} 是 {@code @TableLogic} 列，
     * 走逻辑删除会留下 {@code deleted=1} 的行，而唯一键
     * {@code uk_project_target(project_id, target_id)} 与生成列上的
     * {@code uk_primary_target} 都不含 {@code deleted} —— 结果就是「解绑后再绑同一个靶点」
     * 直接撞唯一键报 500。绑定本身是纯关联，历史归属由基线快照自己留存，不需要软删。
     */
    @Delete("DELETE FROM project_target WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);

    /**
     * 清掉同一 (project, target) 上的历史残留行。
     *
     * <p>这次修复之前解绑走的是逻辑删除，库里可能留下 {@code deleted=1} 的行；
     * 它们逃过 MyBatis-Plus 的 {@code deleted=0} 过滤，却实实在在占着唯一键。
     * 绑定前先清一遍，让老数据也能正常重绑。
     */
    @Delete("DELETE FROM project_target WHERE project_id = #{projectId} AND target_id = #{targetId}")
    int purgeBinding(@Param("projectId") Long projectId, @Param("targetId") Long targetId);
}
