package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 候选版本树（06 §5.7）。
 *
 * <p>「分子不是孤立存在，而是一棵版本树」——这个接口就是那棵树的形状。
 * 放弃的分支（{@code abandoned}）也返回，只是标记出来：
 * 删掉会让「为什么当时那条路走不通」永久丢失。
 *
 * @author SynPharm Team
 */
@Data
public class CandidateTreeResponse {

    /** 树根（最早的祖先） */
    private Long rootId;

    /** 当前查询节点 */
    private Long focusId;

    private List<Node> nodes;

    @Data
    public static class Node {
        private Long id;
        private Long parentId;
        private Integer versionNo;
        private Integer editRound;
        private String branchStatus;
        /** active / abandoned / archived */
        private String pipelineStatus;
        private String originType;
        private String strategyName;

        private String smiles;
        private String sequenceHash;
        private BigDecimal totalScore;
        private Boolean hardPassed;
        /** 是否有可用的评估冻结快照 */
        private Boolean hasFreeze;
        private Integer needsReEval;

        /** 是否是 focus 节点 */
        private Boolean isFocus;
        /** 深度（根为 0） */
        private Integer depth;

        private String note;
        private LocalDateTime createdAt;
    }
}
