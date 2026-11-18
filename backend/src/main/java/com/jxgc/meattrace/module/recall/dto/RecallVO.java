package com.jxgc.meattrace.module.recall.dto;

import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.recall.entity.RecallRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 召回落库结果。
 *
 * <p>{@code affectedSales} 与 {@code recalledQty} 是这个接口最该被看的两个数：
 * 前者告诉食堂"要去哪几个窗口追"，后者告诉采购"总共追回多少 kg"。
 */
public record RecallVO(
        Long id,
        String recallNo,
        String processNo,
        String meatType,
        String reason,
        String scope,
        Integer affectedSales,
        String recalledQty,
        String operator,
        LocalDateTime recallTime,
        String status,
        String processStatus,
        EvidenceInfo evidence,
        Boolean replayed) {

    public static RecallVO of(RecallRecord record, String processNo, String meatType,
                              String processStatus, EvidenceInfo evidence, Boolean replayed) {
        return new RecallVO(
                record.getId(),
                record.getRecallNo(),
                processNo,
                meatType,
                record.getReason(),
                record.getScope(),
                record.getAffectedSales(),
                record.getRecalledQty() == null ? null
                        : record.getRecalledQty().setScale(3, RoundingMode.HALF_UP).toPlainString(),
                record.getOperator(),
                record.getRecallTime(),
                record.getStatus(),
                processStatus,
                evidence,
                replayed);
    }
}
