package com.synpharm.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 能力矩阵中的单个工具项（契约 `Capabilities.tools[]`）。
 *
 * @author SynPharm Team
 */
@Data
@AllArgsConstructor
public class CapabilityItem {

    private String toolCode;

    private Boolean available;

    private String probeMsg;

    private String realness;
}