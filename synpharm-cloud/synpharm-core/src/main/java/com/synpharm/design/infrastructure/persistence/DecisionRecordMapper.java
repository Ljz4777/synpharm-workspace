package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.DecisionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * DecisionRecord 数据访问接口。
 *
 * <p>简单查询走 BaseMapper 条件构造器；跨表投影再补 XML。
 *
 * @author SynPharm Team
 */
@Mapper
public interface DecisionRecordMapper extends BaseMapper<DecisionRecord> {
}