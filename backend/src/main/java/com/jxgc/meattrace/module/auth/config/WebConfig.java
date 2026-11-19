package com.jxgc.meattrace.module.auth.config;

import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册。
 *
 * <p>放行清单只有三处：登录接口、公开溯源接口（{@code /api/public/**}）、
 * 存活探针（{@code /api/health}，容器健康检查用，不返回任何业务数据）。
 * 其余 {@code /api/**} 一律要求 token。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/public/**",
                        "/api/health",
                        "/error");
    }
}
