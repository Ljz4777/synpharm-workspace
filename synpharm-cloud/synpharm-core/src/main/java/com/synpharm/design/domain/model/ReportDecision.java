package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 报告审批结论（设计域状态机 6.5）：PENDING → APPROVED / REVISE / REJECTED。
 *
 * @author SynPharm Team
 */
public enum ReportDecision {

    PENDING,
    APPROVED,
    REVISE,
    REJECTED;

    public static ReportDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "decision 不能为空");
        }
        for (ReportDecision d : values()) {
            if (d.name().equalsIgnoreCase(raw.trim())) {
                return d;
            }
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 decision: " + raw);
    }

    /** 审批时只接受三种结论，PENDING 不是可提交的决策 */
    public static ReportDecision parseDecidable(String raw) {
        ReportDecision decision = parse(raw);
        if (decision == PENDING) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "decision 只能是 APPROVED / REVISE / REJECTED");
        }
        return decision;
    }

    /** REVISE / REJECTED 必须写意见（否则用户不知道被打回的原因） */
    public boolean requiresNote() {
        return this == REVISE || this == REJECTED;
    }

    /** 已决策：不可重复审批 */
    public boolean isDecided() {
        return this != PENDING;
    }
}
