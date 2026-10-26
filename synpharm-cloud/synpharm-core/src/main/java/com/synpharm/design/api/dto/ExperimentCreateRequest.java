package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 录入/更正湿实验结果（契约 {@code ExperimentCreateRequest}）。
 *
 * <p><b>注意</b>：{@code errorDelta} 与 {@code isHit} 都由服务端计算，本请求对象里
 * 刻意不提供这两个字段 —— 契约明确「不接受客户端传入」。
 *
 * <p>作为 {@code PATCH} 的请求体时语义为「不传 = 不变」（与项目 PATCH 一致）。
 *
 * @author SynPharm Team
 */
@Data
public class ExperimentCreateRequest {

    @NotNull(message = "projectId 不能为空")
    private Long projectId;

    private Long roundId;

    private Long candidateId;

    private Long reportId;

    @NotBlank(message = "assayType 不能为空")
    private String assayType;

    private String targetRole;

    private String assayProtocol;

    @NotBlank(message = "resultStatus 不能为空")
    private String resultStatus;

    private BigDecimal resultNum;

    private String resultText;

    private String unit;

    /** 不等式算子 GT/GTE/LT/LTE/EQ（如 >10000 nM） */
    private String operator;

    private BigDecimal predictedValue;

    private String predictedMetric;

    private String labName;

    private Long dataAssetId;
}
