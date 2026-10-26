package com.synpharm.platform.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 算法引擎（FastAPI）WebClient。
 *
 * <p>阶段 4 把 `/api/system/algorithm-health`（能力探测）从单体迁到 platform 后，
 * 探测所需的最小客户端配置随之落在这里 —— 只保留 base-url 与 api-key；
 * 单条/批量预测用的超时（`fastapi.timeout-single` / `timeout-batch`）属于 predict，不在此处。
 *
 * @author SynPharm Team
 * @version 1.0.0
 */
@Configuration
public class WebClientConfig {

    @Value("${fastapi.base-url}")
    private String baseUrl;

    @Value("${fastapi.api-key:}")
    private String apiKey;

    @Bean
    public WebClient fastApiWebClient() {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(apiKey)) {
            builder.defaultHeader("X-API-Key", apiKey);
        }
        return builder.build();
    }
}