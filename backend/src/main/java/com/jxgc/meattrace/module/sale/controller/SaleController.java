package com.jxgc.meattrace.module.sale.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.sale.dto.SaleCreateRequest;
import com.jxgc.meattrace.module.sale.dto.SaleCreateVO;
import com.jxgc.meattrace.module.sale.dto.SaleQuery;
import com.jxgc.meattrace.module.sale.dto.SaleVO;
import com.jxgc.meattrace.module.sale.service.SaleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 销售登记接口，口径见 docs/tech/03-接口文档.md 4.5。 */
@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final SaleService saleService;

    public SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<SaleCreateVO> create(
            @Valid @RequestBody SaleCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(saleService.create(request, requestId, user.userId()));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<SaleVO>> page(SaleQuery query) {
        return R.ok(saleService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<SaleVO> detail(@PathVariable long id) {
        return R.ok(saleService.detail(id));
    }
}
