package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目响应（契约 {@code Project}）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectResponse {

    private Long id;

    /** 项目编号 P+yyMMddHHmmss+随机 */
    private String projectNo;

    private String name;

    private String goalText;

    private Map<String, Object> goalMetric;

    private Long diseaseId;

    private Long modalityId;

    /** 作用机制码（MOA 字典项 code）；null 表示未指定 */
    private String moaCode;

    /** 生成后置闸门：最少候选数；null 表示未设，基线块7 会判不完整 */
    private Integer minCandidates;

    /** GPU 小时配额；null 表示不限 */
    private BigDecimal quotaGpuHours;

    /** 已消耗 GPU 小时 */
    private BigDecimal quotaGpuUsed;

    /** 湿实验名额；null 表示不限 */
    private Integer quotaWetLab;

    /** 已用掉湿实验名额 */
    private Integer quotaWetUsed;

    /** 决策名额档位：RATIO / ABSOLUTE */
    private String decisionCapacityMode;

    /** 档位参数：RATIO 时 0~1，ABSOLUTE 时正整数 */
    private BigDecimal decisionCapacityValue;

    /** 当前生效基线快照 id；null 表示还没冻结过 */
    private Long currentBaselineId;

    /** 基线版本号，每次内容变更 +1 */
    private Integer projectVersion;

    /** 当前生效的约束集；为空说明尚未绑定 */
    private Long activeConstraintSetId;

    private Long currentRoundId;

    private String status;

    private String summary;

    private LocalDateTime startedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private List<ProjectTargetResponse> targets = new ArrayList<>();

    private List<RoundResponse> rounds = new ArrayList<>();

    private CandidateStats candidateStats;

    /** 候选分子统计（按 {@code review_status} 聚合，数据来自 candidate_molecule） */
    @Data
    public static class CandidateStats {

        private long total;

        private long qualified;

        private long top;
    }

    /**
     * 进入 READY 还缺什么（红线 R2：不得静默就绪）。
     *
     * <p>不放进契约的 {@code Project}，因为它只在「创建/详情」时对用户有意义，
     * 且 {@code Project} 里已由 {@code status} 表达结论。
     */
    private List<String> readyBlockers = new ArrayList<>();
}
