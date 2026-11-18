package com.jxgc.meattrace.module.evidence.service;

import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.mapper.EventSnapshotMapper;
import com.jxgc.meattrace.module.evidence.mapper.EvidenceTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存证快照与任务的统一入口。
 *
 * <p><b>BR-13 的落地点</b>：业务数据、事件快照、存证任务必须同生共死。
 * 这里用 {@code Propagation.MANDATORY} 强制调用方必须已开启事务——
 * 如果有人图省事在事务外调用，启动期就会报错，而不是上线后才发现"业务有记录但永远核验不了"。
 *
 * <p>摘要计算（纯 CPU，毫秒级）刻意放在事务内：保证落库的业务数据与摘要一定对得上。
 */
@Service
public class SnapshotFactory {

    private static final ZoneOffset CN = ZoneOffset.ofHours(8);

    private final EventSnapshotMapper snapshotMapper;
    private final EvidenceTaskMapper taskMapper;

    public SnapshotFactory(EventSnapshotMapper snapshotMapper, EvidenceTaskMapper taskMapper) {
        this.snapshotMapper = snapshotMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 生成并落库一条事件快照 + 一条 PENDING 任务。
     *
     * @return eventId（64 位小写 hex）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(String batchId,
                         String eventType,
                         LocalDateTime bizTime,
                         BigDecimal quantity,
                         String unit,
                         Map<String, Object> payload) {
        String eventId = DigestUtil.newEventId();
        OffsetDateTime bizTimeWithOffset = bizTime.atOffset(CN);
        String canonical = DigestUtil.canonicalText(
                eventId, batchId, eventType, bizTimeWithOffset, quantity, unit, payload);
        String digest = DigestUtil.sha256Hex(canonical);

        EventSnapshot snapshot = new EventSnapshot();
        snapshot.setEventId(eventId);
        snapshot.setBatchId(batchId);
        snapshot.setEventType(eventType);
        snapshot.setBizTime(bizTime);
        snapshot.setQuantity(quantity);
        snapshot.setUnit(unit);
        // 用规范 JSON 存，便于和 canonical_text 里的 payload 逐字对照
        snapshot.setPayloadJson(DigestUtil.toCanonicalJson(payload));
        snapshot.setCanonicalText(canonical);
        snapshot.setDataDigest(digest);
        snapshot.setSchemaVersion(DigestUtil.SCHEMA_VERSION);
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);

        EvidenceTask task = new EvidenceTask();
        task.setEventId(eventId);
        task.setStatus(EvidenceTask.PENDING);
        task.setRetryCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        return eventId;
    }
}
