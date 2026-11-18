package com.jxgc.meattrace.module.abnormal.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalCreateRequest;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalDisposeRequest;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalQuery;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalVO;
import com.jxgc.meattrace.module.abnormal.service.AbnormalService;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.auth.interceptor.AuthInterceptor;
import com.jxgc.meattrace.module.auth.service.JwtTokenService.TokenPayload;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 异常登记与处置接口，口径见 docs/tech/03-接口文档.md 4.7。 */
@RestController
@RequestMapping("/api/abnormal-events")
public class AbnormalController {

    private final AbnormalService abnormalService;

    public AbnormalController(AbnormalService abnormalService) {
        this.abnormalService = abnormalService;
    }

    @PostMapping
    @RequireRole({"ADMIN"})
    public R<AbnormalVO> create(
            @Valid @RequestBody AbnormalCreateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestAttribute(AuthInterceptor.ATTR_USER) TokenPayload user) {
        return R.ok(abnormalService.create(request, requestId, user.userId()));
    }

    @PostMapping("/{id}/dispose")
    @RequireRole({"ADMIN"})
    public R<AbnormalVO> dispose(@PathVariable long id,
                                 @Valid @RequestBody AbnormalDisposeRequest request) {
        return R.ok(abnormalService.dispose(id, request));
    }

    @GetMapping
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<PageResult<AbnormalVO>> page(AbnormalQuery query) {
        return R.ok(abnormalService.page(query));
    }

    @GetMapping("/{id}")
    @RequireRole({"ADMIN", "VIEWER", "SUPPLIER"})
    public R<AbnormalVO> detail(@PathVariable long id) {
        return R.ok(abnormalService.detail(id));
    }
}
