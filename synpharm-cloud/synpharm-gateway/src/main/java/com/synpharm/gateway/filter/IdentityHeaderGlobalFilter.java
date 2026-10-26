package com.synpharm.gateway.filter;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.common.constant.RedisKeys;
import com.synpharm.gateway.config.GatewayAuthProperties;
import com.synpharm.gateway.security.JwtVerifier;
import com.synpharm.gateway.security.ParsedToken;
import com.synpharm.gateway.security.TokenValidationException;
import com.synpharm.gateway.web.GatewayErrorWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 身份头过滤器：把客户端的 Token 换成可信的身份头。
 *
 * <p><b>无论鉴权是否通过，都先剥离</b>客户端传入的 {@code X-User-Id} /
 * {@code X-User-Role} / {@code X-User-Email} / {@code X-Token-Id} / {@code X-Internal-Token}
 * —— 否则任何人都能伪装成任意用户（红线 R4 归属校验的前提）。
 *
 * <p>校验顺序：剥离伪造头 → 是否公开路径 / 预检 → Token 验签 → Redis 黑名单 →（注入身份头 | 拒绝）。
 *
 * <p>是否「拒绝」由 {@code synpharm.gateway.auth.enforce} 决定：
 * 阶段 0 为 {@code false}，鉴权未通过时原样转发，由下游自行判定，相对现状零行为变更。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityHeaderGlobalFilter implements GlobalFilter, Ordered {

    /** 排在 {@link TraceIdGlobalFilter} 之后 */
    public static final int ORDER = TraceIdGlobalFilter.ORDER + 10;

    /** 黑名单查询结果 */
    private enum BlacklistState {
        /** 不在黑名单 */
        OK,
        /** 已注销 */
        BLACKLISTED,
        /** Redis 不可用，无法判断 */
        UNAVAILABLE
    }

    private final JwtVerifier jwtVerifier;
    private final GatewayAuthProperties authProperties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayErrorWriter errorWriter;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 必须在剥离之前取 Token：forward-authorization=false 时 Authorization 会被移除
        String token = extractToken(exchange.getRequest());
        ServerWebExchange sanitized = stripForgedHeaders(exchange);
        ServerHttpRequest request = sanitized.getRequest();

        // CORS 预检不携带凭据
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(sanitized);
        }

        if (isPublicPath(request.getPath().value())) {
            return chain.filter(sanitized);
        }

        if (token == null) {
            return rejectOrForward(sanitized, chain, ErrorCode.UNAUTHORIZED, "请求未携带 Token");
        }

        ParsedToken parsed;
        try {
            parsed = jwtVerifier.verify(token);
        } catch (TokenValidationException e) {
            return rejectOrForward(sanitized, chain, e.getErrorCode(), "Token 校验失败");
        }

        return checkBlacklist(parsed)
                .flatMap(state -> switch (state) {
                    case BLACKLISTED ->
                            rejectOrForward(sanitized, chain, ErrorCode.TOKEN_BLACKLISTED, "Token 已注销");
                    case UNAVAILABLE ->
                            rejectOrForward(sanitized, chain, ErrorCode.DEPENDENCY_UNAVAILABLE, "鉴权依赖不可用");
                    case OK -> chain.filter(injectIdentity(sanitized, parsed));
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 查询 Token 黑名单。
     *
     * <p>把 Redis 异常收敛成 {@link BlacklistState#UNAVAILABLE} 而不是向外抛出 ——
     * 这样 {@code onErrorResume} 只覆盖 Redis 调用本身，不会误吞下游链路的异常。
     * 未知状态按「无法判断」处理（红线 R7：禁止静默兜底）。
     */
    private Mono<BlacklistState> checkBlacklist(ParsedToken parsed) {
        if (!StringUtils.hasText(parsed.tokenId())) {
            return Mono.just(BlacklistState.OK);
        }
        return redisTemplate.hasKey(RedisKeys.tokenBlacklist(parsed.tokenId()))
                .map(hit -> Boolean.TRUE.equals(hit) ? BlacklistState.BLACKLISTED : BlacklistState.OK)
                .defaultIfEmpty(BlacklistState.OK)
                .onErrorResume(e -> {
                    log.error("Token 黑名单查询失败，无法判断凭据是否已注销: {}", e.getMessage());
                    return Mono.just(BlacklistState.UNAVAILABLE);
                });
    }

    /** 移除客户端伪造的身份头，并盖上网关自己的内部令牌 */
    private ServerWebExchange stripForgedHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    for (String name : InternalHeaders.FORGEABLE_IDENTITY_HEADERS) {
                        // 含 X-Internal-Token：客户端带来的必须先删，否则等于自己发通行证
                        headers.remove(name);
                    }
                    if (!authProperties.isForwardAuthorization()) {
                        headers.remove(InternalHeaders.AUTHORIZATION);
                    }
                    if (StringUtils.hasText(authProperties.getInternalToken())) {
                        headers.set(InternalHeaders.INTERNAL_TOKEN, authProperties.getInternalToken());
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /** 注入网关验签得出的身份头 */
    private ServerWebExchange injectIdentity(ServerWebExchange exchange, ParsedToken token) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(InternalHeaders.USER_ID, String.valueOf(token.userId()));
                    setIfPresent(headers, InternalHeaders.USER_ROLE, token.role());
                    setIfPresent(headers, InternalHeaders.USER_EMAIL, token.email());
                    setIfPresent(headers, InternalHeaders.TOKEN_ID, token.tokenId());
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * 鉴权未通过：{@code enforce=true} 时网关直接拒绝；否则原样转发（阶段 0）。
     *
     * <p>转发路径下<b>不会</b>注入任何身份头，下游只能靠自己判定。
     */
    private Mono<Void> rejectOrForward(ServerWebExchange exchange, GatewayFilterChain chain,
                                       ErrorCode errorCode, String reason) {
        if (authProperties.isEnforce()) {
            log.debug("鉴权失败[{}]，网关拒绝 {}：{}", errorCode.getErrorCode(),
                    exchange.getRequest().getURI().getPath(), reason);
            return errorWriter.write(exchange, errorCode);
        }
        log.debug("鉴权失败[{}]，enforce=false 原样转发给下游处置 {}：{}", errorCode.getErrorCode(),
                exchange.getRequest().getURI().getPath(), reason);
        return chain.filter(exchange);
    }

    private boolean isPublicPath(String path) {
        List<String> publicPaths = authProperties.getPublicPaths();
        if (publicPaths == null || publicPaths.isEmpty()) {
            return false;
        }
        return publicPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private static String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(InternalHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(InternalHeaders.BEARER_PREFIX)) {
            String token = header.substring(InternalHeaders.BEARER_PREFIX.length()).trim();
            return StringUtils.hasText(token) ? token : null;
        }
        return null;
    }

    private static void setIfPresent(org.springframework.http.HttpHeaders headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }
}
