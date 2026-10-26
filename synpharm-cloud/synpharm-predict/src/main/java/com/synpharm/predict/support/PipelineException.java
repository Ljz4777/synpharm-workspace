package com.synpharm.predict.support;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

/**
 * 数据管道分阶段异常（输入解析 / 执行 / 输出格式化）。
 *
 * <p>阶段 4 迁移后改为继承 {@link BizException}，阶段映射沿用单体 GlobalExceptionHandler 的口径：
 * {@code execute → FASTAPI_UNAVAILABLE}，其余（parse / format）{@code → INPUT_RESOLVE_FAILED}。
 * 对外表现随之改变：由「HTTP 200 + body code 6001」变为<b>真实 HTTP 状态码</b>（503 / 400），
 * errorCode 字段的取值不变。
 *
 * @author SynPharm Team
 * @version 2.0.0
 */
public class PipelineException extends BizException {

    private static final long serialVersionUID = 1L;

    /** 出错阶段：parse / execute / format */
    private final String stage;

    public PipelineException(String stage, String message) {
        super(mapStage(stage), "管道[" + stage + "]执行失败: " + message);
        this.stage = stage;
    }

    public PipelineException(String stage, String message, Throwable cause) {
        super(mapStage(stage), "管道[" + stage + "]执行失败: " + message);
        this.stage = stage;
        if (cause != null) {
            initCause(cause);
        }
    }

    public String getStage() {
        return stage;
    }

    private static ErrorCode mapStage(String stage) {
        return "execute".equals(stage) ? ErrorCode.FASTAPI_UNAVAILABLE : ErrorCode.INPUT_RESOLVE_FAILED;
    }

    public static PipelineException parse(String message) {
        return new PipelineException("parse", message);
    }

    public static PipelineException parse(String message, Throwable cause) {
        return new PipelineException("parse", message, cause);
    }

    public static PipelineException execute(String message) {
        return new PipelineException("execute", message);
    }

    public static PipelineException execute(String message, Throwable cause) {
        return new PipelineException("execute", message, cause);
    }

    public static PipelineException format(String message) {
        return new PipelineException("format", message);
    }

    public static PipelineException format(String message, Throwable cause) {
        return new PipelineException("format", message, cause);
    }
}