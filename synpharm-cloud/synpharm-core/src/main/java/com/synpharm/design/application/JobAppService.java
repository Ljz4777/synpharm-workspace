package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.JobResponse;
import com.synpharm.design.domain.model.JobType;

import java.util.Map;

/**
 * 任务（{@code design_job}）用例。
 *
 * <p>任务表同时承担两个职责：**异步任务的进度载体** 与 **幂等键存储**
 * （{@code uk_idempotent_key} 唯一索引）。后者让「带 {@code Idempotency-Key} 的重复提交」
 * 收敛到同一条任务，而不是产生第二份业务数据。
 *
 * @author SynPharm Team
 */
public interface JobAppService {

    /** 任务列表（可按项目 / 类型 / 状态过滤，仅本人） */
    PageResult<JobResponse> list(Long projectId, String jobType, String status, int page, int pageSize);

    /** 查询任务：入参可以是数字 jobId，也可以是 jobNo */
    JobResponse get(String jobIdOrNo);

    /** 取消任务；已终态返回 409 */
    void cancel(String jobIdOrNo);

    /**
     * 登记任务（不投递）。
     *
     * <p>{@code idempotentKey} 非空且已存在时，<b>返回既有任务</b>而不是新建 —— 调用方据此知道
     * 这是重放，可直接复用已有 jobId。
     *
     * @param existing 出参：是否命中已有任务（重放）
     */
    CreatedJob createOrReuse(JobType jobType, Long projectId, Long roundId, Long candidateId,
                             String bizType, Long bizId, Map<String, Object> params,
                             String idempotentKey, boolean[] existing);

    /** 计算层回报：更新任务状态/进度/结果（按 jobNo 定位，终态任务忽略重复回报） */
    void report(String jobNo, String status, Integer totalCount, Integer doneCount,
                Map<String, Object> result, String errorCode, String errorMsg);

    /**
     * 投递失败登记：状态保持 PENDING，只把「未派发」写进 errorCode/errorMsg。
     *
     * <p>刻意不置 FAILED —— 消息没投出去不等于任务失败，置 FAILED 会丢掉「本该执行」的语义。
     */
    /**
     * 投递失败登记：状态保持 PENDING，只把「未派发」写进 errorCode/errorMsg。
     *
     * <p>刻意不置 FAILED —— 消息没投出去不等于任务失败，置 FAILED 会丢掉「本该执行」的语义。
     */
    void markDispatchFailure(Long jobId, String reason);

    /**
     * 登记任务的业务关联（{@code biz_type}/{@code biz_id}）。
     *
     * <p>存在的原因：任务的业务主体（如报告行）往往在任务之后才拿到自增 ID，
     * 所以先建任务、再回填关联，两步在同一个事务里。
     */
    void attachBusiness(Long jobId, String bizType, Long bizId);

    /**
     * 新建任务的最小信息。
     *
     * @param id  任务主键
     * @param no  任务编号
     * @param job 完整响应（含状态）
     */
    record CreatedJob(Long id, String no, JobResponse job) {
    }
}
