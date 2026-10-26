package com.synpharm.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具注册表条目（内网 {@code GET /internal/tools/registry} 的返回项）。
 *
 * <p>为什么不复用 {@link CapabilityItem}：能力矩阵（`/api/design/capabilities`）是给前端画
 * **状态灯**的，只需要「谁可用、为什么不可用」；而 design 的命令注册表要按 {@code category}
 * 分组、用 {@code name} 做按钮文案，还需要 {@code agentToolName} 供二期智能体复用同一张表。
 * 二者字段集不同、演化节奏也不同，混用会让公开契约被内部需求牵着走。
 *
 * @author SynPharm Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRegistryItem {

    private String toolCode;

    private String name;

    private String category;

    /** 真实性边界，命令面板需要据此提示（红线 R1） */
    private String realness;

    private Boolean enabled;

    /** 能力事实（权威），只由探测回写 */
    private Boolean available;

    /** 不可用原因，UI 直接展示（红线 R2） */
    private String probeMsg;

    private Boolean agentExposed;

    private String agentToolName;
}
