package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 湿实验响应（契约 {@code Experiment}）。
 *
 * @author SynPharm Team
 */
@Data
public class ExperimentResponse {

    private Long id;

    private String experimentNo;

    private Long projectId;

    private Long roundId;

    private Long candidateId;

    private Long reportId;

    private String assayType;

    private String targetRole;

    private String assayProtocol;

    private String resultStatus;

    private BigDecimal resultNum;

    private String resultText;

    private String unit;

    private String operator;

    /** 服务端计算 = resultNum - predictedValue */
    private BigDecimal errorDelta;

    /** 服务端判定；null = 不可判定（缺阈值/缺预测值/实测为有界值） */
    private Boolean isHit;

    private BigDecimal predictedValue;

    private String predictedMetric;

    private Boolean feedbackToModel;

    private String feedbackBatch;

    private LocalDateTime feedbackAt;

    private String calibrationId;

    private Long dataAssetId;

    private String labName;

    private Long operatorId;

    private LocalDateTime createdAt;
}
