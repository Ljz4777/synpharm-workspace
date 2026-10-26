package com.synpharm.auth.application.strategy;

import com.synpharm.auth.api.dto.LoginRequest;
import com.synpharm.auth.api.dto.LoginResponse;
import com.synpharm.auth.api.dto.UserResponse;
import com.synpharm.auth.domain.model.SysUser;
import com.synpharm.auth.infrastructure.persistence.SysUserMapper;
import com.synpharm.auth.application.LoginStrategy;
import com.synpharm.auth.support.IpUtils;
import com.synpharm.auth.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuestLoginStrategy implements LoginStrategy {

    private final SysUserMapper userMapper;
    private final JwtIssuer jwtIssuer;

    @Override
    public String getLoginType() {
        return "guest";
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = IpUtils.getClientIp(httpRequest);
        String guestEmail = "guest_" + UUID.randomUUID().toString().substring(0, 8) + "@guest.local";
        String nickname = "游客_" + System.currentTimeMillis() % 10000;

        SysUser user = new SysUser();
        user.setEmail(guestEmail);
        user.setNickname(nickname);
        user.setRole("guest");
        user.setStatus(1);
        user.setEmailVerified(0);
        user.setRegisterType("guest");
        user.setLoginCount(0);
        user.setPassword(null);
        userMapper.insert(user);

        log.info("游客登录创建成功, userId: {}, email: {}", user.getId(), guestEmail);

        String token = jwtIssuer.generateToken(user.getId(), user.getEmail(), user.getRole());
        updateLoginInfo(user.getId(), ip);

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtIssuer.getExpiration() / 1000)
                .isNewUser(true)
                .user(UserResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .nickname(user.getNickname())
                        .role(user.getRole())
                        .status(user.getStatus())
                        .registerType(user.getRegisterType())
                        .build())
                .build();
    }

    private void updateLoginInfo(Long userId, String ip) {
        userMapper.updateLoginInfo(userId, LocalDateTime.now(), ip, LocalDateTime.now());
    }
}