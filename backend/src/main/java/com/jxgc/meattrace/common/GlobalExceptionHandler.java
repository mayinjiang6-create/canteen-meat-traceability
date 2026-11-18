package com.jxgc.meattrace.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常翻译。
 *
 * <p>关键口径：业务异常按 {@link ErrorCode#getHttpStatus()} 返回（多数仍是 200），
 * 只有鉴权失败给 401/403、未知异常给 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        if (code.getHttpStatus() >= 500) {
            log.error("业务异常 {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        } else {
            log.warn("业务异常 {} {} -> {} ({})",
                    request.getMethod(), request.getRequestURI(), code.getCode(), e.getMessage());
        }
        return ResponseEntity.status(code.getHttpStatus())
                .body(R.fail(code, e.getMessage()));
    }

    /** @Valid 校验失败（@RequestBody）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.ok(R.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.ok(R.fail(ErrorCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.ok(R.fail(ErrorCode.PARAM_INVALID, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未捕获异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(500).body(R.fail(ErrorCode.INTERNAL_ERROR));
    }
}
