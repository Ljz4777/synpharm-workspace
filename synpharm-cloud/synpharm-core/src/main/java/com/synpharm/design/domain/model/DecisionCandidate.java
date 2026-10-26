package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * decision_candidate · 决策候选明细（07 §5.1）。
 *
 * <p><b>FAIL 桶也必须落库</b>：它们不参与排序也不占名额，但「这个分子为什么被淘汰」
 * 与「为什么入选」同样有价值 —— 回流阶段的负样本就来自这里。
 *
 * <p>{@code rankNo} 只在桶内有意义，FAIL 桶为 {@code null}：
 * 给淘汰对象编个名次会把「没通过」伪装成「排在后面」。
 *
 * @author SynPharm Team
 */
@Data
@TableName("decision_candidate")
public class DecisionCandidate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long decisionId;

    private Long candidateId;

    private Long evidenceBundleId;

    /** FULL_PASS / TOLERANCE / FAIL */
    private String bucket;

    /** 桶内排名；FAIL 桶为 null */
    private Integer rankNo;

    /** Σ(w×s)/Σw，只对**有数据**的指标求和 */
    private BigDecimal weightedScore;

    private Integer coversMetrics;

    /** 缺数据的指标码，用于解释「为什么分母比别人的小」 */
    private String missingMetricsJson;

    /** SELECTED / BACKUP / REJECTED / DEFERRED */
    private String outcome;

    /** FILTER_REASON 字典（stage=DECISION） */
    private String rejectReason;

    private Integer manualOverride;

    /** manualOverride=1 时必填 */
    private String overrideReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
