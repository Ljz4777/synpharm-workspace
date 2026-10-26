package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.WorkspaceState;
import org.apache.ibatis.annotations.Mapper;

/**
 * workspace_state 数据访问接口。
 *
 * <p>简单查询走 BaseMapper 条件构造器；复杂投影/批量再补 XML。
 *
 * @author SynPharm Team
 */
@Mapper
public interface WorkspaceStateMapper extends BaseMapper<WorkspaceState> {
}
