package com.jxgc.meattrace.common;

/** 携带 {@link ErrorCode} 的业务异常，由 {@link GlobalExceptionHandler} 统一翻译成返回体。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message == null || message.isBlank() ? errorCode.getMessage() : message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public static BizException of(ErrorCode code) {
        return new BizException(code);
    }

    public static BizException of(ErrorCode code, String message) {
        return new BizException(code, message);
    }
}
