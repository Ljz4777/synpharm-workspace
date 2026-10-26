package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 约束集状态：DRAFT → ACTIVE → ARCHIVED。
 *
 * <p>只有 DRAFT 可编辑（{@code CONSTRAINT_SET_NOT_EDITABLE}）；激活前必须通过自检。
 *
 * @author SynPharm Team
 */
public enum ConstraintStatus {

    DRAFT,
    ACTIVE,
    ARCHIVED;

    public static ConstraintStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "status 不能为空");
        }
        return switch (raw.trim().toUpperCase()) {
            case "DRAFT" -> DRAFT;
            case "ACTIVE" -> ACTIVE;
            case "ARCHIVED" -> ARCHIVED;
            default -> throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 status: " + raw);
        };
    }

    /** 宽松解析：非法值返回 null（查询条件场景） */
    public static ConstraintStatus tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parse(raw);
        } catch (BizException e) {
            return null;
        }
    }
}
