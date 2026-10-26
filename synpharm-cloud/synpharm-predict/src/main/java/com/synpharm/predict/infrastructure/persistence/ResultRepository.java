package com.synpharm.predict.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.predict.domain.model.PredictResult;
import com.synpharm.predict.infrastructure.persistence.PredictResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 结果仓储
 */
@Repository
@RequiredArgsConstructor
public class ResultRepository {

    private final PredictResultMapper predictResultMapper;

    public PredictResult findById(Long id) {
        return predictResultMapper.selectById(id);
    }

    public List<PredictResult> findByTaskId(Long taskId) {
        LambdaQueryWrapper<PredictResult> query = Wrappers.lambdaQuery();
        query.eq(PredictResult::getTaskId, taskId);
        query.eq(PredictResult::getDeleted, 0);
        return predictResultMapper.selectList(query);
    }

    public void save(PredictResult result) {
        if (result.getId() == null) {
            predictResultMapper.insert(result);
        } else {
            predictResultMapper.updateById(result);
        }
    }

    public void deleteById(Long id) {
        predictResultMapper.deleteById(id);
    }

    public void deleteByTaskId(Long taskId) {
        LambdaQueryWrapper<PredictResult> query = Wrappers.lambdaQuery();
        query.eq(PredictResult::getTaskId, taskId);
        predictResultMapper.delete(query);
    }
}