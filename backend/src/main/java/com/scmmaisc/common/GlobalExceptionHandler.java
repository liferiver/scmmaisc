package com.scmmaisc.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：业务异常按错误码映射 HTTP 状态；
 * 请求体/参数格式错误统一 400（T041）；未知异常不泄露内部细节（宪法"安全"约束）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Object>> handleBiz(BizException ex) {
        log.warn("业务异常: code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        ApiResult<Object> body = ApiResult.error(ex.getErrorCode(), ex.getMessage(), ex.getDetail());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    /** 请求体 JSON 不可读（格式错误/字段类型不匹配）：400，不泄露解析细节。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return badRequest("请求体格式错误或字段类型不合法");
    }

    /** 缺少必填请求参数（如 clientId）：400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("缺少请求参数: {}", ex.getParameterName());
        return badRequest("缺少必填参数: " + ex.getParameterName());
    }

    /** 路径/查询参数类型不匹配（如 /api/runs/abc）：400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配: {}", ex.getName());
        return badRequest("参数 " + ex.getName() + " 类型不合法");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        ApiResult<Void> body = ApiResult.error(ErrorCode.ENGINE_ERROR, "服务器内部错误，请稍后重试", null);
        return ResponseEntity.status(ErrorCode.ENGINE_ERROR.getHttpStatus()).body(body);
    }

    private static ResponseEntity<ApiResult<Void>> badRequest(String message) {
        ApiResult<Void> body = ApiResult.error(ErrorCode.PARAM_INVALID, message, null);
        return ResponseEntity.badRequest().body(body);
    }
}
