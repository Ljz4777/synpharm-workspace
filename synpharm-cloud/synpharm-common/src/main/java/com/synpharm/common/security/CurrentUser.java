package com.synpharm.common.security;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 当前请求的可信身份（由 {@link InternalRequestFilter} 写入，仅当来源可信时非空）。
 *
 * <p>为什么用 ThreadLocal 而不是直接读请求头：Servlet 请求头<b>不可修改</b>，
 * 「剥离伪造头」只能靠包装请求对象；而包装一旦漏掉某个读取路径（例如
 * {@code getHeaderNames} 与 {@code getHeader} 不一致），伪造头就会漏进业务代码。
 * 把「是否可信」收敛到唯一入口，比分散剥离更不容易出错。
 *
 * @author SynPharm Team
 */
public final class CurrentUser {

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    /**
     * 可信身份。
     *
     * @param userId  用户 ID
     * @param role    角色
     * @param email   邮箱
     * @param tokenId Token 唯一标识（jti）
     */
    public record Identity(Long userId, String role, String email, String tokenId) {
    }

    static void set(Identity identity) {
        HOLDER.set(identity);
    }

    static void clear() {
        HOLDER.remove();
    }

    /** 可信身份，不存在时返回 null */
    public static Identity get() {
        return HOLDER.get();
    }

    /**
     * 取可信用户 ID；不存在则 401。
     *
     * <p>业务代码只应通过本方法获取用户身份，<b>不得</b>直接读 {@code X-User-Id} 头。
     */
    public static Long requireUserId() {
        Identity identity = HOLDER.get();
        if (identity == null || identity.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return identity.userId();
    }

    /** 取 Token 的 jti（登出写黑名单用），不存在时返回 null */
    public static String tokenId() {
        Identity identity = HOLDER.get();
        return identity == null ? null : identity.tokenId();
    }
}
