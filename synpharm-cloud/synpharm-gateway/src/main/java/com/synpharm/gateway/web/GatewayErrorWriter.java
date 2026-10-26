package com.synpharm.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.api.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 把错误码写成统一响应信封（网关自身产生的响应，不走下游）。
 *
 * <p>HTTP 状态行取 {@link ErrorCode#httpStatus()}，前端 axios 会走错误分支并读取
 * {@code message} 展示（见 {@code synpharm-frontend/src/utils/request.ts}）。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(errorCode.getHttpStatus()));
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        byte[] body = serialize(Result.failure(errorCode));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serialize(Result<Void> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            log.error("错误响应序列化失败", e);
            return "{\"code\":50000,\"errorCode\":\"INTERNAL_ERROR\",\"message\":\"服务内部错误，请稍后重试\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
