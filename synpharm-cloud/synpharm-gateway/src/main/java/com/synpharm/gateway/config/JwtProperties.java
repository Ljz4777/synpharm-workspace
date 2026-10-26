package com.synpharm.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。
 *
 * <p>属性名沿用拆分前的 {@code jwt.secret} / {@code jwt.expiration}，
 * 环境变量同为 {@code JWT_SECRET} / {@code JWT_EXPIRATION} —— 网关与各服务必须用<b>同一密钥</b>
 * 才能互相读懂 Token。
 *
 * @author SynPharm Team
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥；留空或不足 32 字节时启动失败（fail-fast，与单体一致） */
    private String secret;

    /** 过期时间（毫秒）；仅用于文档与校验，签发方是 auth 服务 */
    private long expiration = 86_400_000L;
}
