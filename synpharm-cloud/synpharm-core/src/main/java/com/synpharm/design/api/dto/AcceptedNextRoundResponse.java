package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 启动下一轮迭代响应（契约 {@code AcceptedNextRound}，HTTP 202）。
 *
 * <p>{@code jobId} / {@code status} 为 null 表示「只开了轮次、没投递任务」——
 * 即 {@code autoStartGeneration=false} 的情形。
 *
 * @author SynPharm Team
 */
@Data
public class AcceptedNextRoundResponse {

    private Long roundId;

    private Integer roundNo;

    private Long jobId;

    private String status;
}
