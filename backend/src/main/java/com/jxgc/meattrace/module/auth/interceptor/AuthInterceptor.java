package com.jxgc.meattrace.module.auth.interceptor;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.service.JwtTokenService;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器：校验 Bearer token，并按 {@link RequireRole} 做角色校验。
 *
 * <p>不放行任何"未标注即公开"的路径——公开接口在 {@code WebConfig} 里显式排除。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Controller 里可用 {@code @RequestAttribute(AuthInterceptor.ATTR_USER)} 取到。 */
    public static final String ATTR_USER = "currentUser";

    private static final String BEARER = "Bearer ";

    private final JwtTokenService jwtTokenService;

    public AuthInterceptor(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        TokenPayload payload = jwtTokenService.parse(header.substring(BEARER.length()).trim());

        RequireRole required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequireRole.class);
        }
        if (required != null && Arrays.stream(required.value()).noneMatch(r -> r.equals(payload.role()))) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        request.setAttribute(ATTR_USER, payload);
        return true;
    }
}
