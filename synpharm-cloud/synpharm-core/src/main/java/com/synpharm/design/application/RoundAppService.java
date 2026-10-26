package com.synpharm.design.application;

import com.synpharm.design.api.dto.AcceptedNextRoundResponse;
import com.synpharm.design.api.dto.NextRoundRequest;
import com.synpharm.design.api.dto.RoundOpenRequest;
import com.synpharm.design.api.dto.RoundResponse;

/**
 * 轮次生命周期用例。
 *
 * <p>同一项目**同时只允许一个未关闭的轮次**（{@code DESIGN_ROUND_ALREADY_OPEN}）。
 * 这条约束顺带让 {@code next-round} 具备天然的重放保护：重复提交会命中 409，
 * 不会开出两个轮次（{@code design_round} 没有幂等键列，幂等键落在 {@code design_job} 上）。
 *
 * @author SynPharm Team
 */
public interface RoundAppService {

    /** 开启第一轮：项目须 READY（推导态），成功后项目转 ACTIVE */
    RoundResponse open(Long projectId, RoundOpenRequest request);

    /** 关闭轮次并写结论 */
    RoundResponse close(Long roundId, String conclusionText);

    /** 启动下一轮迭代（202）；上一轮必须已关闭 */
    AcceptedNextRoundResponse nextRound(Long projectId, NextRoundRequest request);
}
