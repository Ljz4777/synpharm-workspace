package com.synpharm.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.platform.domain.model.DesignTool;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具注册表 Mapper。
 *
 * @author SynPharm Team
 */
@Mapper
public interface DesignToolMapper extends BaseMapper<DesignTool> {
}
