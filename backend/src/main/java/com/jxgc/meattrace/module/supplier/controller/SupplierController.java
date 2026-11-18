package com.jxgc.meattrace.module.supplier.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.supplier.dto.SupplierCreateRequest;
import com.jxgc.meattrace.module.supplier.dto.SupplierCreateVO;
import com.jxgc.meattrace.module.supplier.dto.SupplierQuery;
import com.jxgc.meattrace.module.supplier.dto.SupplierUpdateRequest;
import com.jxgc.meattrace.module.supplier.dto.SupplierVO;
import com.jxgc.meattrace.module.supplier.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供应商接口，口径见 docs/tech/03-接口文档.md 第 3 节。 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<SupplierVO>> page(SupplierQuery query) {
        return R.ok(supplierService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<SupplierVO> detail(@PathVariable long id) {
        return R.ok(supplierService.getById(id));
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<SupplierCreateVO> create(
            @Valid @RequestBody SupplierCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(supplierService.create(request, requestId, user.userId()));
    }

    @PutMapping("/{id}")
    @RequireRole({"ADMIN"})
    public R<SupplierVO> update(@PathVariable long id,
                                @Valid @RequestBody SupplierUpdateRequest request) {
        return R.ok(supplierService.update(id, request));
    }
}
