package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.GenerationOutputSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * GenerationOutputSnapshot 数据访问接口（生成完整回放包（S8））。
 *
 * @author SynPharm Team
 */
@Mapper
public interface GenerationOutputSnapshotMapper extends BaseMapper<GenerationOutputSnapshot> {
}
