package com.scmmaisc.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包裹（对齐 contracts/api.md）：{ code, message, data }，code=0 成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static ApiResult<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode, String message, T data) {
        return new ApiResult<>(errorCode.getCode(), message, data);
    }
}
