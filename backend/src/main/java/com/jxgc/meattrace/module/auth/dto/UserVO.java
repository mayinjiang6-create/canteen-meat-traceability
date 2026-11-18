package com.jxgc.meattrace.module.auth.dto;

/** 当前登录用户出参（不含口令哈希）。 */
public record UserVO(
        Long id,
        String username,
        String displayName,
        String role,
        Long supplierId) {
}
