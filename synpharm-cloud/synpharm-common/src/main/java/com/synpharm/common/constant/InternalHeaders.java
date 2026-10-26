package com.synpharm.common.constant;

/**
 * 服务间内部透传头（规范：{@code docs/conventions/01-接口与错误码规范.md} §3.2）。
 *
 * <p><b>安全铁律</b>：这些头只在「经网关」的链路上有意义。网关必须先剥离客户端传入的同名头
 * 再注入自己的值；下游服务不得在未经 {@code InternalHeaderFilter} 判定的情况下信任它们。
 *
 * @author SynPharm Team
 */
public final class InternalHeaders {

    /** 客户端 → 网关：仅为网关解析，<b>不透传</b>给下游 */
    public static final String AUTHORIZATION = "Authorization";

    /** Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 网关 → 服务：当前用户 ID（网关强制覆盖） */
    public static final String USER_ID = "X-User-Id";

    /** 网关 → 服务：角色（USER / ADMIN） */
    public static final String USER_ROLE = "X-User-Role";

    /** 网关 → 服务：用户邮箱（便利字段，避免每服务查 auth） */
    public static final String USER_EMAIL = "X-User-Email";

    /** 网关 → 服务：Token 唯一标识 jti（供登出等场景使用） */
    public static final String TOKEN_ID = "X-Token-Id";

    /** 客户端可生成，缺省由网关生成 */
    public static final String REQUEST_ID = "X-Request-Id";

    /** 全链路追踪 ID */
    public static final String TRACE_ID = "X-Trace-Id";

    /** 仅调用 {@code /internal/**} 时携带 */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    /**
     * 必须由网关先行剥离、再按鉴权结果重新注入的头。
     * 客户端伪造这些头的请求必须被无条件清理。
     */
    public static final String[] FORGEABLE_IDENTITY_HEADERS = {
            USER_ID, USER_ROLE, USER_EMAIL, TOKEN_ID, INTERNAL_TOKEN
    };

    private InternalHeaders() {
    }
}
