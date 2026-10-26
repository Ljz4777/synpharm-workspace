package com.synpharm.auth.application.strategy;

import com.synpharm.auth.api.dto.LoginRequest;
import com.synpharm.auth.api.dto.LoginResponse;
import com.synpharm.auth.api.dto.UserResponse;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.auth.domain.model.SysUser;
import com.synpharm.auth.infrastructure.persistence.SysUserMapper;
import com.synpharm.auth.application.LoginStrategy;
import com.synpharm.auth.support.IpUtils;
import com.synpharm.auth.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 邮箱密码登录策略
 *
 * <p>特点：
 * <ul>
 *   <li>登录类型：password</li>
 *   <li>使用 BCrypt 校验密码（与注册/修改密码使用同一 PasswordEncoder）</li>
 *   <li>仅用于已注册（设置过密码）的用户；未注册用户提示先注册</li>
 * </ul>
 *
 * @author SynPharm Team
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordLoginStrategy implements LoginStrategy {

    private final SysUserMapper userMapper;
    private final JwtIssuer jwtIssuer;
    private final PasswordEncoder passwordEncoder;

    @Override
    public String getLoginType() {
        return "password";
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String email = request.getEmail();
        String password = request.getPassword();

        if (email == null || email.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "邮箱不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "密码不能为空");
        }

        // ========== 第一步：查询用户 ==========
        SysUser user = userMapper.selectByEmail(email);
        if (user == null) {
            log.warn("密码登录-用户不存在, email: {}", email);
            throw new BizException(ErrorCode.USER_NOT_FOUND, "该邮箱未注册，请先注册");
        }

        // ========== 第二步：检查用户状态 ==========
        if (user.getStatus() != 1) {
            log.warn("密码登录-用户已被禁用, userId: {}", user.getId());
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        // ========== 第三步：校验密码 ==========
        // 验证码登录自动注册的用户 password 可能为 null，此时不能用密码登录
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            log.warn("密码登录-用户未设置密码, userId: {}", user.getId());
            throw new BizException(ErrorCode.PASSWORD_ERROR, "该账号未设置密码，请使用验证码登录或重置密码");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("密码登录-密码错误, userId: {}", user.getId());
            throw new BizException(ErrorCode.PASSWORD_ERROR, "密码错误");
        }

        // ========== 第四步：生成JWT Token ==========
        String token = jwtIssuer.generateToken(user.getId(), user.getEmail(), user.getRole());
        String ip = IpUtils.getClientIp(httpRequest);
        userMapper.updateLoginInfo(user.getId(), LocalDateTime.now(), ip, LocalDateTime.now());

        log.info("密码登录成功, userId: {}, email: {}", user.getId(), email);
        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtIssuer.getExpiration() / 1000)
                .isNewUser(false)
                .user(UserResponse.fromEntity(user))
                .build();
    }
}
