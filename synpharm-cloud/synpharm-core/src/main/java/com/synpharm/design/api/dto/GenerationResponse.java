package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 生成批次响应（06 §4.6）。
 *
 * <p>{@code degraded} 与 {@code degradedReason} 必须回传给前端：
 * 用户需要一眼看出「这批候选是模型生成的，还是规则/导入的」。
 *
 * @author SynPharm Team
 */
@Data
public class GenerationResponse {

    private Long id;
    private String runNo;
    private Long projectId;
    private Long roundId;
    private Long baselineSnapshotId;
    private String status;
    private String strategy;

    /** 是否降级（未使用生成引擎） */
    private Boolean degraded;
    private String degradedReason;

    private Integer nSamples;
    private Integer batchSize;
    private Long seed;
    private BigDecimal estimatedGpuHours;
    private BigDecimal planConfidence;
    private String rationale;
    private String planHash;

    private Integer generatedCount;
    private Integer dedupedCount;
    private Integer rejectedCount;
    private Integer finalCount;
    private Integer validClusterCount;
    private Integer minCandidates;

    private BigDecimal progress;
    private String errorMsg;
    private Integer latencyMs;
    private Long outputSnapshotId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    private Map<String, Object> plan;
    /** 漏斗（S9 数据源） */
    private Map<String, Object> funnel;
    /** 诊断结果 */
    private Map<String, Object> diagnosis;
    private List<SliceView> slices;

    @Data
    public static class SliceView {
        private Integer sliceNo;
        private String status;
        private Integer producedCount;
        private Integer retryCount;
        private Long seed;
        private String errorMsg;
        private LocalDateTime completedAt;
    }
}
