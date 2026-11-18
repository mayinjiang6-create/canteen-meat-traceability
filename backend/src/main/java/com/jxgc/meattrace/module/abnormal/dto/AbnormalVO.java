package com.jxgc.meattrace.module.abnormal.dto;

import com.jxgc.meattrace.module.abnormal.entity.AbnormalEvent;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import java.time.LocalDateTime;

/**
 * 异常事件出参。
 *
 * <p>把采购批次号与加工批次号都带上（可能只有一个）：
 * 页面要能一眼看出"这条异常是到货时就有的，还是成品阶段才发现的"。
 */
public record AbnormalVO(
        Long id,
        String abnormalNo,
        String purchaseBatchNo,
        String processNo,
        String abnormalType,
        String level,
        String description,
        String action,
        String handler,
        String conclusion,
        String status,
        LocalDateTime occurredAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        EvidenceInfo evidence) {

    public static AbnormalVO of(AbnormalEvent event, String purchaseBatchNo, String processNo,
                                EvidenceInfo evidence) {
        return new AbnormalVO(
                event.getId(),
                event.getAbnormalNo(),
                purchaseBatchNo,
                processNo,
                event.getAbnormalType(),
                event.getLevel(),
                event.getDescription(),
                event.getAction(),
                event.getHandler(),
                event.getConclusion(),
                event.getStatus(),
                event.getOccurredAt(),
                event.getClosedAt(),
                event.getCreatedAt(),
                evidence);
    }
}
