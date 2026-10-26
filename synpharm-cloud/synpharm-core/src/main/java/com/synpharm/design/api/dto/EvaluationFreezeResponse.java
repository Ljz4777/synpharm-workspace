package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

/**
 * 评估冻结快照响应（06 §5.1）。
 *
 * <p>{@code weightCoverage} 必须回传：小分子目前只有约三分之一的权重能算出值，
 * 用户在看到一个「82 分」时必须同时知道「这只代表 35% 的指标」。
 *
 * @author SynPharm Team
 */
@Data
public class EvaluationFreezeResponse {

    private Long id;
    private String freezeNo;
    private Long projectId;
    private Long candidateId;
    private Integer versionNo;
    private Long evalRunId;

    /** {metric: {value, normalized, passed, risk, note}} */
    private Map<String, Object> scores;

    private Boolean hardPassed;
    private BigDecimal totalScore;

    /** 可算权重占比 0~1；totalScore 只代表这部分 */
    private BigDecimal weightCoverage;

    /** 权重里有但算不出来的指标 */
    private java.util.List<String> missingMetrics;

    /** 未能判定的规则码（没检查 ≠ 检查通过） */
    private java.util.List<String> skippedRules;

    /** 硬约束违例明细 */
    private java.util.List<String> violations;

    private Map<String, Object> engineVersions;
    private String calibrationId;
    private String contentHash;
    private LocalDateTime createdAt;
}
