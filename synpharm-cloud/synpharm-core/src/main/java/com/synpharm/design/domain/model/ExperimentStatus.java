package com.synpharm.design.domain.model;

import java.util.Arrays;

/**
 * 湿实验执行状态：PLANNED / RUNNING / SUCCEEDED / FAILED / INCONCLUSIVE。
 *
 * @author SynPharm Team
 */
public enum ExperimentStatus {

    PLANNED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    INCONCLUSIVE;

    public static boolean isValid(String raw) {
        return raw != null && Arrays.stream(values()).anyMatch(v -> v.name().equals(raw));
    }

    public static String names() {
        return Arrays.toString(values());
    }
}
