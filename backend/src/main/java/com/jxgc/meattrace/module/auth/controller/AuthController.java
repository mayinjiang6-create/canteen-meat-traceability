package com.jxgc.meattrace.module.auth.controller;

import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.dto.LoginRequest;
import com.jxgc.meattrace.module.auth.dto.LoginResponse;
import com.jxgc.meattrace.module.auth.dto.UserVO;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.AuthService;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口，口径见 docs/tech/03-接口文档.md 4.1。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @GetMapping("/me")
    public R<UserVO> me(@RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload payload) {
        return R.ok(authService.currentUser(payload.userId()));
    }
}
