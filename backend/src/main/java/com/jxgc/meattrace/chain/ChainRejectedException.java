package com.jxgc.meattrace.chain;

/**
 * 链上明确拒绝了这笔交易（合约 require 失败、revert 等）。
 *
 * <p>与 {@link ChainUnavailableException} 的区别很重要：
 * 这个异常表示「**确定没写进去**」，可以安全重试；
 * 而 Unavailable 表示「不知道写没写」，重试前必须先查链（BR-14）。
 */
public class ChainRejectedException extends RuntimeException {

    public ChainRejectedException(String message) {
        super(message);
    }

    public ChainRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
