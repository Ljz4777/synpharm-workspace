package com.synpharm.platform.domain.model;

/**
 * 调用类型（契约 openapi.yaml `Tool.type`）。
 *
 * <p>注意：{@code design_tool.type} 列的注释曾写作 "HTTP / LOCAL / MQ"，与契约不符，已更正为
 * HTTP（外部端点）/ INTERNAL（服务内调用，如报告生成）/ LLM（模型直调）。
 *
 * @author SynPharm Team
 */
public enum ToolType {
    HTTP,
    INTERNAL,
    LLM
}