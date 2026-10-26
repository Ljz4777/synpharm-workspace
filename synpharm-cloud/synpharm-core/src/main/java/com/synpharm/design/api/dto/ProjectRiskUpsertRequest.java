package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 风险关注点的新增/修改请求（{@code POST|PUT /projects/{id}/risks}）。
 *
 * <p>{@code mustVerify=true} 的风险点会在决策阶段被强制要求「有实测证据」，
 * 不是装饰性字段。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectRiskUpsertRequest {

    /** 风险码，自由文本但建议用字典 FILTER_REASON 或维度名保持可聚合 */
    private String riskCode;

    /** 关联维度（AFFINITY / IMMUNOGENICITY …），可空 */
    private String dimension;

    private String description;

    /** LOW / MEDIUM / HIGH / CRITICAL；默认 MEDIUM */
    private String severity;

    /** 是否必须验证；默认 false */
    private Boolean mustVerify;

    /** MANUAL / MODALITY_DEFAULT / FEEDBACK；默认 MANUAL */
    private String source;
}
