package com.jxgc.meattrace.module.purchase.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.purchase.dto.PurchaseCreateRequest;
import com.jxgc.meattrace.module.purchase.dto.PurchaseCreateVO;
import com.jxgc.meattrace.module.purchase.dto.PurchaseQuery;
import com.jxgc.meattrace.module.purchase.dto.PurchaseVO;
import com.jxgc.meattrace.module.purchase.service.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 采购批次接口。 */
@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<PurchaseVO>> page(PurchaseQuery query) {
        return R.ok(purchaseService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PurchaseVO> detail(@PathVariable long id) {
        return R.ok(purchaseService.detail(id));
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<PurchaseCreateVO> create(
            @Valid @RequestBody PurchaseCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(purchaseService.create(request, requestId, user.userId()));
    }
}
