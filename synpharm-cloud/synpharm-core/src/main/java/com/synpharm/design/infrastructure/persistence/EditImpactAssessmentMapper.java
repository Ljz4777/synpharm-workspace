package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.EditImpactAssessment;
import org.apache.ibatis.annotations.Mapper;

/**
 * edit_impact_assessment 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface EditImpactAssessmentMapper extends BaseMapper<EditImpactAssessment> {
}