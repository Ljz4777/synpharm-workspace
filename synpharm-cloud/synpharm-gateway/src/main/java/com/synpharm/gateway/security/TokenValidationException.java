package com.synpharm.gateway.security;

import com.synpharm.common.error.ErrorCode;
import lombok.Getter;

/**
 * Token 校验失败，携带对应的错误码（TOKEN_EXPIRED / TOKEN_INVALID / SIGNATURE_INVALID）。
 *
 * @author SynPharm Team
 */
@Getter
public class TokenValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    public TokenValidationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
