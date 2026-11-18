package com.jxgc.meattrace.module.process.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.process.dto.ProcessCreateRequest;
import com.jxgc.meattrace.module.process.dto.ProcessCreateVO;
import com.jxgc.meattrace.module.process.dto.ProcessQuery;
import com.jxgc.meattrace.module.process.dto.ProcessVO;
import com.jxgc.meattrace.module.process.service.ProcessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 加工投料接口，口径见 docs/tech/03-接口文档.md 4.4。 */
@RestController
@RequestMapping("/api/process-batches")
public class ProcessController {

    private final ProcessService processService;

    public ProcessController(ProcessService processService) {
        this.processService = processService;
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<ProcessCreateVO> create(
            @Valid @RequestBody ProcessCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(processService.create(request, requestId, user.userId()));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<ProcessVO>> page(ProcessQuery query) {
        return R.ok(processService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<ProcessVO> detail(@PathVariable long id) {
        return R.ok(processService.detail(id));
    }

    @PostMapping("/{id}/onsale")
    @RequireRole({"ADMIN"})
    public R<ProcessVO> onSale(@PathVariable long id) {
        return R.ok(processService.onSale(id));
    }
}
