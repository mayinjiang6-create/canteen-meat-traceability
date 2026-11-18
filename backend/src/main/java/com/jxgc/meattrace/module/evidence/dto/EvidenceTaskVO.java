package com.jxgc.meattrace.module.evidence.dto;

import java.time.LocalDateTime;

/** 存证任务出参。 */
public record EvidenceTaskVO(
        String eventId,
        String batchId,
        String eventType,
        String status,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        String lastError,
        String txHash,
        Long blockNumber,
        LocalDateTime receiptTime) {
}
