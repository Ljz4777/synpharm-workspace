package com.synpharm.platform.api.dto;

import com.synpharm.platform.domain.model.Realness;
import com.synpharm.platform.domain.model.ToolCategory;
import com.synpharm.platform.domain.model.ToolProvider;
import com.synpharm.platform.domain.model.ToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 工具注册 / 更新请求（契约 `ToolUpsert`）。
 *
 * <p><b>刻意不包含</b> {@code available} / {@code probeMsg} / {@code lastProbeAt}：
 * 三者是探测回写的**能力事实**，一旦可由客户端传入就等于允许伪造能力（红线 R1）。
 * 缺少对应字段时 Jackson 默认忽略多余字段，因此客户端即便硬塞也不会生效。
 *
 * <p>{@code realness} 必填（契约 required 列表），不接受默认值。
 *
 * @author SynPharm Team
 */
@Data
public class ToolUpsertRequest {

    @NotBlank(message = "toolCode 不能为空")
    @Size(max = 64, message = "toolCode 最长 64 字符")
    private String toolCode;

    @NotBlank(message = "name 不能为空")
    @Size(max = 200, message = "name 最长 200 字符")
    private String name;

    @Size(max = 500, message = "description 最长 500 字符")
    private String description;

    @NotNull(message = "category 不能为空")
    private ToolCategory category;

    private ToolType type = ToolType.HTTP;

    private ToolProvider provider = ToolProvider.FASTAPI;

    @Size(max = 500, message = "endpoint 最长 500 字符")
    private String endpoint;

    private String httpMethod = "POST";

    private Map<String, Object> inputSchema;

    private Map<String, Object> outputSchema;

    private Boolean asyncExecution = Boolean.TRUE;

    private Integer timeoutMs = 60000;

    private String requiresEngine;

    private String capabilityKey;

    @NotNull(message = "realness 不能为空（红线 R1）")
    private Realness realness;

    private Boolean enabled = Boolean.TRUE;

    private Boolean agentExposed = Boolean.FALSE;

    @Size(max = 64, message = "agentToolName 最长 64 字符")
    private String agentToolName;

    @Size(max = 20, message = "version 最长 20 字符")
    private String version;
}