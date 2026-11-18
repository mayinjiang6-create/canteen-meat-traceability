package com.jxgc.meattrace.module.trace.dto;

import java.util.List;

/**
 * 公开溯源全量响应，口径见 docs/tech/03-接口文档.md 5.1。
 *
 * <p><b>实现约束（BR-15）</b>：{@code conclusion}（检验结论）与 {@code verifySummary}
 * （核验状态）是两个独立字段，页面必须分两块渲染，任何情况下都不许拼成一句话。
 */
public record TraceResponse(
        String batchNo,
        String batchType,
        String meatType,
        SupplierBrief supplier,
        SummaryVO summary,
        ConclusionVO conclusion,
        List<TimelineItem> timeline,
        VerifySummary verifySummary) {
}
