package com.jxgc.meattrace.chain;

/**
 * 链不可达或返回异常。对应公开页的 {@code UNREACHABLE} 核验状态——
 * 必须与「记录不一致 MISMATCHED」严格区分，不能混为一谈。
 */
public class ChainUnavailableException extends RuntimeException {

    public ChainUnavailableException(String message) {
        super(message);
    }

    public ChainUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
