package com.synpharm.platform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具视图（契约 `Tool`）。
 *
 * <p>{@code available} 在这里是**布尔**（契约如此），库里是 tinyint；转换只在 DTO 层做，
 * 实体保持与列一致的类型，避免把「0/1 还是 true/false」的判断散落各处。
 *
 * @author SynPharm Team
 */
@Data
@Builder
public class ToolResponse {

    private Long id;

    private String toolCode;

    private String name;

    private String description;

    private String category;

    private String type;

    private String provider;

    private String endpoint;

    private String httpMethod;

    private Map<String, Object> inputSchema;

    private Map<String, Object> outputSchema;

    private Boolean asyncExecution;

    private Integer timeoutMs;

    private String requiresEngine;

    private String capabilityKey;

    private String realness;

    /** 是否启用（管理员开关） */
    private Boolean enabled;

    /** 能力事实（权威）。只由探测回写，不接受客户端传入 */
    private Boolean available;

    /** 探测失败原因，UI 直接展示（红线 R2：不得「点了才失败」） */
    private String probeMsg;

    private LocalDateTime lastProbeAt;

    private Boolean agentExposed;

    private String agentToolName;

    private String version;
}