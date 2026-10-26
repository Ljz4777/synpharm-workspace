package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务状态响应（契约 {@code JobStatusResponse}）。
 *
 * @author SynPharm Team
 */
@Data
public class JobResponse {

    private Long jobId;

    private String jobNo;

    /** JobType */
    private String jobType;

    /** JobStatus */
    private String status;

    /** 0~100 */
    private BigDecimal progress;

    private Integer totalCount;

    private Integer doneCount;

    private String bizType;

    private Long bizId;

    private Long toolId;

    private String toolCode;

    /** 从 result_json.realness 透出（design_job 无独立列） */
    private String realness;

    private Map<String, Object> result;

    private String errorCode;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
