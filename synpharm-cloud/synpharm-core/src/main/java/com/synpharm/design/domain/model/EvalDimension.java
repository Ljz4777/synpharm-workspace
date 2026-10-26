package com.synpharm.design.domain.model;

import java.util.Arrays;
import java.util.List;

/**
 * 评估维度（驱动加权与红黄绿）。与知识库 {@code OBJECTIVE} 分类同名，
 * 便于前端把「目标」与「约束」对上。
 *
 * @author SynPharm Team
 */
public enum EvalDimension {

    AFFINITY,
    STABILITY,
    EXPRESSION,
    ADMET,
    SYNTHESIZABILITY,
    DEVELOPABILITY,
    SELECTIVITY,
    IMMUNOGENICITY;

    public static boolean isValid(String raw) {
        return raw != null && Arrays.stream(values()).anyMatch(v -> v.name().equalsIgnoreCase(raw.trim()));
    }

    /** 供 {@code /constraints/meta} 下发 */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
