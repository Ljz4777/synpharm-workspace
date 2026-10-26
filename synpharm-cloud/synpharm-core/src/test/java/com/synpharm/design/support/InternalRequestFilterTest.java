package com.synpharm.design.support;

import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.InternalRequestFilter;
import com.synpharm.common.security.SynpharmInternalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务边界守门人测试。
 *
 * <p>本服务不校验 JWT，身份完全依赖 {@code X-Internal-Token} 是否与网关一致；
 * 这些用例锁死「令牌不一致时身份头必须被忽略」这条 fail-closed 语义。
 *
 * @author SynPharm Team
 */
class InternalRequestFilterTest {

    private static final String TOKEN = "shared-secret";

    private SynpharmInternalProperties properties;
    private InternalRequestFilter filter;

    @BeforeEach
    void setUp() {
        properties = new SynpharmInternalProperties();
        filter = new InternalRequestFilter(properties);
    }

    /** 跑过滤链，返回链上观察到的身份（null 表示被判定为匿名） */
    private CurrentUser.Identity runWith(MockHttpServletRequest request) throws Exception {
        AtomicReference<CurrentUser.Identity> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set(CurrentUser.get()));
        return captured.get();
    }

    private static MockHttpServletRequest request(String... headerPairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/design/projects");
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.addHeader(headerPairs[i], headerPairs[i + 1]);
        }
        return request;
    }

    @Test
    @DisplayName("内部令牌一致：身份头被信任并写入 CurrentUser")
    void trustsIdentityHeadersWhenInternalTokenMatches() throws Exception {
        properties.setToken(TOKEN);
        MockHttpServletRequest request = request(
                InternalHeaders.INTERNAL_TOKEN, TOKEN,
                InternalHeaders.USER_ID, "42",
                InternalHeaders.USER_ROLE, "USER",
                InternalHeaders.USER_EMAIL, "chemist@synpharm.local",
                InternalHeaders.TOKEN_ID, "jti-1");

        CurrentUser.Identity identity = runWith(request);

        assertThat(identity).isNotNull();
        assertThat(identity.userId()).isEqualTo(42L);
        assertThat(identity.role()).isEqualTo("USER");
        assertThat(identity.email()).isEqualTo("chemist@synpharm.local");
        assertThat(identity.tokenId()).isEqualTo("jti-1");
    }

    @Test
    @DisplayName("请求结束后身份必须被清理，避免线程复用串号")
    void clearsIdentityAfterRequest() throws Exception {
        properties.setToken(TOKEN);
        runWith(request(InternalHeaders.INTERNAL_TOKEN, TOKEN, InternalHeaders.USER_ID, "42"));

        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    @DisplayName("无内部令牌：直接伪造 X-User-Id 无效")
    void ignoresIdentityHeadersWithoutInternalToken() throws Exception {
        properties.setToken(TOKEN);

        CurrentUser.Identity identity = runWith(request(InternalHeaders.USER_ID, "42"));

        assertThat(identity).isNull();
    }

    @Test
    @DisplayName("内部令牌不匹配：身份头无效")
    void ignoresIdentityHeadersWhenInternalTokenDiffers() throws Exception {
        properties.setToken(TOKEN);

        CurrentUser.Identity identity = runWith(request(
                InternalHeaders.INTERNAL_TOKEN, "wrong-secret",
                InternalHeaders.USER_ID, "42"));

        assertThat(identity).isNull();
    }

    @Test
    @DisplayName("未配置内部令牌：一律不信任（fail-closed）")
    void neverTrustsWhenTokenNotConfigured() throws Exception {
        properties.setToken(null);

        assertThat(runWith(request(InternalHeaders.INTERNAL_TOKEN, "", InternalHeaders.USER_ID, "42"))).isNull();
        assertThat(runWith(request(InternalHeaders.INTERNAL_TOKEN, TOKEN, InternalHeaders.USER_ID, "42"))).isNull();
    }

    @Test
    @DisplayName("只有令牌可信但无 X-User-Id：视为匿名，requireUserId 抛 401")
    void treatsMissingUserIdAsAnonymous() throws Exception {
        properties.setToken(TOKEN);

        CurrentUser.Identity identity = runWith(request(InternalHeaders.INTERNAL_TOKEN, TOKEN));

        assertThat(identity).isNotNull();
        assertThat(identity.userId()).isNull();
        assertThatThrownBy(CurrentUser::requireUserId)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("X-User-Id 非数字：视为匿名，不抛 500")
    void treatsNonNumericUserIdAsAnonymous() throws Exception {
        properties.setToken(TOKEN);
        CurrentUser.Identity identity = runWith(request(
                InternalHeaders.INTERNAL_TOKEN, TOKEN,
                InternalHeaders.USER_ID, "not-a-number"));

        assertThat(identity).isNotNull();
        assertThat(identity.userId()).isNull();
    }

    @Test
    @DisplayName("/internal/** 且令牌不匹配：直接 401，且不再进入后续过滤器")
    void rejectsUntrustedCallOnInternalPath() throws Exception {
        properties.setToken(TOKEN);
        MockHttpServletRequest request = request();
        request.setRequestURI("/internal/tools/capabilities");
        request.addHeader(InternalHeaders.INTERNAL_TOKEN, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("/internal/** 且未配置令牌：同样拒绝（fail-closed，不放行成匿名）")
    void rejectsInternalPathWhenTokenNotConfigured() throws Exception {
        properties.setToken(null);
        MockHttpServletRequest request = request();
        request.setRequestURI("/internal/tools/capabilities");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("/internal/** 且令牌正确：正常放行")
    void allowsTrustedCallOnInternalPath() throws Exception {
        properties.setToken(TOKEN);
        MockHttpServletRequest request = request(InternalHeaders.INTERNAL_TOKEN, TOKEN);
        request.setRequestURI("/internal/tools/capabilities");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("非 /internal 路径不受影响：令牌不匹配仍只忽略身份头（阶段 1 起的既有语义）")
    void stillForwardsNonInternalPathWithWrongToken() throws Exception {
        properties.setToken(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request(InternalHeaders.INTERNAL_TOKEN, "wrong-secret"), response,
                (req, res) -> reached.set(true));

        assertThat(reached.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
