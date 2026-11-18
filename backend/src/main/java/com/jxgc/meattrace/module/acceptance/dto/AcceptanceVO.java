package com.jxgc.meattrace.module.acceptance.dto;

import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import java.time.LocalDateTime;

/** 验收登记出参。 */
public record AcceptanceVO(
        Long id,
        String acceptNo,
        String batchNo,
        String batchStatus,
        LocalDateTime acceptTime,
        String inspector,
        String temperature,
        Boolean appearanceOk,
        Boolean certComplete,
        Boolean sampleRetained,
        String result,
        String rejectReason,
        EvidenceInfo evidence,
        Boolean replayed) {
}
