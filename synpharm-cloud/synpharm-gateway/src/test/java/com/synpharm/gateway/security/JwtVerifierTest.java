package com.synpharm.gateway.security;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.gateway.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtVerifier} 单元测试。
 *
 * <p>Token 格式必须与各服务（auth 签发、网关验签）完全一致，
 * 否则会出现「网关验签通过但下游 401」或反过来的错位。
 */
class JwtVerifierTest {

    /** 与单体 application.yml 的 jwt.secret 语义一致：≥32 字节的 UTF-8 字符串 */
    private static final String SECRET = "synpharm-unit-test-secret-0123456789abcdef";

    private static SecretKey keyOf(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtVerifier verifier(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        JwtVerifier verifier = new JwtVerifier(properties);
        verifier.init();
        return verifier;
    }

    private static String token(String secret, Instant expiry, String jti) {
        return Jwts.builder()
                .id(jti)
                .subject("1024")
                .claim("email", "chemist@synpharm.local")
                .claim("role", "USER")
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiry))
                .signWith(keyOf(secret))
                .compact();
    }

    @Test
    @DisplayName("有效 Token 解析出与单体一致的 claim")
    void parsesValidToken() {
        String jti = UUID.randomUUID().toString();
        String raw = token(SECRET, Instant.now().plusSeconds(3600), jti);

        ParsedToken parsed = verifier(SECRET).verify(raw);

        assertThat(parsed.userId()).isEqualTo(1024L);
        assertThat(parsed.email()).isEqualTo("chemist@synpharm.local");
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.tokenId()).isEqualTo(jti);
    }

    @Test
    @DisplayName("过期 Token 抛 TOKEN_EXPIRED")
    void rejectsExpiredToken() {
        String raw = token(SECRET, Instant.now().minusSeconds(10), UUID.randomUUID().toString());

        assertThatThrownBy(() -> verifier(SECRET).verify(raw))
                .isInstanceOf(TokenValidationException.class)
                .extracting(e -> ((TokenValidationException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("换密钥签发的 Token 抛 SIGNATURE_INVALID")
    void rejectsForeignSignature() {
        String raw = token("another-secret-0123456789abcdefghijklmn", Instant.now().plusSeconds(3600),
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> verifier(SECRET).verify(raw))
                .isInstanceOf(TokenValidationException.class)
                .extracting(e -> ((TokenValidationException) e).getErrorCode())
                .isEqualTo(ErrorCode.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("非法字符串抛 TOKEN_INVALID")
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> verifier(SECRET).verify("not-a-jwt"))
                .isInstanceOf(TokenValidationException.class)
                .extracting(e -> ((TokenValidationException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("密钥缺失或过短时启动即失败（fail-fast）")
    void failsFastOnWeakSecret() {
        JwtProperties empty = new JwtProperties();
        empty.setSecret("");
        assertThatThrownBy(() -> new JwtVerifier(empty).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥长度不足");

        JwtProperties tooShort = new JwtProperties();
        tooShort.setSecret("short-secret");
        assertThatThrownBy(() -> new JwtVerifier(tooShort).init())
                .isInstanceOf(IllegalStateException.class);

        JwtProperties nullSecret = new JwtProperties();
        nullSecret.setSecret(null);
        assertThatThrownBy(() -> new JwtVerifier(nullSecret).init())
                .isInstanceOf(IllegalStateException.class);
    }
}
