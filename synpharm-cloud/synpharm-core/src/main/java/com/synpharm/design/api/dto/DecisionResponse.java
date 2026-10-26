package com.synpharm.design.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 决策记录响应（07 §5.1）。
 *
 * <p>阶段 E2~E5 只产出 {@code DRAFT}；确认/暂缓/取消在 E6~E8。
 *
 * <p>三个回放字段（{@code weightsSnapshot} / {@code ruleVersion} / {@code inputSnapshotHash}）
 * 一律回传：它们是「换个人按同样输入重算会不会得到同样结论」的唯一凭据。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionResponse {

    private Long id;
    private String decisionNo;
    private Long projectId;
    private Long roundId;
    private Long baselineSnapshotId;

    /** DRAFT / CONFIRMED / DEFERRED / CANCELLED */
    private String status;

    /** 排序用的指标权重快照 */
    private Map<String, Object> weightsSnapshot;

    private String ruleVersion;

    /** 全部证据包 hash 的排序聚合 */
    private String inputSnapshotHash;

    private Integer selectedCount;
    private Integer backupCount;
    private Integer rejectedCount;
    private Integer deferredCount;

    /** 实际名额（档位与湿实验余额取小） */
    private Integer capacity;

    private String decisionNote;

    private Integer versionNo;
    private Long revisionOf;

    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    private List<DecisionCandidateResponse> candidates;

    /** 规则层与名额层的告警（如 NO_VIABLE_CANDIDATE）；不是错误 */
    private List<String> warnings;
}