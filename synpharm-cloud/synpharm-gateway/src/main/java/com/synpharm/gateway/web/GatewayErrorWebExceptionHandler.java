package com.synpharm.gateway.web;

import com.synpharm.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 把网关自身产生的错误写成统一响应信封（规范 §1）。
 *
 * <p><b>为什么需要它。</b>单体退役（2026-10-16）后，原先那条 `Path=/api/**` 的兜底路由已删除，
 * 未被任何专属路由匹配的路径会走 Spring Cloud Gateway 的默认错误路径 —— 返回一个
 * **不带信封、body 为空或为 HTML 错误页**的 404。前端 axios 拦截器按
 * `errorCode`/`message` 取文案（见 `synpharm-frontend/src/utils/request.ts`），
 * 拿到空 body 只会显示"未知错误"，排障时也看不出是"路径根本不存在"。
 *
 * <p>{@link ResponseStatusException} 是网关对「无匹配路由」「下游连接失败」等情况的统一异常类型：
 * <ul>
 *   <li>{@code 404} → {@link ErrorCode#RESOURCE_NOT_FOUND}（路径不存在，注意这**不是**业务 404）；</li>
 *   <li>{@code 503} → {@link ErrorCode#DEPENDENCY_UNAVAILABLE}（下游不可达 / 熔断）；</li>
 *   <li>其余 → 原样透传状态码，信封用 {@code INTERNAL_ERROR} 语义兜底。</li>
 * </ul>
 *
 * <p><b>顺序</b>：必须排在 Spring Boot 默认的 {@code DefaultErrorWebExceptionHandler}（order = -1）
 * **之前**，否则永远轮不到本处理器。这里取 {@code -2}。
 *
 * <p>已有的 {@code GatewayErrorWriter} 负责写；本类只决定"哪种异常映射到哪个错误码"。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final GatewayErrorWriter errorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 响应已经开始写了就不能再插手（例如下游已返回部分响应后中断）
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        ErrorCode errorCode = resolve(ex);
        if (errorCode == ErrorCode.RESOURCE_NOT_FOUND) {
            // 路径不存在属正常的客户端错误，用 debug 记录，避免刷满 ERROR 日志
            log.debug("网关未匹配到路由: {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getRawPath());
        } else {
            log.error("网关处理请求失败: {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getRawPath(), ex);
        }
        return errorWriter.write(exchange, errorCode);
    }

    private static ErrorCode resolve(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) {
                return ErrorCode.RESOURCE_NOT_FOUND;
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()
                    || status == HttpStatus.GATEWAY_TIMEOUT.value()
                    || status == HttpStatus.BAD_GATEWAY.value()) {
                return ErrorCode.DEPENDENCY_UNAVAILABLE;
            }
        }
        // 非状态型异常（含下游连接被拒）都按"下游不可用"处理：
        // 网关自己很少"内部出错"，绝大多数情况是拿不到某个后端服务
        return ErrorCode.DEPENDENCY_UNAVAILABLE;
    }
}
