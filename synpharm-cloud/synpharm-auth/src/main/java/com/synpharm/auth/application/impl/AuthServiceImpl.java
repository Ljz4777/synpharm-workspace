package com.synpharm.auth.application.impl;

import com.synpharm.auth.api.dto.LoginRequest;
import com.synpharm.auth.api.dto.RegisterRequest;
import com.synpharm.auth.api.dto.LoginResponse;
import com.synpharm.auth.api.dto.UserResponse;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.auth.domain.model.SysLoginLog;
import com.synpharm.auth.domain.model.SysUser;
import com.synpharm.auth.infrastructure.persistence.SysLoginLogMapper;
import com.synpharm.auth.infrastructure.persistence.SysUserMapper;
import com.synpharm.auth.application.AuthService;
import com.synpharm.auth.application.CaptchaService;
import com.synpharm.auth.application.strategy.LoginStrategyFactory;
import com.synpharm.auth.support.IpUtils;
import com.synpharm.auth.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final LoginStrategyFactory loginStrategyFactory;
    private final JwtIssuer jwtIssuer;
    private final StringRedisTemplate redisTemplate;
    private final SysLoginLogMapper loginLogMapper;
    private final SysUserMapper userMapper;
    private final CaptchaService captchaService;
    private final PasswordEncoder passwordEncoder;

    private static final String LOGIN_FAIL_KEY = "login:fail:";
    private static final String LOGIN_LOCK_KEY = "login:lock:";
    private static final String TOKEN_BLACKLIST_KEY = "token:blacklist:";

    private static final int MAX_LOGIN_FAIL_COUNT = 5;
    private static final int LOCK_DURATION_MINUTES = 15;

    @Override
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String account = getAccount(request);
        String ip = IpUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        try {
            checkLoginLock(account);

            LoginResponse response = loginStrategyFactory.login(
                    request.getLoginType(),
                    request,
                    httpRequest
            );

            clearLoginFailCount(account);

            saveLoginLog(response.getUser().getId(), account, request.getLoginType(),
                    ip, userAgent, 1, null, getCaptchaType(request.getLoginType()));

            log.info("登录成功, type: {}, account: {}, ip: {}", request.getLoginType(), account, ip);
            return response;

        } catch (BizException e) {
            handleLoginFail(account, ip, userAgent, request.getLoginType(), e.getMessage(),
                    getCaptchaType(request.getLoginType()));
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        String email = request.getEmail();

        // ========== 第一步：检查邮箱是否已注册 ==========
        if (userMapper.selectByEmail(email) != null) {
            log.warn("注册-邮箱已存在, email: {}", email);
            throw new BizException(ErrorCode.USER_EXISTS, "该邮箱已注册，请直接登录");
        }

        // ========== 第二步：校验验证码（type=register） ==========
        boolean captchaValid = captchaService.verifyCaptcha(email, request.getCaptcha(), "register");
        if (!captchaValid) {
            log.warn("注册-验证码校验失败, email: {}", email);
            throw new BizException(ErrorCode.CAPTCHA_ERROR, "验证码错误或已过期");
        }

        // ========== 第三步：创建用户 ==========
        SysUser user = new SysUser();
        user.setEmail(email);
        user.setNickname(request.getNickname());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("user");
        user.setStatus(1);
        user.setEmailVerified(1);
        user.setRegisterType("qq_email");
        user.setLoginCount(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            log.warn("注册-并发冲突, email: {}", email);
            throw new BizException(ErrorCode.USER_EXISTS, "该邮箱已注册，请直接登录");
        }
        log.info("注册成功, userId: {}, email: {}", user.getId(), email);

        // ========== 第四步：生成Token并自动登录 ==========
        String token = jwtIssuer.generateToken(user.getId(), user.getEmail(), user.getRole());
        String ip = IpUtils.getClientIp(httpRequest);
        userMapper.updateLoginInfo(user.getId(), LocalDateTime.now(), ip, LocalDateTime.now());

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtIssuer.getExpiration() / 1000)
                .isNewUser(true)
                .user(UserResponse.fromEntity(user))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String email, String captcha, String newPassword) {
        if (newPassword == null || !Pattern.matches(LoginRequest.PASSWORD_REGEX, newPassword)) {
            throw new BizException(ErrorCode.PASSWORD_TOO_WEAK, "密码至少8位，必须包含大小写字母和数字");
        }

        boolean captchaValid = captchaService.verifyCaptcha(email, captcha, "reset");
        if (!captchaValid) {
            log.warn("忘记密码验证码校验失败, email: {}", email);
            throw new BizException(ErrorCode.CAPTCHA_ERROR, "验证码错误或已过期");
        }

        SysUser user = userMapper.selectByEmail(email);
        if (user == null) {
            log.warn("忘记密码-用户不存在, email: {}", email);
            throw new BizException(ErrorCode.USER_NOT_FOUND, "该邮箱未注册");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        log.info("忘记密码-密码重置成功, email: {}", email);
    }

    @Override
    public void logout(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            log.warn("登出请求缺少 Token 标识（X-Token-Id），无法加入黑名单");
            return;
        }
        try {
            // TTL 取 Token 的最大有效期：比实际剩余时间略长无害（jti 全局唯一），
            // 但必须足够长 —— 黑名单被提前淘汰等于给已登出的 Token 复活。
            redisTemplate.opsForValue().set(
                    TOKEN_BLACKLIST_KEY + tokenId,
                    "1",
                    jwtIssuer.getExpiration(),
                    TimeUnit.MILLISECONDS
            );
            log.info("Token已加入黑名单, jti: {}", tokenId);
        } catch (Exception e) {
            // 红线 R7：禁止静默兜底 —— Redis 不可用意味着登出没生效，必须让调用方知道
            log.error("Token加入黑名单失败: {}", e.getMessage());
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String getAccount(LoginRequest request) {
        if ("guest".equals(request.getLoginType())) {
            return "guest";
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            return request.getEmail();
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            return request.getPhone();
        }
        throw new BizException(ErrorCode.VALIDATION_FAILED, "无法获取登录账号");
    }

    private String getCaptchaType(String loginType) {
        return switch (loginType) {
            case "qq_email" -> "email";
            case "phone" -> "sms";
            default -> null;
        };
    }

    private void checkLoginLock(String account) {
        if ("guest".equals(account)) {
            return;
        }
        String lockKey = LOGIN_LOCK_KEY + account;
        Boolean locked = redisTemplate.hasKey(lockKey);
        if (Boolean.TRUE.equals(locked)) {
            Long expire = redisTemplate.getExpire(lockKey, TimeUnit.MINUTES);
            log.warn("账户已被锁定, account: {}, 剩余{}分钟", account, expire);
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    "账户已被锁定，请" + expire + "分钟后再试");
        }
    }

    private void handleLoginFail(String account, String ip, String userAgent,
                                 String loginType, String reason, String captchaType) {
        if ("guest".equals(account)) {
            return;
        }
        String failKey = LOGIN_FAIL_KEY + account;
        Long failCount = redisTemplate.opsForValue().increment(failKey);

        if (failCount != null && failCount == 1) {
            redisTemplate.expire(failKey, LOCK_DURATION_MINUTES, TimeUnit.MINUTES);
        }

        if (failCount != null && failCount >= MAX_LOGIN_FAIL_COUNT) {
            redisTemplate.opsForValue().set(
                    LOGIN_LOCK_KEY + account,
                    "1",
                    LOCK_DURATION_MINUTES,
                    TimeUnit.MINUTES
            );
            log.warn("账户连续登录失败{}次，已锁定, account: {}", failCount, account);
        }

        saveLoginLog(null, account, loginType, ip, userAgent, 0, reason, captchaType);
    }

    private void clearLoginFailCount(String account) {
        if ("guest".equals(account)) {
            return;
        }
        redisTemplate.delete(LOGIN_FAIL_KEY + account);
        redisTemplate.delete(LOGIN_LOCK_KEY + account);
    }

    private void saveLoginLog(Long userId, String account, String loginType,
                              String ip, String userAgent, Integer status,
                              String failReason, String captchaType) {
        try {
            SysLoginLog logEntity = new SysLoginLog();
            logEntity.setUserId(userId);
            logEntity.setAccount(account);
            logEntity.setLoginType(loginType);
            logEntity.setLoginIp(ip);
            logEntity.setUserAgent(userAgent);
            logEntity.setCaptchaType(captchaType);
            logEntity.setStatus(status);
            logEntity.setFailReason(failReason);
            loginLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.error("记录登录日志失败: {}", e.getMessage());
        }
    }
}