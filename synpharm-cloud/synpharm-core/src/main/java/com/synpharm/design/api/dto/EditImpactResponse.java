package com.synpharm.design.api.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * E3 影响面评估响应（06 §5.7）。
 *
 * @author SynPharm Team
 */
@Data
public class EditImpactResponse {

    private Long id;
    private Long candidateId;
    private String actionCode;

    /** PASS / WARN / BLOCK */
    private String guardResult;

    private String blockReason;

    /** 受影响维度：[{dimension, risk, currentPassed, predictedDelta, note}] */
    private List<Map<String, Object>> protectedOnes;

    /** true 表示可以提交编辑；BLOCK 且未解锁时为 false */
    private Boolean executable;

    private String note = "predictedDelta 恒为 null：动作→指标的量化模型未接入，只给定性风险";
}
