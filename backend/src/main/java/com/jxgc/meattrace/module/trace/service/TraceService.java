package com.jxgc.meattrace.module.trace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.acceptance.entity.AcceptanceRecord;
import com.jxgc.meattrace.module.acceptance.mapper.AcceptanceRecordMapper;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.mapper.EventSnapshotMapper;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import com.jxgc.meattrace.module.process.entity.SourceRelation;
import com.jxgc.meattrace.module.process.mapper.ProcessBatchMapper;
import com.jxgc.meattrace.module.process.mapper.SourceRelationMapper;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import com.jxgc.meattrace.module.purchase.mapper.PurchaseBatchMapper;
import com.jxgc.meattrace.module.supplier.entity.Supplier;
import com.jxgc.meattrace.module.supplier.mapper.SupplierMapper;
import com.jxgc.meattrace.module.trace.dto.ConclusionVO;
import com.jxgc.meattrace.module.trace.dto.SupplierBrief;
import com.jxgc.meattrace.module.trace.dto.SummaryVO;
import com.jxgc.meattrace.module.trace.dto.TimelineItem;
import com.jxgc.meattrace.module.trace.dto.TraceResponse;
import com.jxgc.meattrace.module.trace.dto.VerifyResponse;
import com.jxgc.meattrace.module.trace.dto.VerifySummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 公开溯源组装。
 *
 * <p>支持两种批次号：
 * <ul>
 *   <li>{@code PB*}（采购批次）：时间线就是该批次自身的事件</li>
 *   <li>{@code PR*}（加工批次）：沿 {@code source_relation} 回溯上游采购批次，
 *       把上游事件一并拼进时间线——这才是"链路"，否则只是一串列表</li>
 * </ul>
 *
 * <p>注意时间线里的 {@code batchNo}：加工批次回溯时，同一条时间线上会混着多个批次的记录，
 * 页面必须能显示每个事件属于哪一批，不然用户会以为数据串了。
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);
    private static final DateTimeFormatter ISO_CN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");
    private static final ZoneOffset CN = ZoneOffset.ofHours(8);

    private static final Map<String, String> EVENT_LABELS = Map.of(
            "SUPPLIER_REG", "供应商备案",
            "PURCHASE_CREATE", "采购入库",
            "ACCEPT_RESULT", "入库验收",
            "COLD_CHAIN", "冷链登记",
            "PROCESS_CREATE", "加工投料",
            "SALE_CREATE", "销售登记",
            "DISPOSAL", "异常处置",
            "RECALL", "召回");

    private final PurchaseBatchMapper purchaseMapper;
    private final SupplierMapper supplierMapper;
    private final AcceptanceRecordMapper acceptanceMapper;
    private final ProcessBatchMapper processMapper;
    private final SourceRelationMapper sourceRelationMapper;
    private final EventSnapshotMapper snapshotMapper;
    private final VerificationService verificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceService(PurchaseBatchMapper purchaseMapper,
                        SupplierMapper supplierMapper,
                        AcceptanceRecordMapper acceptanceMapper,
                        ProcessBatchMapper processMapper,
                        SourceRelationMapper sourceRelationMapper,
                        EventSnapshotMapper snapshotMapper,
                        VerificationService verificationService) {
        this.purchaseMapper = purchaseMapper;
        this.supplierMapper = supplierMapper;
        this.acceptanceMapper = acceptanceMapper;
        this.processMapper = processMapper;
        this.sourceRelationMapper = sourceRelationMapper;
        this.snapshotMapper = snapshotMapper;
        this.verificationService = verificationService;
    }

    public TraceResponse trace(String batchNo) {
        if (batchNo != null && batchNo.startsWith("PR")) {
            return traceProcess(batchNo);
        }
        return tracePurchase(batchNo);
    }

    public VerifyResponse verify(String batchNo) {
        List<EventSnapshot> snapshots = collectSnapshots(batchNo);
        List<VerifiedEvent> verified = verificationService.verifyAll(snapshots);
        long[] counts = countByStatus(verified);
        return new VerifyResponse(
                batchNo,
                VerifyStatusResolver.overall(counts[0], counts[1], counts[2], counts[3]),
                new VerifyResponse.VerifyCounts(counts[0], counts[1], counts[2], counts[3]),
                verified.stream()
                        .map(v -> new VerifyResponse.VerifyItem(v.snapshot().getEventId(), v.status()))
                        .toList());
    }

    // ------------------------------------------------------------------ 采购批次

    private TraceResponse tracePurchase(String batchNo) {
        PurchaseBatch batch = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                .eq(PurchaseBatch::getBatchNo, batchNo));
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "批次不存在：" + batchNo);
        }
        Supplier supplier = supplierMapper.selectById(batch.getSupplierId());
        List<VerifiedEvent> verified = verificationService.verifyAll(
                snapshotsOf(List.of(batchNo)));

        SummaryVO summary = new SummaryVO(
                0,
                scale(batch.getQuantity()),
                scale(batch.getQuantity()),
                "0.000",
                blankToNull(List.of(batch.getQuarantineNo())));

        return new TraceResponse(
                batchNo,
                "PURCHASE",
                batch.getMeatType(),
                supplier == null ? null
                        : new SupplierBrief(supplier.getName(), supplier.getLicenseNo(), supplier.getStatus()),
                summary,
                conclusionOf(batch),
                toTimeline(verified),
                summarise(verified));
    }

    // ------------------------------------------------------------------ 加工批次

    private TraceResponse traceProcess(String processNo) {
        ProcessBatch process = processMapper.selectOne(Wrappers.<ProcessBatch>lambdaQuery()
                .eq(ProcessBatch::getProcessNo, processNo));
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在：" + processNo);
        }

        List<SourceRelation> relations = sourceRelationMapper.selectList(
                Wrappers.<SourceRelation>lambdaQuery()
                        .eq(SourceRelation::getProcessBatchId, process.getId()));
        List<PurchaseBatch> upstream = relations.isEmpty() ? List.of()
                : purchaseMapper.selectBatchIds(relations.stream()
                        .map(SourceRelation::getPurchaseBatchId).toList());

        List<String> batchNos = new ArrayList<>();
        batchNos.add(processNo);
        upstream.forEach(b -> batchNos.add(b.getBatchNo()));

        List<VerifiedEvent> verified = verificationService.verifyAll(snapshotsOf(batchNos));

        BigDecimal inputQty = relations.stream()
                .map(SourceRelation::getUsedQty)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal soldQty = process.getOutputQty() == null || process.getAvailableQty() == null
                ? BigDecimal.ZERO
                : process.getOutputQty().subtract(process.getAvailableQty());

        SummaryVO summary = new SummaryVO(
                relations.size(),
                scale(inputQty),
                scale(process.getOutputQty()),
                scale(soldQty),
                blankToNull(upstream.stream().map(PurchaseBatch::getQuarantineNo).toList()));

        Supplier supplier = upstream.isEmpty() ? null
                : supplierMapper.selectById(upstream.get(0).getSupplierId());
        ConclusionVO conclusion = upstream.isEmpty()
                ? new ConclusionVO(null, "该加工批次未登记上游来源", null)
                : conclusionOf(upstream.get(0));

        return new TraceResponse(
                processNo,
                "PROCESS",
                process.getMeatType(),
                supplier == null ? null
                        : new SupplierBrief(supplier.getName(), supplier.getLicenseNo(), supplier.getStatus()),
                summary,
                conclusion,
                toTimeline(verified),
                summarise(verified));
    }

    // ------------------------------------------------------------------ 公共

    /** 收集某个批次号相关的事件快照；PR 批次会连带上游采购批次的事件。 */
    private List<EventSnapshot> collectSnapshots(String batchNo) {
        if (batchNo == null || !batchNo.startsWith("PR")) {
            return snapshotsOf(List.of(batchNo));
        }
        ProcessBatch process = processMapper.selectOne(Wrappers.<ProcessBatch>lambdaQuery()
                .eq(ProcessBatch::getProcessNo, batchNo));
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在：" + batchNo);
        }
        List<SourceRelation> relations = sourceRelationMapper.selectList(
                Wrappers.<SourceRelation>lambdaQuery()
                        .eq(SourceRelation::getProcessBatchId, process.getId()));
        List<String> batchNos = new ArrayList<>();
        batchNos.add(batchNo);
        if (!relations.isEmpty()) {
            purchaseMapper.selectBatchIds(relations.stream()
                            .map(SourceRelation::getPurchaseBatchId).toList())
                    .forEach(b -> batchNos.add(b.getBatchNo()));
        }
        return snapshotsOf(batchNos);
    }

    private List<EventSnapshot> snapshotsOf(List<String> batchNos) {
        return snapshotMapper.selectList(Wrappers.<EventSnapshot>lambdaQuery()
                .in(EventSnapshot::getBatchId, batchNos)
                .orderByAsc(EventSnapshot::getBizTime)
                .orderByAsc(EventSnapshot::getId));
    }

    private List<TimelineItem> toTimeline(List<VerifiedEvent> verified) {
        List<TimelineItem> items = new ArrayList<>(verified.size());
        for (VerifiedEvent v : verified) {
            EventSnapshot s = v.snapshot();
            items.add(new TimelineItem(
                    s.getEventId(),
                    s.getEventType(),
                    EVENT_LABELS.getOrDefault(s.getEventType(), s.getEventType()),
                    formatTime(s.getBizTime()),
                    s.getDataDigest(),
                    v.status(),
                    v.receipt() == null ? null : v.receipt().getTxHash(),
                    v.receipt() == null ? null : v.receipt().getBlockNumber(),
                    s.getBatchId(),
                    parsePayload(s.getPayloadJson())));
        }
        return items;
    }

    private VerifySummary summarise(List<VerifiedEvent> verified) {
        long[] c = countByStatus(verified);
        return new VerifySummary(c[0], c[1], c[2], c[3],
                VerifyStatusResolver.overall(c[0], c[1], c[2], c[3]),
                formatTime(LocalDateTime.now()));
    }

    /** 返回 [matched, mismatched, unconfirmed, unreachable]。 */
    private long[] countByStatus(List<VerifiedEvent> verified) {
        long[] c = new long[4];
        for (VerifiedEvent v : verified) {
            switch (v.status()) {
                case VerifyStatusResolver.MATCHED -> c[0]++;
                case VerifyStatusResolver.MISMATCHED -> c[1]++;
                case VerifyStatusResolver.UNCONFIRMED -> c[2]++;
                default -> c[3]++;
            }
        }
        return c;
    }

    /** 检验结论来自验收记录，与核验状态是两码事。 */
    private ConclusionVO conclusionOf(PurchaseBatch batch) {
        AcceptanceRecord record = acceptanceMapper.selectOne(
                Wrappers.<AcceptanceRecord>lambdaQuery()
                        .eq(AcceptanceRecord::getBatchId, batch.getId())
                        .orderByDesc(AcceptanceRecord::getId)
                        .last("LIMIT 1"));
        if (record == null) {
            return new ConclusionVO(null, "该批次尚未完成入库验收", null);
        }
        StringBuilder note = new StringBuilder();
        note.append(Boolean.TRUE.equals(record.getCertComplete()) ? "检疫合格证齐全" : "检疫合格证不齐");
        note.append("，").append(Boolean.TRUE.equals(record.getAppearanceOk()) ? "感官正常" : "感官异常");
        if (record.getTemperature() != null) {
            note.append("，到货中心温度 ")
                    .append(record.getTemperature().setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append("℃");
        }
        if (AcceptanceRecord.FAIL.equals(record.getResult()) && record.getRejectReason() != null) {
            note.append("；").append(record.getRejectReason());
        }
        return new ConclusionVO(record.getResult(), note.toString(),
                "入库验收记录 " + record.getAcceptNo());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("payload 解析失败：{}", e.toString());
            return Map.of();
        }
    }

    private static List<String> blankToNull(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }

    private static String scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatTime(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return OffsetDateTime.of(time, CN).format(ISO_CN);
    }

    /** 供 TraceResponse 之外的调试使用。 */
    String debugDigest(String canonicalText) {
        return DigestUtil.sha256Hex(canonicalText);
    }
}
