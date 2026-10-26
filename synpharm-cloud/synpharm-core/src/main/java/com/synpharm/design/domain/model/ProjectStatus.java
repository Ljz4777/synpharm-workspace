package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 项目状态：DRAFT → READY → ACTIVE → COMPLETED / CANCELLED → ARCHIVED。
 *
 * <p>{@code READY} 是**推导态**，不接受手工设置：只有「已绑靶点 且 已激活约束集」时才成立。
 *
 * @author SynPharm Team
 */
public enum ProjectStatus {

    DRAFT,
    READY,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    ARCHIVED;

    public static ProjectStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "status 不能为空");
        }
        for (ProjectStatus s : values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的项目状态: " + raw);
    }

    /** 终态：不可再变更 */
    public boolean isTerminal() {
        return this == ARCHIVED;
    }

    /** 结项类终态（可归档） */
    public boolean isClosable() {
        return this == ACTIVE || this == READY || this == DRAFT;
    }
}
