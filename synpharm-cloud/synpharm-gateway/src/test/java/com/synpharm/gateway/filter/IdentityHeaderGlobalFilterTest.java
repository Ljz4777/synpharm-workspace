package com.synpharm.gateway.filter;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.gateway.config.GatewayAuthProperties;
import com.synpharm.gateway.config.JwtProperties;
import com.synpharm.gateway.security.JwtVerifier;
import com.synpharm.gateway.web.GatewayErrorWriter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IdentityHeaderGlobalFilter} 单元测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li>客户端伪造的身份头<b>永远</b>被剥离（否则任何人都能伪装成任意用户）；</li>
 *   <li>阶段 0（{@code enforce=false}）不改变行为，只有 {@code enforce=true} 才拦截。</li>
 * </ol>
 */
class IdentityHeaderGlobalFilterTest {

    private static final String SECRET = "synpharm-unit-test-secret-0123456789abcdef";

    private ReactiveStringRedisTemplate redisTemplate;
    private GatewayErrorWriter errorWriter;
    private GatewayAuthProperties authProperties;
    private JwtVerifier jwtVerifier;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        errorWriter = mock(GatewayErrorWriter.class);
        when(errorWriter.write(any(), any())).thenReturn(Mono.empty());

        authProperties = new GatewayAuthProperties();
        authProperties.setEnforce(false);
        authProperties.setForwardAuthorization(true);
        authProperties.setPublicPaths(List.of("/api/auth/login", "/actuator/**"));

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtVerifier = new JwtVerifier(jwtProperties);
        jwtVerifier.init();
    }

    private IdentityHeaderGlobalFilter filter() {
        return new IdentityHeaderGlobalFilter(jwtVerifier, authProperties, redisTemplate, errorWriter);
    }

    private static String validToken() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("1024")
                .claim("email", "chemist@synpharm.local")
                .claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static MockServerWebExchange request(String path, Map<String, String> headers, String bearerToken) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        headers.forEach(builder::header);
        if (bearerToken != null) {
            builder.header(InternalHeaders.AUTHORIZATION, InternalHeaders.BEARER_PREFIX + bearerToken);
        }
        return MockServerWebExchange.from(builder.build());
    }

    /** 捕获链路真正收到的 exchange，供断言使用 */
    private static GatewayFilterChain capturing(AtomicReference<ServerWebExchange> sink) {
        return exchange -> {
            sink.set(exchange);
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("无条件剥离客户端伪造的身份头（stage 0 也不放行）")
    void stripsForgedIdentityHeaders() {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange input = request("/api/predict/tasks", Map.of(
                InternalHeaders.USER_ID, "999",
                InternalHeaders.USER_ROLE, "ADMIN",
                InternalHeaders.USER_EMAIL, "attacker@evil.local",
                InternalHeaders.INTERNAL_TOKEN, "forged"), null);

        filter().filter(input, capturing(seen)).block();

        HttpHeaders headers = seen.get().getRequest().getHeaders();
        assertThat(headers.getFirst(InternalHeaders.USER_ID)).isNull();
        assertThat(headers.getFirst(InternalHeaders.USER_ROLE)).isNull();
        assertThat(headers.getFirst(InternalHeaders.USER_EMAIL)).isNull();
        assertThat(headers.getFirst(InternalHeaders.INTERNAL_TOKEN)).isNull();
    }

    @Test
    @DisplayName("有效 Token 注入网关验签得出的身份头")
    void injectsIdentityFromVerifiedToken() {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange input = request("/api/predict/tasks", Map.of(
                // 客户端伪造的值必须被真实身份覆盖，而不是并存
                InternalHeaders.USER_ID, "999"), validToken());

        filter().filter(input, capturing(seen)).block();

        HttpHeaders headers = seen.get().getRequest().getHeaders();
        assertThat(headers.getFirst(InternalHeaders.USER_ID)).isEqualTo("1024");
        assertThat(headers.getFirst(InternalHeaders.USER_ROLE)).isEqualTo("USER");
        assertThat(headers.getFirst(InternalHeaders.USER_EMAIL)).isEqualTo("chemist@synpharm.local");
        assertThat(headers.getFirst(InternalHeaders.AUTHORIZATION)).startsWith("Bearer ");
    }

    @Test
    @DisplayName("公开路径不注入身份头")
    void doesNotInjectOnPublicPath() {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange input = request("/api/auth/login", Map.of(), validToken());

        filter().filter(input, capturing(seen)).block();

        assertThat(seen.get().getRequest().getHeaders().getFirst(InternalHeaders.USER_ID)).isNull();
    }

    @Test
    @DisplayName("阶段 0：未带 Token 时原样转发，由下游自行判定")
    void forwardsWhenNotEnforcing() {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange input = request("/api/predict/tasks", Map.of(), null);

        filter().filter(input, capturing(seen)).block();

        assertThat(seen.get()).isNotNull();
        verify(errorWriter, org.mockito.Mockito.never()).write(any(), any());
    }

    @Test
    @DisplayName("enforce=true 且未带 Token：网关直接返回 UNAUTHORIZED")
    void rejectsWhenEnforcingAndNoToken() {
        authProperties.setEnforce(true);

        filter().filter(request("/api/predict/tasks", Map.of(), null), exchange -> Mono.empty()).block();

        assertThat(capturedErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("enforce=true 且 Token 已注销：返回 TOKEN_BLACKLISTED")
    void rejectsBlacklistedToken() {
        authProperties.setEnforce(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true));

        filter().filter(request("/api/predict/tasks", Map.of(), validToken()), exchange -> Mono.empty()).block();

        assertThat(capturedErrorCode()).isEqualTo(ErrorCode.TOKEN_BLACKLISTED);
    }

    @Test
    @DisplayName("enforce=true 且 Redis 不可用：返回 DEPENDENCY_UNAVAILABLE，不静默放行（红线 R7）")
    void rejectsWhenBlacklistUnavailable() {
        authProperties.setEnforce(true);
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        filter().filter(request("/api/predict/tasks", Map.of(), validToken()), exchange -> Mono.empty()).block();

        assertThat(capturedErrorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("forward-authorization=false 时剔除 Authorization，但仍能验签并注入身份")
    void stripsAuthorizationButStillVerifies() {
        authProperties.setForwardAuthorization(false);
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange input = request("/api/predict/tasks", Map.of(), validToken());

        filter().filter(input, capturing(seen)).block();

        HttpHeaders headers = seen.get().getRequest().getHeaders();
        assertThat(headers.getFirst(InternalHeaders.AUTHORIZATION)).isNull();
        assertThat(headers.getFirst(InternalHeaders.USER_ID)).isEqualTo("1024");
    }

    private ErrorCode capturedErrorCode() {
        ArgumentCaptor<ErrorCode> captor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(errorWriter).write(any(), captor.capture());
        return captor.getValue();
    }
}
