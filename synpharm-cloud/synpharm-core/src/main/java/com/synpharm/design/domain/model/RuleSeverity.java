package com.synpharm.design.domain.model;

/**
 * 规则强制力：HARD 命中即淘汰，SOFT 按 {@code weight} 扣分。
 *
 * @author SynPharm Team
 */
public enum RuleSeverity {

    HARD,
    SOFT;

    /** 非法值一律按 HARD 处理（最保守：宁可多淘汰也不放过） */
    public static RuleSeverity parseOrHard(String raw) {
        if (raw == null || raw.isBlank()) {
            return HARD;
        }
        return SOFT.name().equalsIgnoreCase(raw.trim()) ? SOFT : HARD;
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return HARD.name().equalsIgnoreCase(raw.trim()) || SOFT.name().equalsIgnoreCase(raw.trim());
    }
}
