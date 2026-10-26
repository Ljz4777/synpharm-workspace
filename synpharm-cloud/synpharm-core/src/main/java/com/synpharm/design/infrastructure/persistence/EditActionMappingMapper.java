package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.EditActionMapping;
import org.apache.ibatis.annotations.Mapper;

/**
 * edit_action_mapping 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface EditActionMappingMapper extends BaseMapper<EditActionMapping> {
}