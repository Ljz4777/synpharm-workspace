package com.synpharm.design.api.dto;

import java.util.List;
import lombok.Data;

/**
 * 回放结果（D8，07 §10 / B4）。
 *
 * <p>它回答的不是「能不能确认」而是<b>「换个人、换个时间，用同样输入重算，会不会得到同样的名单」</b>。
 * 所以返回的是<span>差异清单</span>而不是一个布尔值：只说「不一致」而不说是哪个候选
 * 从第几名掉到了哪里，用户无法判断该不该接受这个变化。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionReplayResponse {

    private Long decisionId;
    private String decisionNo;

    /** 记录里存的两件指纹 vs 现在重算出来的 */
    private String storedInputHash;
    private String recomputedInputHash;
    private String storedRuleVersion;
    private String currentRuleVersion;

    /** 全部一致才为 true（输入指纹相同且逐候选的桶/名次/去向都没变） */
    private boolean same;

    /** 逐候选差异；空表示没变 */
    private List<ReplayDiff> diffs;

    /** 上次决策后新出现的候选（有冻结快照但当时没参与） */
    private List<Long> addedCandidates;

    /** 上次参与过、现在不再参与（分支被放弃 / 快照被删） */
    private List<Long> removedCandidates;

    private List<String> warnings;

    /** 单条候选差异 */
    @Data
    public static class ReplayDiff {
        private Long candidateId;
        private String moleculeName;
        private String storedBucket;
        private String currentBucket;
        private Integer storedRankNo;
        private Integer currentRankNo;
        private String storedOutcome;
        private String currentOutcome;
        /** 人话说明改了哪几项 */
        private List<String> changes;
    }
}
