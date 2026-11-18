package com.jxgc.meattrace.module.trace.controller;

import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.trace.dto.TraceResponse;
import com.jxgc.meattrace.module.trace.dto.VerifyResponse;
import com.jxgc.meattrace.module.trace.service.TraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开溯源接口——本系统的门面，**免登录**。
 *
 * <p>二维码扫出来的就是这个地址。所以它在 {@code WebConfig} 里被显式放行。
 */
@RestController
@RequestMapping("/api/public/trace")
public class PublicTraceController {

    private final TraceService traceService;

    public PublicTraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /** 全链路时间线 + 概览 + 检验结论 + 核验汇总。 */
    @GetMapping("/{batchNo}")
    public R<TraceResponse> trace(@PathVariable String batchNo) {
        return R.ok(traceService.trace(batchNo));
    }

    /** 仅核验结论，供页面轮询刷新。 */
    @GetMapping("/{batchNo}/verify")
    public R<VerifyResponse> verify(@PathVariable String batchNo) {
        return R.ok(traceService.verify(batchNo));
    }
}
