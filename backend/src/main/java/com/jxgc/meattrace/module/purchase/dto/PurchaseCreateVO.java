package com.jxgc.meattrace.module.purchase.dto;

import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;

/** 创建采购批次出参。 */
public record PurchaseCreateVO(
        Long id,
        String batchNo,
        String status,
        String availableQty,
        EvidenceInfo evidence,
        Boolean replayed) {
}
