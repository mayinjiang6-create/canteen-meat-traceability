package com.jxgc.meattrace.module.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录入参。 */
public record LoginRequest(
        @NotBlank(message = "不能为空") String username,
        @NotBlank(message = "不能为空") String password) {
}
