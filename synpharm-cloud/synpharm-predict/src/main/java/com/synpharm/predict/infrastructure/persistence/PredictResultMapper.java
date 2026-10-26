package com.synpharm.predict.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.predict.domain.model.PredictResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预测结果数据访问接口
 * 
 * <p>继承MyBatisPlus的BaseMapper，提供预测结果数据的CRUD操作。
 * 
 * @author SynPharm Team
 * @version 1.0.0
 */
@Mapper
public interface PredictResultMapper extends BaseMapper<PredictResult> {
}