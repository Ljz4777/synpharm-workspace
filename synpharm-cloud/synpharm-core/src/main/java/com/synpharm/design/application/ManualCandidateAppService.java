package com.synpharm.design.application;

import com.synpharm.design.api.dto.ManualCandidateRequest;
import com.synpharm.design.api.dto.ManualCandidateResponse;

/**
 * 手工候选（二维画布 → 候选）。
 *
 * <p>为什么需要它：生成漏斗是「引擎产分子」，但设计不总是从零生成 ——
 * 化学家经常是「我脑子里有个结构，画出来看看它过不过约束」。
 * 在这条路打通之前，画布产物只能复制 SMILES 走人，进不了评估也进不了决策。
 *
 * @author SynPharm Team
 */
public interface ManualCandidateAppService {

    /**
     * 用 SMILES 建一个候选并挂到项目当前轮次。
     *
     * <p>结构合法性、归一化 SMILES、InChIKey、理化指标全部由计算层实算；
     * 同项目同轮次里 InChIKey 相同的分子不重复建（返回已有那条）。
     */
    ManualCandidateResponse create(Long projectId, ManualCandidateRequest request);
}
