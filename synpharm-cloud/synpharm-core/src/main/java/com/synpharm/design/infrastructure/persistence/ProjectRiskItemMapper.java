package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.ProjectRiskItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * project_risk_item 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface ProjectRiskItemMapper extends BaseMapper<ProjectRiskItem> {
}