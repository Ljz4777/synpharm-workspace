package com.synpharm.design.application;

import com.synpharm.design.api.dto.EvaluationFreezeResponse;

/**
 * 最小评估用例（06 §5.1）。
 *
 * <p>「冻结」是显式动作，不是评估完自动落库 —— 与 {@code baseline:freeze} 同一取舍：
 * 用户要能看清「这次冻结用的是哪份基线、哪些指标算不出来」，而不是某次后台任务
 * 悄悄写了一条谁也说不清的记录。
 *
 * @author SynPharm Team
 */
public interface EvaluationFreezeAppService {

    /**
     * 对候选跑最小评估并冻结。
     *
     * <p>幂等：同一候选 + 同一基线 + 同内容哈希重复冻结，返回已有的那条快照。
     *
     * @param candidateId 候选 id
     * @param baselineSnapshotId 为空取项目当前 ACTIVE 快照
     */
    EvaluationFreezeResponse freeze(Long candidateId, Long baselineSnapshotId);

    /** 候选最新一次冻结快照；没有则抛 {@code RESOURCE_NOT_FOUND} */
    EvaluationFreezeResponse latest(Long candidateId);

    /** 候选的冻结历史（新→旧），用于对照「换基线后分数怎么变」 */
    java.util.List<EvaluationFreezeResponse> history(Long candidateId);
}
