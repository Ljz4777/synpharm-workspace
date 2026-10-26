package com.synpharm.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 CORS 配置。
 *
 * <p><b>默认开启</b>：阶段 0 时期 CORS 响应头由单体 {@code CorsConfig} 产生，网关再产生一份
 * 会出现重复的 {@code Access-Control-Allow-Origin}，故当时默认关闭。
 * <p><b>2026-10-19 现状</b>：单体已退役、下游 5 个服务全仓 0 处 CORS 配置，前置条件已满足，
 * 默认值改为开启。关闭时前端 dev（5174）直连网关（8080）的跨域预检会得到 403
 * 且无 {@code Access-Control-Allow-Origin}，浏览器直接拦掉登录请求。
 *
 * @author SynPharm Team
 */
@Data
@ConfigurationProperties(prefix = "synpharm.gateway.cors")
public class GatewayCorsProperties {

    /** 是否由网关统一处理 CORS */
    private boolean enabled = true;

    /** 允许的来源；使用 {@code addAllowedOriginPattern} 语义，支持通配 */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));

    /** 允许的方法 */
    private List<String> allowedMethods = new ArrayList<>(List.of("*"));

    /** 允许的请求头 */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

    /** 是否允许携带凭证 */
    private boolean allowCredentials = true;

    /** 预检结果缓存秒数 */
    private long maxAge = 3600L;
}
