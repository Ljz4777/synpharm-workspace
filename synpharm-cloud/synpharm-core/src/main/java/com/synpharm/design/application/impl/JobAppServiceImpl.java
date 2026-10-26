package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.JobResponse;
import com.synpharm.design.application.JobAppService;
import com.synpharm.design.domain.model.DesignJob;
import com.synpharm.design.domain.model.JobStatus;
import com.synpharm.design.domain.model.JobType;
import com.synpharm.design.infrastructure.persistence.DesignJobMapper;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务实现。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobAppServiceImpl implements JobAppService {

    private final DesignJobMapper jobMapper;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<JobResponse> list(Long projectId, String jobType, String status, int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();
        if (StringUtils.hasText(jobType) && !JobType.isValid(jobType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 jobType: " + jobType,
                    Map.of("allowedTypes", JobType.names()));
        }
        if (StringUtils.hasText(status) && !JobStatus.isValid(status)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 status: " + status,
                    Map.of("allowedStatus", JobStatus.names()));
        }
        var result = jobMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize),
                Wrappers.lambdaQuery(DesignJob.class)
                        .eq(DesignJob::getUserId, userId)
                        .eq(projectId != null, DesignJob::getProjectId, projectId)
                        .eq(StringUtils.hasText(jobType), DesignJob::getJobType, jobType)
                        .eq(StringUtils.hasText(status), DesignJob::getStatus, status)
                        .orderByDesc(DesignJob::getId));
        return PageResult.of(result, this::toResponse);
    }

    @Override
    public JobResponse get(String jobIdOrNo) {
        return toResponse(loadOwned(jobIdOrNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String jobIdOrNo) {
        DesignJob job = loadOwned(jobIdOrNo);
        JobStatus current = JobStatus.valueOf(job.getStatus());
        if (!current.isCancellable()) {
            throw new BizException(ErrorCode.JOB_NOT_CANCELLABLE, null,
                    Map.of("jobId", job.getId(), "jobNo", job.getJobNo(), "status", job.getStatus()));
        }
        job.setStatus(JobStatus.CANCELLED.name());
        job.setCompletedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        log.info("任务 {} 已取消（原状态 {}）", job.getJobNo(), current);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreatedJob createOrReuse(JobType jobType, Long projectId, Long roundId, Long candidateId,
                                    String bizType, Long bizId, Map<String, Object> params,
                                    String idempotentKey, boolean[] existing) {
        Long userId = CurrentUser.requireUserId();
        if (StringUtils.hasText(idempotentKey)) {
            DesignJob found = findByIdempotentKey(idempotentKey);
            if (found != null) {
                // 规范 §7：同键未终态时拒绝重复提交（原任务还在跑，重提会产生第二份工作）
                if (!JobStatus.valueOf(found.getStatus()).isTerminal()) {
                    throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                            "相同 Idempotency-Key 的任务仍在进行中，请轮询该任务而不是重复提交",
                            Map.of("existingId", found.getId(), "existingJobNo", found.getJobNo(),
                                    "existingStatus", found.getStatus()));
                }
                // 已终态则复用：重放得到同一结果，而不是把活再干一遍
                log.info("Idempotency-Key 命中已终态任务 {}，复用", found.getJobNo());
                existing[0] = true;
                return new CreatedJob(found.getId(), found.getJobNo(), toResponse(found));
            }
        }

        DesignJob job = new DesignJob();
        job.setJobNo(SerialNumbers.jobNo());
        job.setJobType(jobType.name());
        job.setProjectId(projectId);
        job.setRoundId(roundId);
        job.setCandidateId(candidateId);
        job.setUserId(userId);
        job.setBizType(bizType);
        job.setBizId(bizId);
        job.setParamsJson(jsonCodec.write(params));
        job.setStatus(JobStatus.PENDING.name());
        job.setProgress(BigDecimal.ZERO);
        job.setTotalCount(0);
        job.setDoneCount(0);
        job.setIdempotentKey(idempotentKey);
        job.setQueueName(jobType.isAsync() ? jobType.routingKey() : null);
        job.setRetryCount(0);
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一索引是最后一道闸，撞上说明另一个线程刚建好
            DesignJob winner = findByIdempotentKey(idempotentKey);
            if (winner == null) {
                throw e;
            }
            log.info("并发同键，改用已存在的任务 {}", winner.getJobNo());
            existing[0] = true;
            return new CreatedJob(winner.getId(), winner.getJobNo(), toResponse(winner));
        }
        return new CreatedJob(job.getId(), job.getJobNo(), toResponse(job));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void report(String jobNo, String status, Integer totalCount, Integer doneCount,
                       Map<String, Object> result, String errorCode, String errorMsg) {
        if (!StringUtils.hasText(jobNo)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "回报消息缺少 jobNo");
        }
        DesignJob job = jobMapper.selectOne(Wrappers.lambdaQuery(DesignJob.class)
                .eq(DesignJob::getJobNo, jobNo));
        if (job == null) {
            // 抛出让消息重试/进死信，而不是静默丢弃：查不到 jobNo 通常是投递侧写错了编号
            throw new BizException(ErrorCode.JOB_NOT_FOUND, "回报消息的 jobNo 不存在: " + jobNo,
                    Map.of("jobNo", jobNo));
        }
        if (JobStatus.valueOf(job.getStatus()).isTerminal()) {
            // 规范 §7：消费端先校验 job 未终态。MQ 至少投递一次，重投是常态，直接忽略
            log.info("任务 {} 已是终态 {}，忽略重复回报", jobNo, job.getStatus());
            return;
        }
        if (!JobStatus.isValid(status)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的回报状态: " + status,
                    Map.of("allowedStatus", JobStatus.names()));
        }

        JobStatus next = JobStatus.valueOf(status);
        // 规范 §7：PARTIAL 必须逐项写明原因，FAILED 必须给出错误码 —— 否则「部分失败」在报表里
        // 无法解释。宁可让这条消息重试/进死信，也不要收下一条没有原因的部分失败。
        if (next == JobStatus.PARTIAL && !StringUtils.hasText(errorMsg)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "PARTIAL 回报必须逐项写明 errorMsg",
                    Map.of("jobNo", jobNo, "status", status));
        }
        if (next == JobStatus.FAILED && !StringUtils.hasText(errorCode)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "FAILED 回报必须给出 errorCode",
                    Map.of("jobNo", jobNo, "status", status));
        }
        if (next == JobStatus.RUNNING && job.getStartedAt() == null) {
            job.setStartedAt(LocalDateTime.now());
        }
        job.setStatus(next.name());
        if (totalCount != null) {
            job.setTotalCount(totalCount);
        }
        if (doneCount != null) {
            job.setDoneCount(doneCount);
            job.setProgress(progressOf(job.getTotalCount(), doneCount));
        }
        if (result != null) {
            job.setResultJson(jsonCodec.write(result));
        }
        if (next == JobStatus.SUCCEEDED) {
            // 成功要清掉历史错误，否则「投递失败标记」会跟着一个已成功的任务永远显示下去。
            // 注意：updateById 跳过 null 字段，真正的清空在下面 updateById 之后用 UpdateWrapper 做
            job.setErrorCode(null);
            job.setErrorMsg(null);
        } else {
            // 只在回报带了值时才覆盖：否则会把 markDispatchFailure 写下的「未派发」标记抹掉
            if (StringUtils.hasText(errorCode)) {
                job.setErrorCode(errorCode);
            }
            if (StringUtils.hasText(errorMsg)) {
                job.setErrorMsg(errorMsg);
            }
        }
        if (next.isTerminal()) {
            job.setProgress(next == JobStatus.SUCCEEDED ? new BigDecimal("100.00") : job.getProgress());
            job.setCompletedAt(LocalDateTime.now());
        }
        jobMapper.updateById(job);
        if (next == JobStatus.SUCCEEDED) {
            clearError(job.getId());
        }
        log.info("任务 {} 进度回报 -> {}（{}/{}）", jobNo, next, job.getDoneCount(), job.getTotalCount());
    }

    /**
     * 显式清空 error_code / error_msg。
     *
     * <p>必须走 {@code UpdateWrapper}：MyBatis-Plus 的 {@code updateById} 默认策略是
     * 「null 字段不更新」，把字段设成 null 只会被静默跳过 —— 表面上"清了"，库里还在。
     */
    private void clearError(Long jobId) {
        jobMapper.update(null, Wrappers.lambdaUpdate(DesignJob.class)
                .eq(DesignJob::getId, jobId)
                .set(DesignJob::getErrorCode, null)
                .set(DesignJob::getErrorMsg, null));
    }

    @Override
    public void markDispatchFailure(Long jobId, String reason) {
        DesignJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        // 刻意不置 FAILED：消息没投出去不等于任务失败，置 FAILED 会丢掉「本该执行」的语义。
        // 状态保持 PENDING，用 errorCode/errorMsg 把「未投递」显式暴露给调用方。
        job.setErrorCode(ErrorCode.DEPENDENCY_UNAVAILABLE.name());
        job.setErrorMsg("任务未能投递到队列（PENDING 未派发）：" + reason);
        jobMapper.updateById(job);
    }

    @Override
    public void attachBusiness(Long jobId, String bizType, Long bizId) {
        DesignJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BizException(ErrorCode.JOB_NOT_FOUND, "任务不存在: " + jobId, Map.of("jobId", jobId));
        }
        job.setBizType(bizType);
        job.setBizId(bizId);
        jobMapper.updateById(job);
    }

    // ------------------------------------------------------------------ 内部

    private BigDecimal progressOf(Integer total, Integer done) {
        if (total == null || total <= 0 || done == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = BigDecimal.valueOf(done)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
        return ratio.min(new BigDecimal("100.00"));
    }

    private DesignJob findByIdempotentKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return jobMapper.selectOne(Wrappers.lambdaQuery(DesignJob.class)
                .eq(DesignJob::getIdempotentKey, key));
    }

    /** 入参可以是数字 jobId 或 jobNo（契约明确支持两者） */
    private DesignJob loadOwned(String jobIdOrNo) {
        if (!StringUtils.hasText(jobIdOrNo)) {
            throw new BizException(ErrorCode.JOB_NOT_FOUND, "任务标识不能为空", Map.of());
        }
        DesignJob job = jobIdOrNo.chars().allMatch(Character::isDigit)
                ? selectByIdOrNull(jobIdOrNo)
                : jobMapper.selectOne(Wrappers.lambdaQuery(DesignJob.class).eq(DesignJob::getJobNo, jobIdOrNo));
        OwnershipGuard.assertOwned(CurrentUser.requireUserId(),
                job == null ? null : job.getUserId(),
                ErrorCode.JOB_NOT_FOUND, Map.of("jobId", jobIdOrNo));
        return job;
    }

    /**
     * 纯数字按 jobId 查。
     *
     * <p>超出 {@code Long} 范围的数字说明它不可能是本表的 id（自增主键到不了 20 位），
     * 按「查不到」处理即可 —— 直接 {@code Long.valueOf} 会抛 {@code NumberFormatException}，
     * 在全局异常处理里变成 500，把一个参数问题报成服务故障。
     */
    private DesignJob selectByIdOrNull(String digits) {
        try {
            return jobMapper.selectById(Long.valueOf(digits));
        } catch (NumberFormatException e) {
            log.warn("任务标识 {} 是超范围数字，按不存在处理", digits);
            return null;
        }
    }

    private JobResponse toResponse(DesignJob entity) {
        JobResponse response = new JobResponse();
        response.setJobId(entity.getId());
        response.setJobNo(entity.getJobNo());
        response.setJobType(entity.getJobType());
        response.setStatus(entity.getStatus());
        response.setProgress(entity.getProgress());
        response.setTotalCount(entity.getTotalCount());
        response.setDoneCount(entity.getDoneCount());
        response.setBizType(entity.getBizType());
        response.setBizId(entity.getBizId());
        response.setToolId(entity.getToolId());
        response.setErrorCode(entity.getErrorCode());
        response.setErrorMsg(entity.getErrorMsg());
        response.setStartedAt(entity.getStartedAt());
        response.setCompletedAt(entity.getCompletedAt());
        response.setCreatedAt(entity.getCreatedAt());
        Map<String, Object> result = jsonCodec.readMap(entity.getResultJson());
        response.setResult(result);
        if (result != null && result.get("realness") != null) {
            response.setRealness(String.valueOf(result.get("realness")));
        }
        return response;
    }

}
