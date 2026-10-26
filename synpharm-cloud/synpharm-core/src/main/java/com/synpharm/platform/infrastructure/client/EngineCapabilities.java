package com.synpharm.platform.infrastructure.client;

import lombok.Data;

import java.util.Map;

/**
 * 算法引擎能力矩阵（计算层契约 `GET /v1/evaluate/capabilities`，见 04 §2.1）。
 *
 * <p>{@code probedAt} 用 String 承接：它是引擎侧的时间戳（带时区偏移），
 * 本服务不依赖它做时间运算，硬套 OffsetDateTime 反而会在格式细微差异时抛反序列化异常。
 *
 * @author SynPharm Team
 */
@Data
public class EngineCapabilities {

    /** 引擎侧探测时间（原样透传，仅作展示） */
    private String probedAt;

    /** 引擎是否就绪：rdkit / crem / p2rank / vina / admet … */
    private Map<String, Boolean> engines;

    /** 模型就绪明细 */
    private Map<String, ModelCapability> models;

    /** 端点状态：READY / NOT_IMPLEMENTED */
    private Map<String, String> endpoints;

    /** 单个模型的能力明细 */
    @Data
    public static class ModelCapability {

        /** 是否就绪（**取值以此为准**） */
        private Boolean ready;

        /** 权重文件是否存在 */
        private Boolean weightsPresent;

        /** 是否已加载（观测值，不是「能否加载」） */
        private Boolean loaded;

        /** 不就绪的原因 */
        private String error;
    }
}