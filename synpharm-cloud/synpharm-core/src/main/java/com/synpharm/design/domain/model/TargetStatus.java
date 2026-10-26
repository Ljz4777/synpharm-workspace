package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

import java.util.Arrays;

/**
 * 靶点支持状态（契约 {@code Target.status}，取值小写）。
 *
 * <p>对应 {@code target.status}。语义是「该靶点在平台上的支持程度」，决定前端是否允许把它
 * 绑到项目上跑生成/评估：
 * <ul>
 *   <li>{@code supported} —— 已验证可用（引擎侧有对应模型）</li>
 *   <li>{@code beta} —— 可用但结果仅供探索</li>
 *   <li>{@code planned} —— 数据已录入但引擎尚未支持，只能浏览</li>
 * </ul>
 *
 * <p><b>为什么非法值要抛 400 而不是当作"查不到"。</b>它被用作列表的过滤条件，若静默吞掉
 * 非法值会返回**全量**列表 —— 用户以为筛过了，实际拿到的是没筛过的数据，比报错危险得多。
 * 这与 design 域其它过滤参数的处理一致（如报告列表的 {@code decision}）。
 *
 * @author SynPharm Team
 */
public enum TargetStatus {

    /** 已验证可用 */
    SUPPORTED,
    /** 可用但仅供探索 */
    BETA,
    /** 数据已录入、引擎未支持 */
    PLANNED;

    /**
     * 解析过滤条件里的状态值。
     *
     * @param raw 请求参数（大小写不敏感）
     * @throws BizException {@link ErrorCode#VALIDATION_FAILED} 取值不在枚举内
     */
    public static TargetStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "status 不能为空");
        }
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "未知的 status: " + raw + "，可选 " + Arrays.toString(values())));
    }
}
