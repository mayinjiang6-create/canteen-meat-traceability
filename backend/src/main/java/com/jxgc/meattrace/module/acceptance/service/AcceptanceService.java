package com.jxgc.meattrace.module.acceptance.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceCreateRequest;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceQuery;
import com.jxgc.meattrace.module.acceptance.dto.AcceptanceVO;
import com.jxgc.meattrace.module.acceptance.entity.AcceptanceRecord;
import com.jxgc.meattrace.module.acceptance.mapper.AcceptanceRecordMapper;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import com.jxgc.meattrace.module.purchase.mapper.PurchaseBatchMapper;
import com.jxgc.meattrace.module.purchase.service.PurchaseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入库验收登记。
 *
 * <p><b>BR-13 的典型场景</b>：验收记录、批次状态变更、事件快照、存证任务
 * 必须在同一个事务里。任何一步失败全部回滚——否则会出现"验收记录有了但批次还是待验收"
 * 或者"业务落库了但永远核验不了"。
 *
 * <p><b>防重复验收</b>：用带条件的 UPDATE（{@code WHERE status='PENDING_ACCEPT'}），
 * 受影响行数为 0 说明已被别人验收过，直接返回 40902。不需要额外加锁。
 */
@Service
public class AcceptanceService {

    private static final String ENDPOINT = "POST /api/acceptances";
    private static final String EVENT_TYPE = "ACCEPT_RESULT";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final AcceptanceRecordMapper acceptanceMapper;
    private final PurchaseBatchMapper purchaseMapper;
    private final PurchaseService purchaseService;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;

    public AcceptanceService(AcceptanceRecordMapper acceptanceMapper,
                             PurchaseBatchMapper purchaseMapper,
                             PurchaseService purchaseService,
                             SnapshotFactory snapshotFactory,
                             IdempotencyService idempotencyService) {
        this.acceptanceMapper = acceptanceMapper;
        this.purchaseMapper = purchaseMapper;
        this.purchaseService = purchaseService;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public AcceptanceVO create(AcceptanceCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                AcceptanceRecord existing = acceptanceMapper.selectById(record.getBizId());
                if (existing != null) {
                    PurchaseBatch batch = purchaseMapper.selectById(existing.getBatchId());
                    return toVO(existing, batch, null, true);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        PurchaseBatch batch = purchaseMapper.selectById(request.batchId());
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在");
        }
        boolean pass = AcceptanceRecord.PASS.equals(request.result());
        if (!pass && (request.rejectReason() == null || request.rejectReason().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "验收不合格时 rejectReason 必填");
        }

        // 先抢状态：受影响行数为 0 说明已被验收过或被扣光了
        String newStatus = pass ? PurchaseBatch.PASSED : PurchaseBatch.REJECTED;
        var updateWrapper = Wrappers.<PurchaseBatch>lambdaUpdate()
                .set(PurchaseBatch::getStatus, newStatus)
                .set(PurchaseBatch::getUpdatedAt, LocalDateTime.now())
                .eq(PurchaseBatch::getId, batch.getId())
                .eq(PurchaseBatch::getStatus, PurchaseBatch.PENDING_ACCEPT);
        if (!pass) {
            // 拒收后可用量归零：它不参与后续加工，也不参与采购量守恒校验
            updateWrapper.set(PurchaseBatch::getAvailableQty, BigDecimal.ZERO);
        }
        if (purchaseMapper.update(null, updateWrapper) == 0) {
            PurchaseBatch latest = purchaseMapper.selectById(batch.getId());
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "批次当前状态为 " + (latest == null ? "未知" : latest.getStatus()) + "，不能验收");
        }

        LocalDateTime acceptTime = TimeFormats.parseOrNow(request.acceptTime());
        AcceptanceRecord record = new AcceptanceRecord();
        record.setAcceptNo(nextAcceptNo());
        record.setBatchId(batch.getId());
        record.setAcceptTime(acceptTime);
        record.setInspector(request.inspector());
        record.setTemperature(request.temperature());
        record.setAppearanceOk(request.appearanceOk() == null || request.appearanceOk());
        record.setCertComplete(request.certComplete() == null || request.certComplete());
        record.setSampleRetained(request.sampleRetained() != null && request.sampleRetained());
        record.setResult(request.result());
        record.setRejectReason(request.rejectReason());
        record.setCreatedAt(LocalDateTime.now());
        acceptanceMapper.insert(record);

        // payload 结构与 TECH-04 的金标准保持一致，便于人工比对
        String eventId = snapshotFactory.record(
                batch.getBatchNo(),
                EVENT_TYPE,
                acceptTime,
                batch.getQuantity(),
                batch.getUnit(),
                Payloads.of(
                        "acceptNo", record.getAcceptNo(),
                        "certComplete", record.getCertComplete(),
                        "inspector", record.getInspector(),
                        "result", record.getResult(),
                        "temperature", record.getTemperature()));

        batch.setStatus(newStatus);
        idempotencyService.bind(requestId, record.getId(), record.getAcceptNo());

        return toVO(record, batch, new EvidenceInfo(eventId, EvidenceTask.PENDING), false);
    }

    public PageResult<AcceptanceVO> page(AcceptanceQuery query) {
        Long batchId = query.getBatchId();
        if (batchId == null && query.getBatchNo() != null && !query.getBatchNo().isBlank()) {
            PurchaseBatch batch = purchaseService.requireByNo(query.getBatchNo());
            batchId = batch.getId();
        }
        final Long resolvedBatchId = batchId;
        var wrapper = Wrappers.<AcceptanceRecord>lambdaQuery()
                .eq(resolvedBatchId != null, AcceptanceRecord::getBatchId, resolvedBatchId)
                .eq(query.getResult() != null && !query.getResult().isBlank(),
                        AcceptanceRecord::getResult, query.getResult())
                .orderByDesc(AcceptanceRecord::getId);
        Page<AcceptanceRecord> page = acceptanceMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> ids = page.getRecords().stream()
                .map(AcceptanceRecord::getBatchId).distinct().toList();
        Map<Long, PurchaseBatch> batches = ids.isEmpty() ? Map.of()
                : purchaseMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(PurchaseBatch::getId, b -> b, (a, b) -> a));

        return PageResult.of(page, r -> toVO(r, batches.get(r.getBatchId()), null, null));
    }

    private AcceptanceVO toVO(AcceptanceRecord record, PurchaseBatch batch,
                              EvidenceInfo evidence, Boolean replayed) {
        return new AcceptanceVO(
                record.getId(),
                record.getAcceptNo(),
                batch == null ? null : batch.getBatchNo(),
                batch == null ? null : batch.getStatus(),
                record.getAcceptTime(),
                record.getInspector(),
                record.getTemperature() == null ? null
                        : record.getTemperature().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                record.getAppearanceOk(),
                record.getCertComplete(),
                record.getSampleRetained(),
                record.getResult(),
                record.getRejectReason(),
                evidence,
                replayed);
    }

    /** AC + yyyyMMdd + 3 位当日序号。 */
    private String nextAcceptNo() {
        String prefix = "AC" + LocalDate.now().format(DAY);
        AcceptanceRecord last = acceptanceMapper.selectOne(Wrappers.<AcceptanceRecord>lambdaQuery()
                .likeRight(AcceptanceRecord::getAcceptNo, prefix)
                .orderByDesc(AcceptanceRecord::getAcceptNo)
                .last("LIMIT 1"));
        int seq = 1;
        if (last != null && last.getAcceptNo() != null) {
            String tail = last.getAcceptNo().substring(prefix.length());
            try {
                seq = Integer.parseInt(tail) + 1;
            } catch (NumberFormatException ignored) {
                seq = 1;
            }
        }
        return prefix + String.format("%03d", seq);
    }
}
