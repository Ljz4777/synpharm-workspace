package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.ProjectMetric;
import org.apache.ibatis.annotations.Mapper;

/**
 * project_metric 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface ProjectMetricMapper extends BaseMapper<ProjectMetric> {
}