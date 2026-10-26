package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 新建决策请求（07 §8）。
 *
 * <p>不接收候选清单：候选集由项目当前轮次的「已评估冻结」候选推导。
 * 让调用方传候选 id 等于让它决定「拿谁比较」，那本身就是一次未留痕的决策。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionCreateRequest {

    /** 指定轮次；为空表示取项目当前轮次 */
    private Long roundId;

    /** 决策备注（可选，自由文本） */
    private String decisionNote;
}