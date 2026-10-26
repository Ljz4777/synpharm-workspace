package com.synpharm.design.domain.model;

import java.util.Arrays;

/**
 * 任务状态（契约 {@code JobStatus}）。
 *
 * @author SynPharm Team
 */
public enum JobStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED;

    /**
     * 是否终态。
     *
     * <p>消费端必须先校验「job 未终态」再干活（规范 §7 共同约定）—— 这是防重复消费的第一道闸，
     * 因为 MQ 至少投递一次，重投是常态而非异常。
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    /** 可被取消：终态任务不可取消 */
    public boolean isCancellable() {
        return this == PENDING || this == RUNNING;
    }

    public static boolean isValid(String raw) {
        return raw != null && Arrays.stream(values()).anyMatch(v -> v.name().equals(raw));
    }

    public static String names() {
        return Arrays.toString(values());
    }
}
