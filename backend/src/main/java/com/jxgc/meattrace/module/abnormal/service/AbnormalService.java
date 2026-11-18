package com.jxgc.meattrace.module.abnormal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalCreateRequest;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalDisposeRequest;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalQuery;
import com.jxgc.meattrace.module.abnormal.dto.AbnormalVO;
import com.jxgc.meattrace.module.abnormal.entity.AbnormalEvent;
import com.jxgc.meattrace.module.abnormal.mapper.AbnormalEventMapper;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import com.jxgc.meattrace.module.process.mapper.ProcessBatchMapper;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import com.jxgc.meattrace.module.purchase.mapper.PurchaseBatchMapper;
import com.jxgc.meattrace.module.sequence.service.SequenceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异常登记与处置。
 *
 * <p><b>为什么登记异常要顺手冻结批次</b>：异常登记不是"填个表单留档"，
 * 它的目的是让这批肉立刻停售。如果只写一条异常记录而不改批次状态，
 * 系统里就同时存在"有未处置异常"和"正在售卖"两个事实，
 * 而窗口端只认批次状态——异常记录等于白写。
 *
 * <p><b>为什么处置要显式传 resumeSale</b>：处置结论可能是"整批销毁"。
 * 若按"有结论就恢复"的默认逻辑，销毁完的批次会被重新上架。
 */
@Service
public class AbnormalService {

    private static final String ENDPOINT = "POST /api/abnormal-events";
    private static final String EVENT_TYPE = "DISPOSAL";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final AbnormalEventMapper abnormalMapper;
    private final ProcessBatchMapper processMapper;
    private final PurchaseBatchMapper purchaseMapper;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;
    private final SequenceService sequences;

    public AbnormalService(AbnormalEventMapper abnormalMapper,
                           ProcessBatchMapper processMapper,
                           PurchaseBatchMapper purchaseMapper,
                           SnapshotFactory snapshotFactory,
                           IdempotencyService idempotencyService,
                           SequenceService sequences) {
        this.abnormalMapper = abnormalMapper;
        this.processMapper = processMapper;
        this.purchaseMapper = purchaseMapper;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
        this.sequences = sequences;
    }

    // ------------------------------------------------------------------ 登记

    @Transactional
    public AbnormalVO create(AbnormalCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                AbnormalEvent existing = abnormalMapper.selectById(record.getBizId());
                if (existing != null) {
                    return toVO(existing, null);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        boolean hasPurchase = request.batchNo() != null && !request.batchNo().isBlank();
        boolean hasProcess = request.processBatchNo() != null && !request.processBatchNo().isBlank();
        AbnormalRules.checkTarget(hasPurchase, hasProcess);
        AbnormalRules.checkType(request.abnormalType());
        AbnormalRules.checkLevel(request.level());

        ProcessBatch process = hasProcess ? requireProcess(request.processBatchNo()) : null;
        PurchaseBatch purchase = hasPurchase ? requirePurchase(request.batchNo()) : null;

        LocalDateTime occurredAt = TimeFormats.parseOrNow(request.occurredAt());
        AbnormalEvent event = new AbnormalEvent();
        event.setAbnormalNo(nextAbnormalNo());
        event.setBatchId(purchase == null ? null : purchase.getId());
        event.setProcessBatchId(process == null ? null : process.getId());
        event.setAbnormalType(request.abnormalType());
        event.setLevel(request.level() == null || request.level().isBlank() ? "MEDIUM" : request.level());
        event.setDescription(request.description());
        event.setHandler(request.handler());
        event.setStatus(AbnormalEvent.OPEN);
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        abnormalMapper.insert(event);

        // 冻结：只有正在卖的批次需要停；条件 UPDATE 保证并发下也只成功一次
        if (process != null && AbnormalRules.shouldFreeze(process.getStatus())) {
            processMapper.update(null, Wrappers.<ProcessBatch>lambdaUpdate()
                    .set(ProcessBatch::getStatus, ProcessBatch.ABNORMAL)
                    .set(ProcessBatch::getUpdatedAt, LocalDateTime.now())
                    .eq(ProcessBatch::getId, process.getId())
                    .eq(ProcessBatch::getStatus, ProcessBatch.ON_SALE));
        }

        // DISPOSAL 事件（stage=REPORTED）：异常登记本身也是需要留痕的事实
        String eventId = snapshotFactory.record(
                process != null ? process.getProcessNo() : purchase.getBatchNo(),
                EVENT_TYPE,
                occurredAt,
                process != null ? process.getOutputQty() : purchase.getQuantity(),
                process != null ? process.getUnit() : purchase.getUnit(),
                Payloads.of(
                        "abnormalNo", event.getAbnormalNo(),
                        "stage", "REPORTED",
                        "abnormalType", event.getAbnormalType(),
                        "level", event.getLevel(),
                        "description", event.getDescription(),
                        "handler", event.getHandler()));

        idempotencyService.bind(requestId, event.getId(), event.getAbnormalNo());

        return toVO(event, new EvidenceInfo(eventId, EvidenceTask.PENDING));
    }

    // ------------------------------------------------------------------ 处置

    @Transactional
    public AbnormalVO dispose(long id, AbnormalDisposeRequest request) {
        AbnormalEvent event = abnormalMapper.selectById(id);
        if (event == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "异常事件不存在");
        }
        AbnormalRules.checkDisposable(event.getStatus());

        // 先恢复销售，再落结论：若批次已不是 ABNORMAL，宁可整笔回滚也不留一条"已处置但没恢复"的记录
        ProcessBatch process = event.getProcessBatchId() == null ? null
                : processMapper.selectById(event.getProcessBatchId());
        if (Boolean.TRUE.equals(request.resumeSale())) {
            if (process == null) {
                throw new BizException(ErrorCode.STATUS_NOT_ALLOWED, "该异常未关联加工批次，无法恢复销售");
            }
            int rows = processMapper.update(null, Wrappers.<ProcessBatch>lambdaUpdate()
                    .set(ProcessBatch::getStatus, ProcessBatch.ON_SALE)
                    .set(ProcessBatch::getUpdatedAt, LocalDateTime.now())
                    .eq(ProcessBatch::getId, process.getId())
                    .eq(ProcessBatch::getStatus, ProcessBatch.ABNORMAL));
            if (rows == 0) {
                ProcessBatch latest = processMapper.selectById(process.getId());
                throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                        "加工批次 " + process.getProcessNo() + " 当前状态为 "
                                + (latest == null ? "未知" : latest.getStatus()) + "，不能恢复销售");
            }
            process.setStatus(ProcessBatch.ON_SALE);
        }

        LocalDateTime disposeTime = TimeFormats.parseOrNow(request.disposeTime());
        AbnormalEvent update = new AbnormalEvent();
        update.setId(event.getId());
        update.setAction(request.action());
        update.setHandler(request.handler() == null ? event.getHandler() : request.handler());
        update.setConclusion(request.conclusion());
        update.setStatus(AbnormalEvent.CLOSED);
        update.setClosedAt(disposeTime);
        update.setUpdatedAt(LocalDateTime.now());
        abnormalMapper.updateById(update);
        event.setAction(update.getAction());
        event.setHandler(update.getHandler());
        event.setConclusion(update.getConclusion());
        event.setStatus(AbnormalEvent.CLOSED);
        event.setClosedAt(disposeTime);

        PurchaseBatch purchase = process == null ? requirePurchaseById(event.getBatchId()) : null;
        String batchNo = process != null ? process.getProcessNo() : purchase.getBatchNo();
        BigDecimal quantity = process != null ? process.getOutputQty() : purchase.getQuantity();
        String unit = process != null ? process.getUnit() : purchase.getUnit();

        String eventId = snapshotFactory.record(
                batchNo,
                EVENT_TYPE,
                disposeTime,
                quantity,
                unit,
                Payloads.of(
                        "abnormalNo", event.getAbnormalNo(),
                        "stage", "CLOSED",
                        "abnormalType", event.getAbnormalType(),
                        "action", event.getAction(),
                        "handler", event.getHandler(),
                        "conclusion", event.getConclusion(),
                        "resumed", Boolean.TRUE.equals(request.resumeSale())));

        return toVO(event, new EvidenceInfo(eventId, EvidenceTask.PENDING));
    }

    // ------------------------------------------------------------------ 供冷链调用

    /**
     * 冷链超限时自动开一条异常工单（不写 DISPOSAL 事件）。
     *
     * <p>为什么不再写一条 DISPOSAL：超限这件事本身已经由 {@code COLD_CHAIN} 事件存证了，
     * 再写一条只是同一事实的复述。这条异常记录的作用是"待办事项"——
     * 等有人真的处置了，那时的 DISPOSAL 才是新事实。
     *
     * @return 异常编号（AB...）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String raiseForColdChain(Long purchaseBatchId, String batchNo, String description,
                                    LocalDateTime occurredAt, String level) {
        AbnormalEvent event = new AbnormalEvent();
        event.setAbnormalNo(nextAbnormalNo());
        event.setBatchId(purchaseBatchId);
        event.setAbnormalType(AbnormalEvent.TEMPERATURE_EXCEEDED);
        event.setLevel(level);
        event.setDescription(description);
        event.setHandler("系统自动");
        event.setStatus(AbnormalEvent.OPEN);
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        abnormalMapper.insert(event);
        return event.getAbnormalNo();
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<AbnormalVO> page(AbnormalQuery query) {
        Long processId = null;
        if (query.getProcessBatchNo() != null && !query.getProcessBatchNo().isBlank()) {
            processId = requireProcess(query.getProcessBatchNo()).getId();
        }
        Long purchaseId = null;
        if (query.getBatchNo() != null && !query.getBatchNo().isBlank()) {
            purchaseId = requirePurchase(query.getBatchNo()).getId();
        }
        final Long processFilter = processId;
        final Long purchaseFilter = purchaseId;

        var wrapper = Wrappers.<AbnormalEvent>lambdaQuery()
                .eq(processFilter != null, AbnormalEvent::getProcessBatchId, processFilter)
                .eq(purchaseFilter != null, AbnormalEvent::getBatchId, purchaseFilter)
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        AbnormalEvent::getStatus, query.getStatus())
                .eq(query.getAbnormalType() != null && !query.getAbnormalType().isBlank(),
                        AbnormalEvent::getAbnormalType, query.getAbnormalType())
                .orderByDesc(AbnormalEvent::getId);
        Page<AbnormalEvent> page = abnormalMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> purchaseIds = page.getRecords().stream()
                .map(AbnormalEvent::getBatchId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> processIds = page.getRecords().stream()
                .map(AbnormalEvent::getProcessBatchId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, PurchaseBatch> purchases = purchaseIds.isEmpty() ? Map.of()
                : purchaseMapper.selectBatchIds(purchaseIds).stream()
                        .collect(Collectors.toMap(PurchaseBatch::getId, b -> b, (a, b) -> a));
        Map<Long, ProcessBatch> processes = processIds.isEmpty() ? Map.of()
                : processMapper.selectBatchIds(processIds).stream()
                        .collect(Collectors.toMap(ProcessBatch::getId, b -> b, (a, b) -> a));

        return PageResult.of(page, e -> {
            // 注意：异常可能只挂加工批次，此时 batchId 为 null。
            // 不能用 map.get(null) 直接查——Map.of() 生成的不可变集合对 null 键直接抛 NPE
            // （2026-11-18 自检踩到过：/api/abnormal-events 500，堆栈指向 ImmutableCollections.MapN.get）。
            PurchaseBatch p = e.getBatchId() == null ? null : purchases.get(e.getBatchId());
            ProcessBatch pr = e.getProcessBatchId() == null ? null : processes.get(e.getProcessBatchId());
            return AbnormalVO.of(e, p == null ? null : p.getBatchNo(),
                    pr == null ? null : pr.getProcessNo(), null);
        });
    }

    public AbnormalVO detail(long id) {
        AbnormalEvent event = abnormalMapper.selectById(id);
        if (event == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "异常事件不存在");
        }
        return toVO(event, null);
    }

    // ------------------------------------------------------------------ 内部

    private ProcessBatch requireProcess(String processNo) {
        ProcessBatch process = processMapper.selectOne(Wrappers.<ProcessBatch>lambdaQuery()
                .eq(ProcessBatch::getProcessNo, processNo));
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在：" + processNo);
        }
        return process;
    }

    private PurchaseBatch requirePurchase(String batchNo) {
        PurchaseBatch batch = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                .eq(PurchaseBatch::getBatchNo, batchNo));
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在：" + batchNo);
        }
        return batch;
    }

    private PurchaseBatch requirePurchaseById(Long id) {
        PurchaseBatch batch = id == null ? null : purchaseMapper.selectById(id);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "异常关联的采购批次已不存在");
        }
        return batch;
    }

    private AbnormalVO toVO(AbnormalEvent event, EvidenceInfo evidence) {
        PurchaseBatch purchase = event.getBatchId() == null ? null
                : purchaseMapper.selectById(event.getBatchId());
        ProcessBatch process = event.getProcessBatchId() == null ? null
                : processMapper.selectById(event.getProcessBatchId());
        return AbnormalVO.of(event,
                purchase == null ? null : purchase.getBatchNo(),
                process == null ? null : process.getProcessNo(),
                evidence);
    }

    /** AB + yyyyMMdd + 3 位当日序号。 */
    private String nextAbnormalNo() {
        String day = LocalDate.now().format(DAY);
        return sequences.next("ABNORMAL_NO:" + day, "AB", day, 3, this::currentAbnormalSeq);
    }

    private long currentAbnormalSeq() {
        String prefix = "AB" + LocalDate.now().format(DAY);
        AbnormalEvent last = abnormalMapper.selectOne(Wrappers.<AbnormalEvent>lambdaQuery()
                .likeRight(AbnormalEvent::getAbnormalNo, prefix)
                .orderByDesc(AbnormalEvent::getAbnormalNo)
                .last("LIMIT 1"));
        if (last == null || last.getAbnormalNo() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(last.getAbnormalNo().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
