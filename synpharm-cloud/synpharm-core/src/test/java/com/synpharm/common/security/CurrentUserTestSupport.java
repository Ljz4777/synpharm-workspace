package com.synpharm.common.security;

/**
 * 测试用身份注入。
 *
 * <p>{@link CurrentUser} 的 {@code set/clear} 是包级私有（生产代码只应由
 * {@link InternalRequestFilter} 写入），测试要预置身份就得站在同一个包里。
 * 本类因此声明 {@code com.synpharm.common.security} 包并留在业务服务的测试源根下 ——
 * 既能访问公共库的包级成员，又不会把写入口暴露到生产 API。
 *
 * @author SynPharm Team
 */
public final class CurrentUserTestSupport {

    private CurrentUserTestSupport() {
    }

    public static void login(long userId) {
        CurrentUser.set(new CurrentUser.Identity(userId, "USER", null, "test-token-id"));
    }

    public static void logout() {
        CurrentUser.clear();
    }
}
