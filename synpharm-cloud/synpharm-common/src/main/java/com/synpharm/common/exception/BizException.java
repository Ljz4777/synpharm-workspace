package com.synpharm.common.exception;

import com.synpharm.common.error.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带 {@link ErrorCode}，由各服务的全局异常处理器统一转成响应信封。
 *
 * <p>只在「可以预期的业务拒绝」场景抛出；未预期异常一律走
 * {@link ErrorCode#INTERNAL_ERROR}，且不得泄露堆栈给客户端。
 *
 * @author SynPharm Team
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final transient ErrorCode errorCode;

    /** 错误补充信息，可为 null */
    private final transient Object details;

    public BizException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * @param errorCode 错误码
     * @param message   覆盖默认文案；传 null 时用 {@link ErrorCode#getMessage()}
     * @param details   错误补充信息（对应信封 {@code data.details}），可为 null
     */
    public BizException(ErrorCode errorCode, String message, Object details) {
        super(message != null ? message : errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    /** 是否携带了错误补充信息 */
    public boolean hasDetails() {
        return details != null;
    }
}
