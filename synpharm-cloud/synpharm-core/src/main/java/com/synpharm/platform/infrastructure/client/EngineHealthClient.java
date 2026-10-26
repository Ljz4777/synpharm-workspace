package com.synpharm.platform.infrastructure.client;

import com.synpharm.platform.api.dto.AlgorithmHealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 算法引擎（FastAPI）健康探测客户端。
 *
 * <p>阶段 4 把 `/api/system/algorithm-health` 从单体迁入 platform 时，从单体的
 * {@code FastApiClient} 中**只取出健康探测这一项能力**（预测/批量/白名单那几项留在
 * synpharm-predict，platform 不需要），逻辑逐行保持一致：响应超时 + 有限重试（默认 2 次）
 * + 熔断（连续失败达阈值后短期内直接返回 DOWN，避免每次请求都白等一个超时）。
 *
 * @author SynPharm Team
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EngineHealthClient {

    private final WebClient fastApiWebClient;

    /** 熔断器（算法引擎健康检查用） */
    private final SimpleCircuitBreaker circuitBreaker;

    @Value("${fastapi.health-timeout:5000}")
    private long healthTimeoutMs;

    @Value("${fastapi.health-retries:2}")
    private int healthRetries;

    /**
     * 探测 FastAPI {@code GET /health/}（无鉴权）。
     *
     * @return UP：可达；DOWN：不可达或熔断开启
     */
    public AlgorithmHealthResponse health() {
        long start = System.currentTimeMillis();

        if (!circuitBreaker.allow()) {
            log.warn("熔断器开启，跳过算法引擎探测");
            return AlgorithmHealthResponse.builder()
                    .status("DOWN")
                    .message("熔断器开启，暂不探测算法引擎")
                    .checkedAt(LocalDateTime.now())
                    .build();
        }

        Exception lastError = null;
        int attempts = 0;
        while (attempts <= healthRetries) {
            attempts++;
            try {
                Map<String, Object> body = fastApiWebClient.get()
                        .uri("/health/")
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
                        .timeout(Duration.ofMillis(healthTimeoutMs))
                        .block();

                circuitBreaker.recordSuccess();
                long latency = System.currentTimeMillis() - start;
                log.info("算法引擎健康检查成功: latencyMs={}", latency);
                return AlgorithmHealthResponse.builder()
                        .status("UP")
                        .fastapiStatus(body == null ? null : String.valueOf(body.get("status")))
                        .latencyMs(latency)
                        .message("算法引擎健康")
                        .checkedAt(LocalDateTime.now())
                        .build();
            } catch (Exception e) {
                lastError = e;
                log.warn("算法引擎健康检查失败(第{}次): {}", attempts, e.getMessage());
                if (attempts <= healthRetries) {
                    try {
                        Thread.sleep(100L * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        circuitBreaker.recordFailure();
        return AlgorithmHealthResponse.builder()
                .status("DOWN")
                .latencyMs(System.currentTimeMillis() - start)
                .message("算法引擎不可用: " + (lastError == null ? "未知错误" : lastError.getMessage()))
                .checkedAt(LocalDateTime.now())
                .build();
    }
}