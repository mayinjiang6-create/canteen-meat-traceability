package com.jxgc.meattrace.module.trace.dto;

/** 只返回核验结论，供页面轮询刷新用（不重复拉全量时间线）。 */
public record VerifyResponse(
        String batchNo,
        String overall,
        VerifyCounts counts,
        java.util.List<VerifyItem> events) {

    public record VerifyCounts(long matched, long mismatched, long unconfirmed, long unreachable) {
    }

    public record VerifyItem(String eventId, String verifyStatus) {
    }
}
