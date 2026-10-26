package com.synpharm.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 网关统一 CORS。
 *
 * <p><b>由 {@code synpharm.gateway.cors.enabled} 控制</b>，默认开启（2026-10-19 起）：
 * 单体退役后下游 5 个服务已无任何 CORS 配置，不会有重复响应头。
 * 关闭时前端 dev（5174）直连网关（8080）的跨域预检会得到 403 且无
 * {@code Access-Control-Allow-Origin}，浏览器会直接拦掉登录请求。
 *
 * @author SynPharm Team
 */
@Configuration
@ConditionalOnProperty(prefix = "synpharm.gateway.cors", name = "enabled", havingValue = "true")
public class GatewayCorsConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter(GatewayCorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        properties.getAllowedOriginPatterns().forEach(config::addAllowedOriginPattern);
        properties.getAllowedMethods().forEach(config::addAllowedMethod);
        properties.getAllowedHeaders().forEach(config::addAllowedHeader);
        config.setAllowCredentials(properties.isAllowCredentials());
        config.setMaxAge(properties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
