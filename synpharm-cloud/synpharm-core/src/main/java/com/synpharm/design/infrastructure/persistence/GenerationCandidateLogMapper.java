package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.GenerationCandidateLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * GenerationCandidateLog 数据访问接口（逐分子筛选日志（原因必记））。
 *
 * @author SynPharm Team
 */
@Mapper
public interface GenerationCandidateLogMapper extends BaseMapper<GenerationCandidateLog> {
}
