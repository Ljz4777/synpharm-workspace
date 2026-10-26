package com.synpharm.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.synpharm.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应信封。
 *
 * <pre>{@code
 * { "code": 0, "errorCode": null, "message": "ok", "data": {}, "warnings": [] }
 * }</pre>
 *
 * <p><b>成功码是 {@code 0}，不是 {@code 200}</b>（勘误 C-01）。落地范围：
 * {@code synpharm-common}、新建的微服务、以及单体 {@code com.synpharm.utils.Result}，
 * 前端 {@code request.ts} 同步按 {@code code !== 0} 判错。
 *
 * @param <T> 业务数据类型
 * @author SynPharm Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 成功聚合码（勘误 C-01） */
    public static final int SUCCESS_CODE = 0;

    /** 聚合码：成功 = 0；失败 = HTTP 状态码 × 100 + 序号 */
    private Integer code;

    /** 业务错误标识，成功为 null */
    private String errorCode;

    /** 面向用户的可读文案 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 部分成功 / 能力降级提示；有内容才出现（勘误 C-04） */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Warning> warnings;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(SUCCESS_CODE)
                .message("ok")
                .data(data)
                .build();
    }

    /**
     * 成功但存在能力降级 / 部分失败。
     *
     * <p>红线 R1：只要出现 {@code warnings[]}，前端<b>必须</b>在界面上可见，不得静默丢弃。
     */
    public static <T> Result<T> success(T data, List<Warning> warnings) {
        return Result.<T>builder()
                .code(SUCCESS_CODE)
                .message("ok")
                .data(data)
                .warnings(warnings)
                .build();
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.getMessage(), null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return failure(errorCode, message, null);
    }

    /**
     * 失败响应。
     *
     * @param errorCode 错误码
     * @param message   覆盖默认文案；传 null 时用 {@link ErrorCode#getMessage()}
     * @param data      错误补充信息（通常是 {@code Map.of("details", ...)}），可为 null
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message, T data) {
        return Result.<T>builder()
                .code(errorCode.getCode())
                .errorCode(errorCode.getErrorCode())
                .message(message != null ? message : errorCode.getMessage())
                .data(data)
                .build();
    }
}
