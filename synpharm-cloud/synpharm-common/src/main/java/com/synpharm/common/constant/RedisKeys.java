package com.synpharm.common.constant;

/**
 * Redis 键约定（规范：{@code docs/conventions/02-数据与配置规范.md}）。
 *
 * <p>键格式 {@code {service}:{domain}:{id}}；跨服务共享的键（如 Token 黑名单）
 * <b>必须</b>全局统一命名，并放在网关可读的同一个 DB 里。
 *
 * @author SynPharm Team
 */
public final class RedisKeys {

    /** Token 黑名单：值为 jti，TTL = Token 剩余有效期 */
    public static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /** 各服务使用的 Redis DB 序号（现状全部挤在 0，拆分时按此表隔离） */
    public static final int DB_GATEWAY = 0;

    public static final int DB_AUTH = 1;
    public static final int DB_PREDICT = 2;
    public static final int DB_DESIGN = 3;
    public static final int DB_ASSET = 4;
    public static final int DB_PLATFORM = 5;

    /** 拼装 Token 黑名单键 */
    public static String tokenBlacklist(String jti) {
        return TOKEN_BLACKLIST_PREFIX + jti;
    }

    private RedisKeys() {
    }
}
