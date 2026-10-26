package com.synpharm.auth.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器。
 *
 * <p>本服务不引入 {@code spring-boot-starter-security}（鉴权在网关），
 * 但 BCrypt 仍由 {@code spring-security-crypto} 提供，需要自己声明这个 Bean。
 *
 * @author SynPharm Team
 */
@Configuration
public class PasswordEncoderConfig {

    /** 强度 10：与拆分前单体保持一致，避免同一批历史口令被重新编码后不兼容 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
