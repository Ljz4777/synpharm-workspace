package com.synpharm.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关级错误的信封化测试。
 *
 * <p>锁死的行为：**未匹配路由必须是带信封的 404**，而不是 Gateway 默认的空 body / HTML 错误页。
 * 这条规则在单体退役（2026-10-16）后才变得重要 —— 原先未匹配的 `/api/**` 会被兜底路由转发到单体，
 * 现在会直接落在网关自身的错误路径上。
 *
 * <p>用真实的 {@link GatewayErrorWriter}（不是 mock），因为要断言的正是它写出的 JSON 形状。
 * 不引 {@code reactor-test}：{@code Mono<Void>} 直接 {@code block()} 更省一个依赖。
 *
 * @author SynPharm Team
 */
class GatewayErrorWebExceptionHandlerTest {

    private GatewayErrorWebExceptionHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new GatewayErrorWebExceptionHandler(new GatewayErrorWriter(objectMapper));
    }

    @Test
    @DisplayName("未匹配路由（404）：返回统一信封，errorCode=RESOURCE_NOT_FOUND，不是空 body")
    void writesEnvelopeForNotFound() throws Exception {
        MockServerWebExchange exchange = exchange("/api/design/does-not-exist");

        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = readBody(exchange);
        assertThat(body.get("code").asInt()).isEqualTo(40400);
        assertThat(body.get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    @Test
    @DisplayName("下游不可达（503）：信封为 DEPENDENCY_UNAVAILABLE，便于前端提示稍后重试")
    void writesEnvelopeForServiceUnavailable() throws Exception {
        MockServerWebExchange exchange = exchange("/api/predict/history");

        handler.handle(exchange, new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readBody(exchange).get("errorCode").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("非状态型异常（如连接被拒）按下游不可用处理，不落成裸 500")
    void writesEnvelopeForConnectFailure() throws Exception {
        MockServerWebExchange exchange = exchange("/api/auth/login");

        handler.handle(exchange, new RuntimeException("Connection refused")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readBody(exchange).get("errorCode").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("响应已提交时不再插手（异常原样抛回，避免写坏半截响应）")
    void passesThroughWhenResponseCommitted() {
        MockServerWebExchange exchange = exchange("/api/design/projects");
        exchange.getResponse().setComplete().block();

        assertThatThrownBy(() -> handler.handle(exchange, new RuntimeException("boom")).block())
                .hasMessage("boom");
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private JsonNode readBody(MockServerWebExchange exchange) throws Exception {
        String json = exchange.getResponse().getBodyAsString().defaultIfEmpty("").block();
        assertThat(json).isNotBlank();
        return objectMapper.readTree(json);
    }
}
