package com.jxgc.meattrace.module.auth.dto;

/** 登录出参。{@code expiresIn} 单位秒。 */
public record LoginResponse(String token, long expiresIn, UserVO user) {
}
