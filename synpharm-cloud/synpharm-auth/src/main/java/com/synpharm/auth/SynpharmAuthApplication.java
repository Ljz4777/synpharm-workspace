package com.synpharm.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * SynPharm 身份与访问服务。
 *
 * <p>拥有 {@code synpharm_auth} schema（{@code sys_user}、{@code sys_login_log}），
 * 是这两张表<b>唯一</b>的写入方（拆分铁律 R2）。
 *
 * <p>鉴权边界：本服务<b>不</b>校验 JWT —— 网关已验签并注入
 * {@code X-User-Id} / {@code X-User-Role}。防伪造由
 * {@link com.synpharm.common.security.InternalRequestFilter} + 内部令牌负责。
 *
 * @author SynPharm Team
 */
// 扫描 com.synpharm：公共库（synpharm-common）里的过滤器 / 全局异常处理器 / MybatisPlusConfig 才会装配
@SpringBootApplication(scanBasePackages = "com.synpharm")
@EnableDiscoveryClient
@ConfigurationPropertiesScan
public class SynpharmAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynpharmAuthApplication.class, args);
    }
}
