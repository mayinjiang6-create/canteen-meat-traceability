package com.jxgc.meattrace.module.evidence.controller;

import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.R;
import com.jxgc.meattrace.module.auth.annotation.RequireRole;
import com.jxgc.meattrace.module.evidence.dto.EvidenceTaskVO;
import com.jxgc.meattrace.module.evidence.service.EvidenceTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 存证管理接口，口径见 docs/tech/03-接口文档.md 第 6 节。 */
@RestController
@RequestMapping("/api/evidence/tasks")
@RequireRole({"ADMIN"})
public class EvidenceController {

    private final EvidenceTaskService taskService;

    public EvidenceController(EvidenceTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public R<PageResult<EvidenceTaskVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(taskService.page(status, page, size));
    }

    @GetMapping("/{eventId}")
    public R<EvidenceTaskVO> detail(@PathVariable String eventId) {
        return R.ok(taskService.detail(eventId));
    }

    /** 手动重试：服务端先按 eventId 查链，确认未写入才重新排队（BR-14）。 */
    @PostMapping("/{eventId}/retry")
    public R<EvidenceTaskVO> retry(@PathVariable String eventId) {
        return R.ok(taskService.retry(eventId));
    }
}
