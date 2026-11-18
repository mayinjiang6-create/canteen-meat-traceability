package com.jxgc.meattrace.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.MDC;

/**
 * 统一返回体，口径见 docs/tech/03-接口文档.md 1.1。
 *
 * <p>约定：{@code code = 0} 表示业务成功且 HTTP 200；业务失败时 HTTP 仍为 200
 * （鉴权失败 401 / 系统级异常 500 除外），{@code data} 为 null。
 *
 * @param <T> 业务数据类型
 */
public class R<T> {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private int code;
    private String message;
    private T data;
    private String traceId;
    private String timestamp;

    public R() {
    }

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = MDC.get(TraceIdFilter.MDC_KEY);
        this.timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(TS);
    }

    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(0, message, data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.getCode(),
                message == null || message.isBlank() ? errorCode.getMessage() : message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
