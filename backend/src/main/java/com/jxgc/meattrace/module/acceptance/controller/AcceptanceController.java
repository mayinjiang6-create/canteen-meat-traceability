package com.jxgc.meattrace.module.acceptance.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceCreateRequest;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceQuery;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceVO;
import com.jxgc.meattrace.module.acceptance.service.AcceptanceService;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 验收接口。 */
@RestController
@RequestMapping("/api/acceptances")
public class AcceptanceController {

    private final AcceptanceService acceptanceService;

    public AcceptanceController(AcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<AcceptanceVO> create(
            @Valid @RequestBody AcceptanceCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(acceptanceService.create(request, requestId, user.userId()));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<AcceptanceVO>> page(AcceptanceQuery query) {
        return R.ok(acceptanceService.page(query));
    }
}
