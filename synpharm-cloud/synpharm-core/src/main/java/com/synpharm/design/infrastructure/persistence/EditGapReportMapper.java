package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.EditGapReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * edit_gap_report 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface EditGapReportMapper extends BaseMapper<EditGapReport> {
}