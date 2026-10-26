package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 放弃分支的响应。
 *
 * <p>刻意不用 {@code EditGapResponse}：放弃分支与缺口诊断是两件事，
 * 一个从未诊断过的候选也应该能被放弃。返回诊断报告会把这个动作
 * 绑死在「必须先诊断」上 —— 那是没有业务理由的耦合。
 *
 * @author SynPharm Team
 */
@Data
public class AbandonResponse {

    private Long candidateId;
    private String branchStatus;
    private String note = "分支已放弃，保留在版本树中，不再参与后续筛选与决策";
}
