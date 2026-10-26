package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 轮次状态（设计域状态机 6.2）：
 * PLANNED → GENERATING → EDITING → EVALUATING → REVIEWING → CLOSED。
 *
 * @author SynPharm Team
 */
public enum RoundStatus {

    PLANNED,
    GENERATING,
    EDITING,
    EVALUATING,
    REVIEWING,
    CLOSED;

    public static RoundStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "轮次状态不能为空");
        }
        for (RoundStatus s : values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的轮次状态: " + raw);
    }

    /** 已关闭：同一项目同时只允许一个未关闭的轮次 */
    public boolean isClosed() {
        return this == CLOSED;
    }
}
