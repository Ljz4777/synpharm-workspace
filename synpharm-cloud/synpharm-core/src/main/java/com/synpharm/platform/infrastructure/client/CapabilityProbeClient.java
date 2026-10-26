package com.synpharm.platform.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 算法引擎能力矩阵客户端（`GET /v1/evaluate/capabilities`）。
 *
 * <p>这是 {@code design_tool.available} 的**唯一数据源**（红线 R1）。调用失败时**抛异常** ——
 * 由 {@link com.synpharm.platform.support.ToolRegistryService} 把它翻译成
 * 「引擎不可达：<原因>」并回写 {@code probe_msg}，绝不允许静默当成「可用」。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityProbeClient {

    private final WebClient fastApiWebClient;

    @Value("${fastapi.probe-timeout:5000}")
    private long probeTimeoutMs;

    /**
     * 拉取能力矩阵。
     *
     * @throws RuntimeException 引擎不可达、超时或返回体无法解析
     */
    public EngineCapabilities fetch() {
        EngineCapabilities capabilities = fastApiWebClient.get()
                .uri("/v1/evaluate/capabilities")
                .retrieve()
                .bodyToMono(EngineCapabilities.class)
                .timeout(Duration.ofMillis(probeTimeoutMs))
                .block();
        if (capabilities == null) {
            throw new IllegalStateException("能力矩阵响应为空");
        }
        return capabilities;
    }
}