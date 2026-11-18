package com.jxgc.meattrace.module.recall.service;

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
import com.jxgc.meattrace.module.recall.dto.RecallCreateRequest;
import com.jxgc.meattrace.module.recall.dto.RecallQuery;
import com.jxgc.meattrace.module.recall.dto.RecallVO;
import com.jxgc.meattrace.module.recall.entity.RecallRecord;
import com.jxgc.meattrace.module.recall.mapper.RecallRecordMapper;
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
 * 批次召回。
 *
 * <p><b>事务边界是这一节唯一的难点</b>：三处写入必须原子——
 * ① 加工批次置 {@code RECALLED}（挡住后续销售）、
 * ② 该批次全部销售记录置 {@code RECALLED}（标记已售出部分）、
 * ③ 写召回记录 + {@code RECALL} 事件。
 * 少任何一步都会留下自相矛盾的状态：批次已召回但销售记录还是 NORMAL（对不上账），
 * 或者销售记录已标召回但批次还在卖（更糟）。
 *
 * <p><b>加锁顺序</b>：先更新 {@code process_batch} 行、再更新 {@code sale_record} 行，
 * 与销售登记完全一致（销售也是先扣 process_batch 再插 sale_record）。
 * 顺序一致才不会出现"销售持 A 等 B、召回持 B 等 A"的死锁。
 *
 * <p><b>并发正确性</b>：销售扣减的 WHERE 里带 {@code status='ON_SALE'}，
 * 召回的 UPDATE 会把状态改成 {@code RECALLED} 并持有该行锁到提交；
 * 因此召回提交后进入的销售请求必然扣不动——不需要额外的分布式锁。
 */
@Service
public class RecallService {

    private static final String ENDPOINT = "POST /api/recalls";
    private static final String EVENT_TYPE = "RECALL";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RecallRecordMapper recallMapper;
    private final ProcessBatchMapper processMapper;
    private final SaleRecordMapper saleMapper;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;
    private final SequenceService sequences;

    public RecallService(RecallRecordMapper recallMapper,
                         ProcessBatchMapper processMapper,
                         SaleRecordMapper saleMapper,
                         SnapshotFactory snapshotFactory,
                         IdempotencyService idempotencyService,
                         SequenceService sequences) {
        this.recallMapper = recallMapper;
        this.processMapper = processMapper;
        this.saleMapper = saleMapper;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
        this.sequences = sequences;
    }

    @Transactional
    public RecallVO create(RecallCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                RecallRecord existing = recallMapper.selectById(record.getBizId());
                if (existing != null) {
                    return toVO(existing, true);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        ProcessBatch process = requireProcess(request.processBatchNo());
        RecallRules.checkRecallable(process.getProcessNo(), process.getStatus());
        if (ProcessBatch.RECALLED.equals(process.getStatus())) {
            // 已经召回过：把上次的召回单还给他，而不是报一个"状态不允许"让人以为失败了
            return existingRecall(process);
        }

        // ① 先挡销售：条件 UPDATE，0 行说明有别的请求刚刚召回成功
        int rows = processMapper.update(null, Wrappers.<ProcessBatch>lambdaUpdate()
                .set(ProcessBatch::getStatus, ProcessBatch.RECALLED)
                .set(ProcessBatch::getUpdatedAt, LocalDateTime.now())
                .eq(ProcessBatch::getId, process.getId())
                .ne(ProcessBatch::getStatus, ProcessBatch.RECALLED));
        if (rows == 0) {
            ProcessBatch latest = processMapper.selectById(process.getId());
            if (latest != null && ProcessBatch.RECALLED.equals(latest.getStatus())) {
                return existingRecall(latest);
            }
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "加工批次 " + process.getProcessNo() + " 当前状态为 "
                            + (latest == null ? "未知" : latest.getStatus()) + "，无法召回");
        }

        // ② 标记已售出的部分：affected 就是"要去追几笔"
        int affectedSales = saleMapper.markRecalledByProcess(process.getId());
        // ③ 追回数量：此时该批次的销售记录已全部是 RECALLED，SUM 覆盖并发卖出的最后一笔
        BigDecimal recalledQty = saleMapper.sumSoldQty(process.getId());

        LocalDateTime recallTime = TimeFormats.parseOrNow(request.recallTime());
        RecallRecord record = new RecallRecord();
        record.setRecallNo(nextRecallNo());
        record.setProcessBatchId(process.getId());
        record.setReason(request.reason());
        record.setScope(request.scope());
        record.setAffectedSales(affectedSales);
        record.setRecalledQty(recalledQty == null ? BigDecimal.ZERO : recalledQty);
        record.setOperator(request.operator());
        record.setRecallTime(recallTime);
        record.setStatus(RecallRecord.INITIATED);
        record.setRemark(request.remark());
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        recallMapper.insert(record);

        String eventId = snapshotFactory.record(
                process.getProcessNo(),
                EVENT_TYPE,
                recallTime,
                record.getRecalledQty(),
                process.getUnit(),
                Payloads.of(
                        "recallNo", record.getRecallNo(),
                        "processNo", process.getProcessNo(),
                        "reason", record.getReason(),
                        "scope", record.getScope(),
                        "affectedSales", record.getAffectedSales(),
                        "recalledQty", record.getRecalledQty(),
                        "operator", record.getOperator()));

        idempotencyService.bind(requestId, record.getId(), record.getRecallNo());

        process.setStatus(ProcessBatch.RECALLED);
        RecallVO vo = RecallVO.of(record, process.getProcessNo(), process.getMeatType(),
                process.getStatus(), new EvidenceInfo(eventId, EvidenceTask.PENDING), false);
        return vo;
    }

    public PageResult<RecallVO> page(RecallQuery query) {
        Long processId = null;
        String processNo = null;
        if (query.getProcessBatchNo() != null && !query.getProcessBatchNo().isBlank()) {
            ProcessBatch process = requireProcess(query.getProcessBatchNo());
            processId = process.getId();
            processNo = process.getProcessNo();
        }
        final Long resolvedId = processId;

        var wrapper = Wrappers.<RecallRecord>lambdaQuery()
                .eq(resolvedId != null, RecallRecord::getProcessBatchId, resolvedId)
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        RecallRecord::getStatus, query.getStatus())
                .orderByDesc(RecallRecord::getId);
        Page<RecallRecord> page = recallMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> ids = page.getRecords().stream()
                .map(RecallRecord::getProcessBatchId).distinct().toList();
        Map<Long, ProcessBatch> processes = ids.isEmpty() ? Map.of()
                : processMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(ProcessBatch::getId, b -> b, (a, b) -> a));

        return PageResult.of(page, r -> {
            ProcessBatch p = processes.get(r.getProcessBatchId());
            return RecallVO.of(r, p == null ? null : p.getProcessNo(),
                    p == null ? null : p.getMeatType(),
                    p == null ? null : p.getStatus(), null, null);
        });
    }

    public RecallVO detail(long id) {
        RecallRecord record = recallMapper.selectById(id);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "召回记录不存在");
        }
        return toVO(record, null);
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

    /** 已经召回过的批次：返回最近一张召回单，标 replayed=true。 */
    private RecallVO existingRecall(ProcessBatch process) {
        RecallRecord existing = recallMapper.selectOne(Wrappers.<RecallRecord>lambdaQuery()
                .eq(RecallRecord::getProcessBatchId, process.getId())
                .orderByDesc(RecallRecord::getId)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "加工批次 " + process.getProcessNo() + " 已是 RECALLED 状态，但找不到对应的召回记录");
        }
        return toVO(existing, true);
    }

    private RecallVO toVO(RecallRecord record, Boolean replayed) {
        ProcessBatch process = processMapper.selectById(record.getProcessBatchId());
        return RecallVO.of(record,
                process == null ? null : process.getProcessNo(),
                process == null ? null : process.getMeatType(),
                process == null ? null : process.getStatus(),
                null,
                replayed);
    }

    /** RC + yyyyMMdd + 3 位当日序号。 */
    private String nextRecallNo() {
        String day = LocalDate.now().format(DAY);
        return sequences.next("RECALL_NO:" + day, "RC", day, 3, this::currentRecallSeq);
    }

    private long currentRecallSeq() {
        String prefix = "RC" + LocalDate.now().format(DAY);
        RecallRecord last = recallMapper.selectOne(Wrappers.<RecallRecord>lambdaQuery()
                .likeRight(RecallRecord::getRecallNo, prefix)
                .orderByDesc(RecallRecord::getRecallNo)
                .last("LIMIT 1"));
        if (last == null || last.getRecallNo() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(last.getRecallNo().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
