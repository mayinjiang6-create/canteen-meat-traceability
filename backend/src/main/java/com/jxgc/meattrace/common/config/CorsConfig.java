package com.jxgc.meattrace.common.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域兜底配置（阶段 C 前端接入时补）。
 *
 * <p><b>为什么用 Filter 而不是 WebMvcConfigurer#addCorsMappings：</b>
 * 浏览器发跨域 POST 前会先发一个 {@code OPTIONS} 预检，且<b>不带 Authorization 头</b>。
 * 本项目 {@code AuthInterceptor} 拦截所有 {@code /api/**}，若 CORS 只配在 MVC 层，
 * 预检会先被拦截器判为「未登录」返回 401，浏览器根本拿不到 CORS 响应头，
 * 于是整个前端请求全被浏览器拦下。用 Servlet Filter 且设为最高优先级，
 * 预检在进 DispatcherServlet 之前就被短路返回，不会碰到任何鉴权逻辑。
 *
 * <p>开发期前端走 Vite 的 {@code /api} 代理，本配置其实用不上；
 * 它服务于两种场景：① 打包后前后端分离部署；② 手机 / 别的电脑通过局域网 IP 直连演示。
 *
 * <p>允许的来源刻意用 {@code allowedOriginPatterns} 限定为本机与私有网段，
 * 不写死 {@code *}——本项目带 Bearer token，允许任意来源等于把 token 暴露给任意站点。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://192.168.*",
                "http://10.*",
                "http://172.16.*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Request-Id",
                "X-Requested-With",
                "Accept",
                "Origin"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setName("corsFilter");
        return bean;
    }
}
