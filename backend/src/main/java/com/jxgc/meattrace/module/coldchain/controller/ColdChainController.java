package com.jxgc.meattrace.module.coldchain.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainCreateRequest;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainQuery;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainVO;
import com.jxgc.meattrace.module.coldchain.service.ColdChainService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 冷链温控登记接口，口径见 docs/tech/03-接口文档.md 4.6。 */
@RestController
@RequestMapping("/api/cold-chain-logs")
public class ColdChainController {

    private final ColdChainService coldChainService;

    public ColdChainController(ColdChainService coldChainService) {
        this.coldChainService = coldChainService;
    }

    @PostMapping
    @RequireRole({"ADMIN", "SUPPLIER"})
    public R<ColdChainVO> create(
            @Valid @RequestBody ColdChainCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(coldChainService.create(request, requestId, user.userId()));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<ColdChainVO>> page(ColdChainQuery query) {
        return R.ok(coldChainService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<ColdChainVO> detail(@PathVariable long id) {
        return R.ok(coldChainService.detail(id));
    }
}
