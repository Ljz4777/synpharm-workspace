package com.synpharm.predict.application;

import com.synpharm.predict.api.dto.PredictResultResponse;

/**
 * 结果服务接口
 */
public interface ResultService {

    Object listResults(Long userId, Integer page, Integer pageSize);

    PredictResultResponse getResult(Long userId, Long id);

    void deleteResult(Long userId, Long id);
}