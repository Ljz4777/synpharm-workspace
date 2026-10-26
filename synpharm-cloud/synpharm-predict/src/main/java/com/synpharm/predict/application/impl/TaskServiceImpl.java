package com.synpharm.predict.application.impl;

import com.synpharm.predict.api.dto.PredictResultResponse;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.predict.domain.model.PredictResult;
import com.synpharm.predict.domain.model.PredictTask;
import com.synpharm.predict.infrastructure.persistence.PredictResultMapper;
import com.synpharm.predict.infrastructure.persistence.PredictTaskMapper;
import com.synpharm.predict.application.TaskService;
import com.synpharm.predict.support.PredictUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务服务实现类
 * 
 * <p>实现预测任务的管理操作，包括任务创建、查询、状态更新和预测执行。
 * 
 * @author SynPharm Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final PredictTaskMapper taskMapper;
    private final PredictResultMapper resultMapper;
    private final PredictUtils predictUtils;

    @Override
    @Transactional
    public PredictTask createTask(Long userId, String predictType, String inputType, String inputValue, String fileUrl) {
        PredictTask task = new PredictTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setPredictType(predictType);
        task.setInputType(inputType);
        task.setInputValue(inputValue);
        task.setFileUrl(fileUrl);
        task.setStatus("pending");
        task.setProgress(0);
        
        taskMapper.insert(task);
        log.info("创建任务成功: taskNo={}", task.getTaskNo());
        return task;
    }

    @Override
    public PredictTask getTaskById(Long taskId) {
        PredictTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    @Override
    public PredictTask getTaskByNo(String taskNo) {
        return taskMapper.selectByTaskNo(taskNo);
    }

    @Override
    public List<PredictTask> getTasksByUserId(Long userId) {
        return taskMapper.selectByUserId(userId);
    }

    @Override
    @Transactional
    public void updateTaskStatus(Long taskId, String status) {
        PredictTask task = getTaskById(taskId);
        task.setStatus(status);
        taskMapper.updateById(task);
        log.info("更新任务状态: taskId={}, status={}", taskId, status);
    }

    @Override
    @Transactional
    public void updateTaskProgress(Long taskId, Integer progress) {
        PredictTask task = getTaskById(taskId);
        task.setProgress(progress);
        taskMapper.updateById(task);
    }

    @Override
    @Transactional
    public void cancelTask(Long taskId) {
        PredictTask task = getTaskById(taskId);
        if ("running".equals(task.getStatus())) {
            task.setStatus("cancelled");
            taskMapper.updateById(task);
            log.info("取消任务: taskId={}", taskId);
        }
    }

    @Override
    @Transactional
    public void deleteTask(Long taskId) {
        PredictTask task = getTaskById(taskId);
        taskMapper.deleteById(taskId);
        log.info("删除任务: taskId={}", taskId);
    }

    @Override
    @Transactional
    public List<PredictResultResponse> executeTask(Long taskId) {
        PredictTask task = getTaskById(taskId);
        
        if ("running".equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_RUNNING);
        }
        
        if ("completed".equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_COMPLETED);
        }
        
        updateTaskStatus(taskId, "running");
        
        try {
            List<PredictResultResponse> results = predictUtils.predict(task);
            
            for (PredictResultResponse result : results) {
                PredictResult entity = new PredictResult();
                entity.setTaskId(taskId);
                entity.setTargetId(result.getTargetId());
                entity.setTargetName(result.getTargetName());
                entity.setBindingAffinity(result.getBindingAffinity());
                entity.setConfidenceScore(result.getConfidenceScore());
                entity.setConfidenceLevel(result.getConfidenceLevel());
                resultMapper.insert(entity);
            }
            
            updateTaskStatus(taskId, "completed");
            updateTaskProgress(taskId, 100);
            
            return results;
        } catch (Exception e) {
            log.error("预测任务执行失败: taskId={}", taskId, e);
            updateTaskStatus(taskId, "failed");
            throw new BizException(ErrorCode.PREDICT_ERROR, e.getMessage());
        }
    }

    /**
     * 生成任务编号
     * 
     * @return 任务编号
     */
    private String generateTaskNo() {
        return "TASK" + System.currentTimeMillis();
    }
}