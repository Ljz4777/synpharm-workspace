package com.synpharm.design.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.design.domain.model.BaselineSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * baseline_snapshot 数据访问接口。
 *
 * @author SynPharm Team
 */
@Mapper
public interface BaselineSnapshotMapper extends BaseMapper<BaselineSnapshot> {
}