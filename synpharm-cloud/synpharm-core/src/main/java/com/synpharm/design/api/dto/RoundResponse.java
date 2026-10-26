package com.synpharm.design.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 轮次响应（契约 {@code Round}）。
 *
 * <p>阶段 2B 先只读：轮次的开启/关闭在下一批实现，此处的 {@code rounds} 目前恒为空数组。
 *
 * @author SynPharm Team
 */
@Data
public class RoundResponse {

    private Long id;

    private Long projectId;

    private Integer roundNo;

    private String strategyNote;

    private String status;

    private String conclusionText;

    private Object metrics;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;
}
