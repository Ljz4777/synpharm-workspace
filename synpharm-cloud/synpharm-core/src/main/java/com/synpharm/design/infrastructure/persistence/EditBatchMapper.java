package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.EditBatch;
import org.apache.ibatis.annotations.Mapper;

/**
 * edit_batch 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface EditBatchMapper extends BaseMapper<EditBatch> {
}