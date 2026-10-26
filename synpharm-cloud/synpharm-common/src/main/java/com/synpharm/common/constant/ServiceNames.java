package com.synpharm.common.constant;

/**
 * 服务注册名。
 *
 * <p>必须与各模块的 {@code spring.application.name} 一致 —— 网关路由的
 * {@code lb://<service-name>} 直接依赖它。
 *
 * @author SynPharm Team
 */
public final class ServiceNames {

    /** 边缘网关 */
    public static final String GATEWAY = "synpharm-gateway";

    /** 身份与访问（阶段 1） */
    public static final String AUTH = "synpharm-auth";

    /** AI 预测（阶段 4） */
    public static final String PREDICT = "synpharm-predict";

    /** 分子设计（阶段 2，白纸新建） */
    public static final String DESIGN = "synpharm-design";

    /** 文件资产（阶段 3） */
    public static final String ASSET = "synpharm-asset";

    /** 平台治理（阶段 5） */
    public static final String PLATFORM = "synpharm-platform";

    private ServiceNames() {
    }
}
