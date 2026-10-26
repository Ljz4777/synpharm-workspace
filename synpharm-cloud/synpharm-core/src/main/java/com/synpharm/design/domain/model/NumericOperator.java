package com.synpharm.design.domain.model;

import java.util.Arrays;
import java.util.List;

/**
 * 数值比较算子。{@code BETWEEN} 时必须同时给 {@code thresholdNum} 与 {@code thresholdMax}。
 *
 * @author SynPharm Team
 */
public enum NumericOperator {

    GT,
    GTE,
    LT,
    LTE,
    EQ,
    BETWEEN;

    public static NumericOperator parse(String raw) {
        for (NumericOperator o : values()) {
            if (o.name().equalsIgnoreCase(raw == null ? null : raw.trim())) {
                return o;
            }
        }
        return null;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
