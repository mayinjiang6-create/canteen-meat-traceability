package com.jxgc.meattrace.module.auth.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口允许的角色，由 {@code AuthInterceptor} 校验。
 *
 * <p>不写该注解表示"登录即可访问"；写了则必须命中其中一个角色。
 * 用法：{@code @RequireRole({"ADMIN"})}
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    String[] value();
}
