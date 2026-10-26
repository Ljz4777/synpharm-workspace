package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 项目内靶点绑定响应（契约 {@code ProjectTarget}）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectTargetResponse {

    private Long id;

    private Long targetId;

    /** 取自 {@code target.name}，省得前端再查一次 */
    private String targetName;

    private Boolean isPrimary;

    private String role;

    private Integer sortNo;
}
