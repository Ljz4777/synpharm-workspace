package com.synpharm.design.api.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

/**
 * 基线快照详情（{@code GET /projects/{id}/baseline}）。
 *
 * <p>八块以 Map 形式原样透出（数据库里存的就是 JSON），不做二次塑形 ——
 * 快照是<b>冻结副本</b>，任何「顺手规范化」都会让它与实际冻结的内容不一致。
 *
 * @author SynPharm Team
 */
@Data
public class BaselineResponse {

    private Long id;

    private String snapshotNo;

    private Long projectId;

    private Integer projectVersion;

    private Long userId;

    /** 块1 靶点列表 + 主靶点角色 */
    private Map<String, Object> targets;

    /** 块2 prep 后的结构 URI/SHA256/口袋 */
    private Map<String, Object> structure;

    /** 块3 分子形式 */
    private String modalityCode;

    /** 块4 硬约束快照 */
    private Map<String, Object> hardRules;

    /** 块5 软约束 + 权重 */
    private Map<String, Object> softRules;

    /** 块6 指标清单 + 权重 + 目标 + 误差带 */
    private Map<String, Object> metrics;

    /** 块7 GPUh / 湿实验名额 / min_candidates */
    private Map<String, Object> quota;

    /** 块8 风险关注点 */
    private Map<String, Object> risks;

    /** 逐块校验结果 */
    private Map<String, String> completeness;

    private String contentHash;

    /** ACTIVE / SUPERSEDED */
    private String status;

    /** 被哪个快照取代（仅 SUPERSEDED） */
    private Long supersededBy;

    private LocalDateTime createdAt;
}
