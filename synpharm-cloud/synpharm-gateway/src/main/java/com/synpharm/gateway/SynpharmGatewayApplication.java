package com.synpharm.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * SynPharm 边缘网关。
 *
 * <p>唯一对外入口（宿主 8080，与现有 {@code BACKEND_PORT} 一致，前端与 nginx 零改动）。
 * 职责：路由、JWT 校验与身份头注入、CORS（阶段 5 收敛）、全链路追踪 ID。
 *
 * @author SynPharm Team
 */
@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan
public class SynpharmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynpharmGatewayApplication.class, args);
    }
}
