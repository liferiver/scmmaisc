package com.scmmaisc.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码（对齐 contracts/api.md 错误码表）。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "ok", HttpStatus.OK),
    PARAM_INVALID(40001, "参数类型或范围非法", HttpStatus.BAD_REQUEST),
    CONSTRAINT_FAILED(40002, "约束校验不通过", HttpStatus.BAD_REQUEST),
    FORBIDDEN(40301, "运行记录归属校验失败", HttpStatus.FORBIDDEN),
    NOT_FOUND(40401, "资源不存在", HttpStatus.NOT_FOUND),
    STATE_CONFLICT(40901, "状态冲突", HttpStatus.CONFLICT),
    ENGINE_ERROR(50000, "引擎内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
