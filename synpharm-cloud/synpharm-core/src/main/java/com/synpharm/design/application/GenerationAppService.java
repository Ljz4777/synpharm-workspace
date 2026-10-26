package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.GenerationCandidateLogResponse;
import com.synpharm.design.api.dto.GenerationCreateRequest;
import com.synpharm.design.api.dto.GenerationResponse;
import java.util.List;

/**
 * 生成批次用例（06 §4）。
 *
 * @author SynPharm Team
 */
public interface GenerationAppService {

    /**
     * 提交生成。同步跑完 S0~S9 后返回最终状态。
     *
     * <p>幂等：同 {@code idempotencyKey} 重复提交返回已有批次，不产生第二份候选集。
     */
    GenerationResponse submit(Long projectId, GenerationCreateRequest request);

    /** 批次列表（新→旧） */
    PageResult<GenerationResponse> list(Long projectId, int page, int pageSize);

    /** 批次详情（含 plan / 分片 / 漏斗 / 诊断） */
    GenerationResponse detail(Long runId);

    /** 漏斗四段转化率 */
    GenerationResponse funnel(Long runId);

    /** S9 诊断（verdict + findings + 建议） */
    GenerationResponse diagnosis(Long runId);

    /** 逐分子筛选日志（按 stage / outcome 过滤，诊断用） */
    List<GenerationCandidateLogResponse> candidateLogs(Long runId, String stage, String outcome);

    /** 取消：未开始的批次直接置 CANCELLED；已完成的不允许取消 */
    GenerationResponse cancel(Long runId);
}
