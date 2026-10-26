package com.synpharm.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务间内部调用配置。
 *
 * <p>本类位于公共库，不在业务服务的 {@code @ConfigurationPropertiesScan} 默认扫描范围
 * （注册类所在包）内，因此显式标注 {@link Component} 由组件扫描注册为 bean；
 * Spring Boot 仍会对该 bean 应用 {@code @ConfigurationProperties} 绑定。
 *
 * @author SynPharm Team
 */
@Data
@Component
@ConfigurationProperties(prefix = "synpharm.internal")
public class SynpharmInternalProperties {

    /**
     * 网关与本服务之间共享的口令，随 {@code X-Internal-Token} 头传递。
     *
     * <p>未配置时本服务<b>拒绝信任任何身份头</b>（fail-closed）—— 宁可所有鉴权接口返回
     * 401，也不能让内网里任何一个进程靠伪造 {@code X-User-Id} 冒充用户。
     */
    private String token;
}
