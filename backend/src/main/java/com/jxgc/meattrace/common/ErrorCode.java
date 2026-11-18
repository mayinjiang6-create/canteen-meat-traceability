package com.jxgc.meattrace.common;

/**
 * 业务错误码，口径见 docs/tech/03-接口文档.md 第 2 节。
 *
 * <p>注意 {@code httpStatus}：按契约，业务失败时 HTTP 仍返回 200，
 * 只有鉴权失败给 401/403、系统级异常给 500。
 */
public enum ErrorCode {

    OK(0, 200, "ok"),

    PARAM_INVALID(40001, 200, "参数校验失败"),
    QUANTITY_FORMAT_INVALID(40002, 200, "数量格式非法，需为 3 位小数"),

    UNAUTHORIZED(40101, 401, "未登录或 token 过期"),
    FORBIDDEN(40102, 403, "无操作权限"),

    NOT_FOUND(40401, 200, "资源不存在"),

    QTY_NOT_ENOUGH(40901, 200, "可用余量不足"),
    STATUS_NOT_ALLOWED(40902, 200, "当前状态不允许该操作"),
    DUPLICATE_REQUEST(40903, 200, "重复请求"),
    SOURCE_DUPLICATED(40904, 200, "来源重复引用同一批次"),
    DIGEST_CONFLICT(40905, 200, "事件摘要冲突，链上保留原记录"),

    TOO_MANY_REQUESTS(42901, 429, "请求过于频繁"),

    EVIDENCE_SUBMIT_FAILED(50001, 200, "已入库，存证待重试"),
    CHAIN_UNREACHABLE(50002, 200, "暂无法核验：链上不可达"),
    LLM_FAILED(50003, 200, "大模型调用失败"),

    INTERNAL_ERROR(50000, 500, "系统内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
