package com.jxgc.meattrace.module.evidence.dto;

/**
 * 业务响应里携带的存证信息。
 *
 * <p>{@code status=PENDING} 是**正常现象**：业务已经落库，存证异步进行中。
 * 前端不要因为看到 PENDING 就报错或提示失败。
 */
public record EvidenceInfo(String eventId, String status) {
}
