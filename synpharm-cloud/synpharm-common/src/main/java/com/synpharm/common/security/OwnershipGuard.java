package com.synpharm.common.security;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

import java.util.Map;

/**
 * 归属校验（红线 R4 / 规范 §5）。
 *
 * <p><b>失败一律 404，不返回 403</b>：403 等于告诉攻击者「这个 id 存在，只是不属于你」，
 * 可以直接枚举出别人的资源总量。
 *
 * <p>对 {@code ownerId == null} 必须抛错（fail-closed）。既有单体存在
 * 「userId 为空就放行」的写法（问题总账 B-12），那是典型的越权漏洞，各域严禁沿用。
 *
 * @author SynPharm Team
 */
public final class OwnershipGuard {

    private OwnershipGuard() {
    }

    /**
     * 校验资源归属。
     *
     * @param currentUserId 当前登录用户（来自网关注入的可信身份）
     * @param ownerId       资源属主
     * @param notFound      不匹配时使用的错误码（必须是该资源的 {@code *_NOT_FOUND}）
     * @param details       放进 {@code data.details} 的排障信息
     */
    public static void assertOwned(Long currentUserId, Long ownerId, ErrorCode notFound,
                                   Map<String, Object> details) {
        if (currentUserId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (ownerId == null || !currentUserId.equals(ownerId)) {
            throw new BizException(notFound, null, details);
        }
    }
}
