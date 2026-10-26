package com.synpharm.gateway.security;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 校验器（HS256）。
 *
 * <p>与单体 {@code com.synpharm.utils.JwtUtils} 使用同一密钥与同一套 claim，
 * 保证网关边缘校验与下游服务校验结论一致。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    /** HS256 要求密钥至少 32 字节（256 位） */
    private static final int MIN_KEY_LENGTH = 32;

    private final JwtProperties properties;

    private SecretKey signingKey;

    /**
     * 初始化签名密钥。
     *
     * <p>密钥缺失或过短时<b>启动即失败</b>（fail-fast）—— 绝不能带着可猜密钥上线。
     */
    @PostConstruct
    public void init() {
        String secret = properties.getSecret();
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足，HS256 要求至少 " + MIN_KEY_LENGTH + " 字节（当前 " + keyBytes.length
                            + " 字节）。请通过环境变量 JWT_SECRET 提供 ≥32 字节强随机密钥。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("网关 JWT 签名密钥初始化完成");
    }

    /**
     * 验签并解析 Token。
     *
     * @param token 不含 {@code Bearer } 前缀的原始 Token
     * @return 已验签的 Token 内容
     * @throws TokenValidationException 过期 / 格式错误 / 签名不符
     */
    public ParsedToken verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new ParsedToken(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    claims.get("role", String.class),
                    claims.getId());
        } catch (ExpiredJwtException e) {
            log.debug("Token 已过期");
            throw new TokenValidationException(ErrorCode.TOKEN_EXPIRED);
        } catch (SignatureException e) {
            log.warn("Token 签名校验失败");
            throw new TokenValidationException(ErrorCode.SIGNATURE_INVALID);
        } catch (UnsupportedJwtException | MalformedJwtException | IllegalArgumentException e) {
            log.warn("Token 格式错误: {}", e.getMessage());
            throw new TokenValidationException(ErrorCode.TOKEN_INVALID);
        } catch (JwtException e) {
            log.warn("Token 校验失败: {}", e.getMessage());
            throw new TokenValidationException(ErrorCode.TOKEN_INVALID);
        }
    }
}
