package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.GenerationSlice;
import org.apache.ibatis.annotations.Mapper;

/**
 * GenerationSlice 数据访问接口（生成分片（S4））。
 *
 * @author SynPharm Team
 */
@Mapper
public interface GenerationSliceMapper extends BaseMapper<GenerationSlice> {
}
