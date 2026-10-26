package com.synpharm.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发器（HS256）。
 *
 * <p><b>本服务只签发，不校验</b>：验签在网关（{@code synpharm-gateway} 的
 * {@code JwtVerifier}），服务间身份靠 {@code X-User-Id} 等内部头传递。
 * 因此这里没有 {@code validate} / {@code parse} —— 少一个能校验的入口，
 * 就少一条绕过网关的路径。
 *
 * <p>与网关必须使用同一密钥（{@code JWT_SECRET}），否则签发的 Token 过不了网关。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class JwtIssuer {

    /** HS256 要求密钥至少 32 字节（256 位） */
    private static final int MIN_KEY_LENGTH = 32;

    /** JWT 签名密钥；留空或不足 32 字节则启动失败（fail-fast） */
    @Value("${jwt.secret}")
    private String secret;

    /** Token 有效期（毫秒）；同时用作登出黑名单的 TTL 上界 */
    @Getter
    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足，HS256 要求至少 " + MIN_KEY_LENGTH + " 字节（当前 " + keyBytes.length
                            + " 字节）。请通过环境变量 JWT_SECRET 提供 ≥32 字节强随机密钥。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 签发密钥初始化完成");
    }

    /**
     * 签发 Token。
     *
     * <p>claim 约定（网关 {@code JwtVerifier} 与前端都依赖）：
     * {@code sub}=userId、{@code email}、{@code role}、{@code jti}（登出黑名单的键）。
     */
    public String generateToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(signingKey)
                .compact();
    }
}
