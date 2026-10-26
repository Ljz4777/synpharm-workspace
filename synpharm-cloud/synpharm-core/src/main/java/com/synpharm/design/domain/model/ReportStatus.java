package com.synpharm.design.domain.model;

import java.util.Arrays;

/**
 * 报告正文的生成状态：PENDING / GENERATING / SUCCEEDED / PARTIAL / FAILED。
 *
 * <p>与 {@link ReportDecision} 正交：{@code status} 说的是「正文生成到哪一步」，
 * {@code decision} 说的是「人怎么批」。正文没生成完就审批是没有意义的，
 * 因此决策前必须先校验 {@code status}。
 *
 * @author SynPharm Team
 */
public enum ReportStatus {

    PENDING,
    GENERATING,
    SUCCEEDED,
    PARTIAL,
    FAILED;

    /** 正文可用（SUCCEEDED 完整、PARTIAL 部分成功）：才允许审批 */
    public static boolean isDecidable(String raw) {
        return raw != null
                && (SUCCEEDED.name().equals(raw) || PARTIAL.name().equals(raw));
    }

    public static boolean isValid(String raw) {
        return raw != null && Arrays.stream(values()).anyMatch(v -> v.name().equals(raw));
    }
}
