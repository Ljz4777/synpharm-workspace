package com.synpharm.common.security;

import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部身份头入口过滤器。
 *
 * <p>判断请求是否来自网关：只有 {@code X-Internal-Token} 与服务端配置一致时，
 * 才把 {@code X-User-Id} / {@code X-User-Role} / {@code X-User-Email} / {@code X-Token-Id}
 * 认定为可信并写入 {@link CurrentUser}；否则一律视为匿名（身份头被忽略）。
 *
 * <p>这是服务边界的守门人：业务服务<b>不</b>校验 JWT，若不做这一步，
 * 内网里任何进程都能靠伪造一个头冒充任意用户。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class InternalRequestFilter extends OncePerRequestFilter {

    private final SynpharmInternalProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String expected = properties.getToken();
            String provided = request.getHeader(InternalHeaders.INTERNAL_TOKEN);
            boolean trusted = StringUtils.hasText(expected) && expected.equals(provided);

            if (trusted) {
                CurrentUser.set(new CurrentUser.Identity(
                        parseUserId(request.getHeader(InternalHeaders.USER_ID)),
                        request.getHeader(InternalHeaders.USER_ROLE),
                        request.getHeader(InternalHeaders.USER_EMAIL),
                        request.getHeader(InternalHeaders.TOKEN_ID)));
            } else if (isInternalPath(request)) {
                // /internal/** 是**服务间专用**路径（网关不路由它）。这类路径上「忽略身份头后放行」
                // 是错的：它们多数不读身份，放行等于把内部数据面暴露给同网络内的任何调用方。
                // 因此这里 fail-closed —— 口令不对就直接拒绝，而不是降级成匿名。
                log.warn("拒绝未携带有效内部令牌的 /internal/** 调用: {} {}",
                        request.getMethod(), request.getRequestURI());
                reject(response);
                return;
            } else if (StringUtils.hasText(provided)) {
                log.warn("内部令牌不匹配，已忽略身份头: {} {}", request.getMethod(), request.getRequestURI());
            } else if (StringUtils.hasText(request.getHeader(InternalHeaders.USER_ID))) {
                log.warn("收到未经网关的身份头，已忽略: {} {}", request.getMethod(), request.getRequestURI());
            }
            filterChain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }

    /** {@code /internal/**}：网关不暴露、仅服务间调用的前缀 */
    private static boolean isInternalPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/internal/");
    }

    /** 直接写回统一信封的 401，不再进入后续过滤器与控制器 */
    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + errorCode.getCode()
                + ",\"errorCode\":\"" + errorCode.getErrorCode()
                + "\",\"message\":\"缺少或错误的内部令牌，/internal/** 仅限服务间调用\"}");
    }

    private static Long parseUserId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("非法的 X-User-Id: {}", raw);
            return null;
        }
    }
}
