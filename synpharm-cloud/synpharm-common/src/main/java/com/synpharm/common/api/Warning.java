package com.synpharm.common.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 部分成功 / 能力降级提示（勘误 C-04）。
 *
 * <p>这类「成功但结果不完整」的情况<b>不是错误</b>，不进错误码表，
 * 由响应信封的 {@code warnings[]} 承载。已登记警示码见
 * {@code docs/conventions/01-接口与错误码规范.md} §5.2。
 *
 * @author SynPharm Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warning {

    /** 警示码（复用错误码命名空间，语义为「降级/警告」） */
    private String code;

    /** 面向用户的中文说明 */
    private String message;

    /** 作用域：METRIC / ITEM / TOOL / RUN / SESSION */
    private String scope;

    /** 作用对象标识（指标码 / 明细 ID 列表 / 工具码 / runNo） */
    private String ref;

    /** 补充信息（如 redistributedTo[]、reason） */
    private Map<String, Object> details;
}
