package com.synpharm.platform.domain.model;

/**
 * 工具分类（契约 openapi.yaml `Tool.category`）。
 *
 * <p>取值来自冻结契约，不得增减；数据库中存字符串，此处用枚举是为了让请求体里的
 * 非法取值在反序列化阶段就被拒（400 MALFORMED_REQUEST），而不是写进库才发现。
 *
 * @author SynPharm Team
 */
public enum ToolCategory {
    PREDICTION,
    GENERATION,
    EDIT,
    EVALUATION,
    STRUCTURE,
    RETRIEVAL,
    REPORT
}