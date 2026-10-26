package com.synpharm.design.support;

/**
 * SQL LIKE 模式转义。
 *
 * <p>MySQL 的 LIKE 默认以 {@code \} 为转义符，{@code %} 与 {@code _} 是通配符。
 * 知识库的 {@code code} 允许下划线（种子数据里就有 {@code KD_SPR}），
 * 直接把它拼进 LIKE 模式会让 {@code _} 匹配任意单字符，从而命中兄弟节点。
 *
 * @author SynPharm Team
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** 转义 {@code \} / {@code %} / {@code _}，使其在 LIKE 模式中按字面量匹配 */
    public static String escape(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
