package com.synpharm.common.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理器（servlet 栈）。
 *
 * <p>把异常转成统一信封，并<b>按错误码决定 HTTP 状态行</b>（规范 §3）。
 * 与旧实现的区别：旧实现无论什么业务错误都返回 HTTP 200，只靠 body 里的 code 区分 ——
 * 那既不符合 HTTP 语义，也让前端无法用拦截器统一处理。
 *
 * @author SynPharm Team
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 可预期的业务拒绝：状态码取错误码自身登记的 HTTP 状态 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("业务异常: errorCode={}, message={}", errorCode.getErrorCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(body(errorCode, e.getMessage(), e.hasDetails() ? details(e) : null));
    }

    /** 参数校验失败：字段级错误放进 data.details.fieldErrors（信封约定 §1.3） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> fieldErrors = e.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("field", error instanceof FieldError fe ? fe.getField() : error.getObjectName());
                    item.put("message", error.getDefaultMessage());
                    return item;
                })
                .toList();
        log.warn("参数校验失败: {}", fieldErrors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(body(ErrorCode.VALIDATION_FAILED, null, Map.of("fieldErrors", fieldErrors)));
    }

    /** 查询参数校验失败（{@code @Validated} 触发）：405/400 都别落进兜底 500 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        List<Map<String, String>> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        log.warn("查询参数校验失败: {}", fieldErrors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(body(ErrorCode.VALIDATION_FAILED, null, Map.of("fieldErrors", fieldErrors)));
    }

    /** 参数类型不符（如 {@code page=abc}）：明确 400，否则用户只能看到 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不符: {}={}", e.getName(), e.getValue());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(body(ErrorCode.VALIDATION_FAILED, "参数类型不符",
                        Map.of("fieldErrors", List.of(Map.of("field", e.getName(),
                                "message", "期望类型 " + (e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName()))))));
    }

    /** 缺少必填查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(body(ErrorCode.VALIDATION_FAILED, "缺少必填参数 " + e.getParameterName(),
                        Map.of("fieldErrors", List.of(Map.of("field", e.getParameterName(), "message", "不能为空")))));
    }

    /**
     * 路径不存在：返回 404 而不是兜底 500。
     *
     * <p>没有这一条时，任何拼错的 URL 都会被下面的兜底处理器吞成 500，
     * 前端与排障者都看不出「其实是路径写错了」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        log.warn("路径不存在: {}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(body(ErrorCode.RESOURCE_NOT_FOUND, null, null));
    }

    /**
     * 方法不匹配：405 才是正确语义，同样是避免落进兜底 500
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(body(ErrorCode.METHOD_NOT_ALLOWED, null, null));
    }

    /**
     * 请求体不可读：JSON 语法错误、字段类型不符、**枚举取值非法**等。
     *
     * <p>没有这一条时它们会落进兜底处理器变成 500，而规范早已为这种情况登记了
     * {@link ErrorCode#MALFORMED_REQUEST}：请求体里写了一个契约之外的取值，本质是调用方的
     * 输入问题，不该让服务端表现得「像崩了」。枚举错值是最常见的触发点（各服务的请求体里
     * 都有 `category` / `algoType` / `realness` 这类枚举字段）。
     *
     * <p>因此这里额外把「非法值 + 允许取值」放进 {@code data}，让 400 可自救，
     * 而不是只回一句「请求体格式错误」让人去翻文档。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        Map<String, Object> data = null;
        for (Throwable cursor = e; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof InvalidFormatException invalid && invalid.getTargetType() != null) {
                Class<?> target = invalid.getTargetType();
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("value", String.valueOf(invalid.getValue()));
                detail.put("expectedType", target.getSimpleName());
                if (target.isEnum()) {
                    detail.put("allowedValues", Arrays.stream(target.getEnumConstants())
                            .map(String::valueOf).toList());
                }
                data = detail;
                break;
            }
        }
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.getHttpStatus())
                .body(body(ErrorCode.MALFORMED_REQUEST, null, data));
    }

    /** 兜底：不把堆栈或内部细节返回给客户端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(body(ErrorCode.INTERNAL_ERROR, null, null));
    }

    private static Map<String, Object> details(BizException e) {
        return Map.of("details", e.getDetails());
    }

    private static Map<String, Object> body(ErrorCode errorCode, String message, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", errorCode.getCode());
        body.put("errorCode", errorCode.getErrorCode());
        body.put("message", message != null ? message : errorCode.getMessage());
        body.put("data", data);
        return body;
    }
}
