package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.GenerationCache;
import org.apache.ibatis.annotations.Mapper;

/**
 * GenerationCache 数据访问接口（生成结果缓存（三元组））。
 *
 * @author SynPharm Team
 */
@Mapper
public interface GenerationCacheMapper extends BaseMapper<GenerationCache> {
}
