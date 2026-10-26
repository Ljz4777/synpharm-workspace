package com.synpharm.gateway.filter;

import com.synpharm.common.constant.InternalHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 追踪 ID 注入。
 *
 * <p>保证每个请求都带 {@code X-Request-Id} 与 {@code X-Trace-Id}：
 * 客户端传了就用客户端的，没传则由网关生成；两者都会被回写到响应头，便于用户报障时提供。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** 必须早于鉴权过滤器，否则拒绝响应里没有追踪 ID */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = firstNonBlank(
                request.getHeaders().getFirst(InternalHeaders.REQUEST_ID),
                request.getHeaders().getFirst(InternalHeaders.TRACE_ID));
        String traceId = StringUtils.hasText(requestId) ? requestId : newTraceId();
        String finalRequestId = StringUtils.hasText(requestId) ? requestId : traceId;

        ServerHttpRequest mutated = request.mutate()
                .header(InternalHeaders.TRACE_ID, traceId)
                .header(InternalHeaders.REQUEST_ID, finalRequestId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        mutatedExchange.getResponse().getHeaders().set(InternalHeaders.TRACE_ID, traceId);

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
