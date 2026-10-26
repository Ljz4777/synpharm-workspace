package com.synpharm.platform.domain.model;

/**
 * 提供方（契约 openapi.yaml `Tool.provider`）。
 *
 * <p>列的旧注释写作 "FASTAPI / INTERNAL"，与契约不符，已更正。
 *
 * @author SynPharm Team
 */
public enum ToolProvider {
    FASTAPI,
    SPRINGBOOT,
    EXTERNAL
}