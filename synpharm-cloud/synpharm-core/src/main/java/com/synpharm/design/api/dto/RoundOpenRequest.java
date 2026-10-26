package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开启新一轮请求（契约 {@code RoundOpenRequest}）。
 *
 * @author SynPharm Team
 */
@Data
public class RoundOpenRequest {

    /** 本轮策略说明（人工或 PlannerAgent 产出） */
    @Size(max = 2000, message = "strategyNote 最长 2000 字符")
    private String strategyNote;
}
