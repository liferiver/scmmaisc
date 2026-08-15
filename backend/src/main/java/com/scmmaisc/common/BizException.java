package com.scmmaisc.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码与可选错误详情（如校验原因列表，对齐 contracts/api.md 错误码表）。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 错误详情（如 400 时的具体原因列表）。 */
    private final Object detail;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object detail) {
        super(message);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
