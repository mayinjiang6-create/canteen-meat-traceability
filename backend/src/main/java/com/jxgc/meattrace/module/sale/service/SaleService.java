package com.jxgc.meattrace.module.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import com.jxgc.meattrace.module.process.mapper.ProcessBatchMapper;
import com.jxgc.meattrace.module.sale.dto.SaleCreateRequest;
import com.jxgc.meattrace.module.sale.dto.SaleCreateVO;
import com.jxgc.meattrace.module.sale.dto.SaleQuery;
import com.jxgc.meattrace.module.sale.dto.SaleVO;
import com.jxgc.meattrace.module.sale.entity.SaleRecord;
import com.jxgc.meattrace.module.sale.mapper.SaleRecordMapper;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售登记。
 *
 * <p>这个模块只有一条真正难的事：<b>把"还剩多少"的判断交给数据库</b>。
 * 食堂中午 11:30 的高峰，两个窗口同时卖同一批净菜是完全正常的。
 * 如果先 {@code SELECT available_qty} 再判断再 {@code UPDATE}，
 * 两笔 5 kg 的销售会同时读到"还剩 5.000"，都判定通过，最后卖出 10 kg、
 * 余量变成 -5.000 被 {@code ck_process_avail} 拒绝（或者更糟，约束没生效时直接卖超）。
 *
 * <p>所以这里的顺序是：
 * <ol>
 *   <li><b>预校验</b>（状态 + 余量）：只为给窗口人员一句能看懂的话，<b>不承担正确性责任</b>；</li>
 *   <li><b>条件扣减</b>：{@code WHERE status='ON_SALE' AND available_qty >= #{qty}}，
 *       受影响行数才是权威结论；</li>
 *   <li>扣动之后再判断是否售罄，{@code SOLD_OUT} 由数据库的余量=0 触达。</li>
 * </ol>
 *
 * <p>BR-13：销售记录、批次余量、事件快照、存证任务同事务提交。
 */
@Service
public class SaleService {

    private static final String ENDPOINT = "POST /api/sales";
    private static final String EVENT_TYPE = "SALE_CREATE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final SaleRecordMapper saleMapper;
    private final ProcessBatchMapper processMapper;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;
    private final SequenceService sequences;

    public SaleService(SaleRecordMapper saleMapper,
                       ProcessBatchMapper processMapper,
                       SnapshotFactory snapshotFactory,
                       IdempotencyService idempotencyService,
                       SequenceService sequences) {
        this.saleMapper = saleMapper;
        this.processMapper = processMapper;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
        this.sequences = sequences;
    }

    // ------------------------------------------------------------------ 登记

    @Transactional
    public SaleCreateVO create(SaleCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                SaleRecord existing = saleMapper.selectById(record.getBizId());
                if (existing != null) {
                    return replayVO(existing);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        ProcessBatch process = requireProcess(request.processBatchNo());

        // ① 预校验：给人看的提示，失败时不产生任何写入
        SaleRules.checkPositive(request.quantity());
        SaleRules.checkSellable(process.getProcessNo(), process.getStatus());
        SaleRules.checkRemain(process.getProcessNo(), request.quantity(), process.getAvailableQty());

        // ② 权威判定：条件 UPDATE。0 行 = 状态变了或余量被别人抢走
        if (processMapper.deductAvailable(process.getId(), request.quantity()) == 0) {
            explainDeductFailure(process.getId(), request.quantity());
        }
        // ③ 卖完就售罄（ON_SALE → SOLD_OUT），由数据库的 available_qty = 0 触达
        processMapper.soldOutIfEmpty(process.getId());

        LocalDateTime saleTime = TimeFormats.parseOrNow(request.saleTime());
        SaleRecord record = new SaleRecord();
        record.setSaleNo(nextSaleNo());
        record.setProcessBatchId(process.getId());
        record.setChannel(request.channel());
        record.setQuantity(request.quantity());
        record.setUnitPrice(request.unitPrice());
        record.setSaleTime(saleTime);
        record.setOperator(request.operator());
        record.setStatus(SaleRecord.NORMAL);
        record.setCreatedAt(LocalDateTime.now());
        saleMapper.insert(record);

        // ④ SALE_CREATE：批次号挂加工批次，公开页顺着追溯码就能看到"这批肉卖到哪个窗口"
        String eventId = snapshotFactory.record(
                process.getProcessNo(),
                EVENT_TYPE,
                saleTime,
                request.quantity(),
                process.getUnit(),
                Payloads.of(
                        "saleNo", record.getSaleNo(),
                        "processNo", process.getProcessNo(),
                        "quantity", request.quantity(),
                        "channel", request.channel(),
                        "unitPrice", request.unitPrice(),
                        "operator", request.operator()));

        idempotencyService.bind(requestId, record.getId(), record.getSaleNo());

        // ⑤ 重新读一次：入参里的余量是扣减前的旧值，售罄后状态也已变成 SOLD_OUT
        ProcessBatch after = processMapper.selectById(process.getId());
        SaleVO sale = SaleVO.of(record, process.getProcessNo(), process.getMeatType());
        return SaleCreateVO.of(sale,
                after == null ? process.getStatus() : after.getStatus(),
                after == null ? process.getAvailableQty() : after.getAvailableQty(),
                new EvidenceInfo(eventId, EvidenceTask.PENDING), false);
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<SaleVO> page(SaleQuery query) {
        Long processBatchId = query.getProcessBatchId();
        if (processBatchId == null && query.getProcessBatchNo() != null
                && !query.getProcessBatchNo().isBlank()) {
            processBatchId = requireProcess(query.getProcessBatchNo()).getId();
        }
        final Long resolvedId = processBatchId;

        var wrapper = Wrappers.<SaleRecord>lambdaQuery()
                .eq(resolvedId != null, SaleRecord::getProcessBatchId, resolvedId)
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        SaleRecord::getStatus, query.getStatus())
                .like(query.getChannel() != null && !query.getChannel().isBlank(),
                        SaleRecord::getChannel, query.getChannel())
                .orderByDesc(SaleRecord::getId);
        Page<SaleRecord> page = saleMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> ids = page.getRecords().stream()
                .map(SaleRecord::getProcessBatchId).distinct().toList();
        Map<Long, ProcessBatch> batches = ids.isEmpty() ? Map.of()
                : processMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(ProcessBatch::getId, b -> b, (a, b) -> a));

        return PageResult.of(page, r -> {
            ProcessBatch batch = batches.get(r.getProcessBatchId());
            return SaleVO.of(r, batch == null ? null : batch.getProcessNo(),
                    batch == null ? null : batch.getMeatType());
        });
    }

    public SaleVO detail(long id) {
        SaleRecord record = saleMapper.selectById(id);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "销售记录不存在");
        }
        ProcessBatch batch = processMapper.selectById(record.getProcessBatchId());
        return SaleVO.of(record, batch == null ? null : batch.getProcessNo(),
                batch == null ? null : batch.getMeatType());
    }

    // ------------------------------------------------------------------ 内部

    private ProcessBatch requireProcess(String processBatchNo) {
        ProcessBatch process = processMapper.selectOne(Wrappers.<ProcessBatch>lambdaQuery()
                .eq(ProcessBatch::getProcessNo, processBatchNo));
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在：" + processBatchNo);
        }
        return process;
    }

    /**
     * 扣减失败后判定原因并抛出对应错误码。
     *
     * <p>顺序不能反：先看状态（召回/冻结是"业务不允许"，与余量无关），
     * 状态正常再当前读余量（"被别人抢光了"）。如果把顺序反过来，
     * 一个刚被召回的批次会提示"余量不足"，把责任推给库存，掩盖真正的原因。
     */
    private void explainDeductFailure(Long processBatchId, BigDecimal quantity) {
        ProcessBatch latest = processMapper.selectById(processBatchId);
        if (latest == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在");
        }
        SaleRules.checkSellable(latest.getProcessNo(), latest.getStatus());
        BigDecimal current = processMapper.lockAvailable(processBatchId);
        SaleRules.checkRemain(latest.getProcessNo(), quantity, current);
        // 理论上不可达：扣减失败但状态正常、余量也够（例如 CHECK 约束兜底）
        throw new BizException(ErrorCode.QTY_NOT_ENOUGH, "可售余量不足，请刷新后重试");
    }

    /** 幂等重放：返回首次销售记录，evidence 为空（首次请求的存证任务早已入队）。 */
    private SaleCreateVO replayVO(SaleRecord record) {
        ProcessBatch batch = processMapper.selectById(record.getProcessBatchId());
        SaleVO sale = SaleVO.of(record, batch == null ? null : batch.getProcessNo(),
                batch == null ? null : batch.getMeatType());
        return SaleCreateVO.of(sale,
                batch == null ? null : batch.getStatus(),
                batch == null ? null : batch.getAvailableQty(),
                null, true);
    }

    /** SL + yyyyMMdd + 3 位当日序号。 */
    private String nextSaleNo() {
        String day = LocalDate.now().format(DAY);
        return sequences.next("SALE_NO:" + day, "SL", day, 3, this::currentSaleSeq);
    }

    /** 种子：业务表里当日已有最大序号（发号器首次创建时用）。 */
    private long currentSaleSeq() {
        String prefix = "SL" + LocalDate.now().format(DAY);
        SaleRecord last = saleMapper.selectOne(Wrappers.<SaleRecord>lambdaQuery()
                .likeRight(SaleRecord::getSaleNo, prefix)
                .orderByDesc(SaleRecord::getSaleNo)
                .last("LIMIT 1"));
        if (last == null || last.getSaleNo() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(last.getSaleNo().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
