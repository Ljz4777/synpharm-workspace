package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 项目内绑定靶点（契约 {@code ProjectTargetBindRequest}）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectTargetBindRequest {

    private Long targetId;

    /** WILD_TYPE / MUTANT / ANTI_TARGET / OFF_TARGET；缺省 WILD_TYPE */
    private String role;

    private Boolean isPrimary;

    private Integer sortNo;
}
