package com.jxgc.meattrace.module.recall.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import com.jxgc.meattrace.module.recall.dto.RecallCreateRequest;
import com.jxgc.meattrace.module.recall.dto.RecallQuery;
import com.jxgc.meattrace.module.recall.dto.RecallVO;
import com.jxgc.meattrace.module.recall.service.RecallService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批次召回接口，口径见 docs/tech/03-接口文档.md 4.8。 */
@RestController
@RequestMapping("/api/recalls")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<RecallVO> create(
            @Valid @RequestBody RecallCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(recallService.create(request, requestId, user.userId()));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<RecallVO>> page(RecallQuery query) {
        return R.ok(recallService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<RecallVO> detail(@PathVariable long id) {
        return R.ok(recallService.detail(id));
    }
}
