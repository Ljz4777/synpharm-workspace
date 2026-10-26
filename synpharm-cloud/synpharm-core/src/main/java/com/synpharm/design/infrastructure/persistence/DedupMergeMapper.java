package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.DedupMerge;
import org.apache.ibatis.annotations.Mapper;

/**
 * DedupMerge 数据访问接口（去重合并关系（S5））。
 *
 * @author SynPharm Team
 */
@Mapper
public interface DedupMergeMapper extends BaseMapper<DedupMerge> {
}
