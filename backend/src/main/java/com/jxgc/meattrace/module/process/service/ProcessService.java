package com.jxgc.meattrace.module.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.sequence.service.SequenceService;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.process.dto.ProcessCreateRequest;
import com.jxgc.meattrace.module.process.dto.ProcessCreateRequest.SourceItem;
import com.jxgc.meattrace.module.process.dto.ProcessCreateVO;
import com.jxgc.meattrace.module.process.dto.ProcessQuery;
import com.jxgc.meattrace.module.process.dto.ProcessSourceSummary;
import com.jxgc.meattrace.module.process.dto.ProcessSourceVO;
import com.jxgc.meattrace.module.process.dto.ProcessVO;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import com.jxgc.meattrace.module.process.entity.SourceRelation;
import com.jxgc.meattrace.module.process.mapper.ProcessBatchMapper;
import com.jxgc.meattrace.module.process.mapper.SourceRelationMapper;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import com.jxgc.meattrace.module.purchase.mapper.PurchaseBatchMapper;
import com.jxgc.meattrace.module.supplier.entity.Supplier;
import com.jxgc.meattrace.module.supplier.mapper.SupplierMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加工投料（多来源）。
 *
 * <p><b>全项目唯一有真正并发难度的写接口</b>：一个加工批次可能同时消耗多个采购批次，
 * 而每个采购批次的可用量都被别的加工批次争用。这里不靠锁，靠两件事：
 *
 * <ol>
 *   <li><b>先算后写</b>：先做完全部业务校验（状态、余量、产出≤投入），
 *       避免写了一半才发现不合格再回滚——回滚虽然安全，但白烧一次自增 id 和行锁。</li>
 *   <li><b>条件 UPDATE 兜底</b>：真正扣减时用
 *       {@code WHERE available_qty >= #{qty}}，受影响行数为 0 就是"被别的请求抢走了"。
 *       预校验只负责给出友好提示，**不承担正确性责任**。</li>
 * </ol>
 *
 * <p>BR-13：加工批次、来源关系、事件快照、存证任务同事务提交。
 */
@Service
public class ProcessService {

    private static final String ENDPOINT = "POST /api/process-batches";
    private static final String EVENT_TYPE = "PROCESS_CREATE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ProcessBatchMapper processMapper;
    private final SourceRelationMapper sourceRelationMapper;
    private final PurchaseBatchMapper purchaseMapper;
    private final SupplierMapper supplierMapper;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;
    private final SequenceService sequences;

    public ProcessService(ProcessBatchMapper processMapper,
                          SourceRelationMapper sourceRelationMapper,
                          PurchaseBatchMapper purchaseMapper,
                          SupplierMapper supplierMapper,
                          SnapshotFactory snapshotFactory,
                          IdempotencyService idempotencyService,
                          SequenceService sequences) {
        this.processMapper = processMapper;
        this.sourceRelationMapper = sourceRelationMapper;
        this.purchaseMapper = purchaseMapper;
        this.supplierMapper = supplierMapper;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
        this.sequences = sequences;
    }

    // ------------------------------------------------------------------ 投料

    @Transactional
    public ProcessCreateVO create(ProcessCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                ProcessBatch existing = processMapper.selectById(record.getBizId());
                if (existing != null) {
                    return toCreateVO(existing, relationsOf(existing.getId()), null, true);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        // ① 入参层面的规则：重复引用（BR-03）、按批次号排序（统一加锁顺序）
        List<SourceItem> sources = ProcessRules.normalizeSources(request.sources());
        Map<String, PurchaseBatch> byNo = loadSources(sources);

        // ② 状态与余量预校验（BR-01 / BR-02），失败时不产生任何写入
        for (SourceItem item : sources) {
            PurchaseBatch batch = byNo.get(item.purchaseBatchNo());
            if (!PurchaseBatch.PASSED.equals(batch.getStatus())) {
                throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                        "来源批次 " + batch.getBatchNo() + " 当前状态为 " + batch.getStatus()
                                + "，未通过验收不可加工");
            }
            BigDecimal snapshotQty = batch.getAvailableQty() == null ? BigDecimal.ZERO : batch.getAvailableQty();
            if (item.usedQty().compareTo(snapshotQty) > 0) {
                // 快照读说"不够"时再用当前读确认一次：可能别人刚回滚把量还回来了（那就继续走），
                // 也可能真被抢光了（那就抛 40901）。提示里的数字必须是数据库此刻的值，
                // 否则并发下会出现"提示剩余 60 kg、实际已经 0"的荒唐消息。
                BigDecimal current = currentAvailable(batch.getId());
                ProcessRules.checkRemain(batch.getBatchNo(), item.usedQty(), current);
                batch.setAvailableQty(current);
            }
        }

        BigDecimal inputQty = ProcessRules.totalInput(sources);
        // ③ 出品率约束（BR-05）：产出不得大于投入
        ProcessRules.checkOutput(request.outputQty(), inputQty);

        // ④ 建加工批次，追溯码即 processNo
        LocalDateTime processTime = TimeFormats.parseOrNow(request.processTime());
        ProcessBatch process = new ProcessBatch();
        process.setProcessNo(nextProcessNo());
        process.setMeatType(request.meatType());
        process.setOutputQty(request.outputQty());
        process.setAvailableQty(request.outputQty());
        process.setUnit("kg");
        process.setProcessTime(processTime);
        process.setWorkshop(request.workshop());
        process.setOperator(request.operator());
        process.setRetainSampleNo(request.retainSampleNo());
        process.setStatus(ProcessBatch.CREATED);
        process.setCreatedAt(LocalDateTime.now());
        process.setUpdatedAt(LocalDateTime.now());
        processMapper.insert(process);

        // ⑤ 逐来源扣减 + 建关系；任一条扣不动就整笔回滚
        List<ProcessSourceVO> sourceVOs = new ArrayList<>(sources.size());
        for (SourceItem item : sources) {
            PurchaseBatch batch = byNo.get(item.purchaseBatchNo());
            if (purchaseMapper.deductAvailable(batch.getId(), item.usedQty()) == 0) {
                throw new BizException(ErrorCode.QTY_NOT_ENOUGH,
                        "来源批次 " + batch.getBatchNo() + " 可用余量不足，当前剩余 "
                                + DigestUtil.scale(currentAvailable(batch.getId())) + " kg");
            }
            purchaseMapper.closeIfEmpty(batch.getId());
            PurchaseBatch latest = purchaseMapper.selectById(batch.getId());

            SourceRelation relation = new SourceRelation();
            relation.setProcessBatchId(process.getId());
            relation.setPurchaseBatchId(batch.getId());
            relation.setUsedQty(item.usedQty());
            relation.setCreatedAt(LocalDateTime.now());
            sourceRelationMapper.insert(relation);

            sourceVOs.add(new ProcessSourceVO(
                    batch.getBatchNo(),
                    DigestUtil.scale(item.usedQty()),
                    DigestUtil.scale(latest == null ? null : latest.getAvailableQty())));
        }

        // ⑥ PROCESS_CREATE 事件：payload 里带 sources[]，公开页据此还原"这批用了哪几批肉"
        String eventId = snapshotFactory.record(
                process.getProcessNo(),
                EVENT_TYPE,
                processTime,
                inputQty,
                process.getUnit(),
                Payloads.of(
                        "processNo", process.getProcessNo(),
                        "meatType", process.getMeatType(),
                        "outputQty", process.getOutputQty(),
                        "retainSampleNo", process.getRetainSampleNo(),
                        "workshop", process.getWorkshop(),
                        "operator", process.getOperator(),
                        "sources", sources.stream()
                                .map(i -> Payloads.of("purchaseBatchNo", i.purchaseBatchNo(),
                                        "usedQty", i.usedQty()))
                                .toList()));

        idempotencyService.bind(requestId, process.getId(), process.getProcessNo());

        return new ProcessCreateVO(process.getId(), process.getProcessNo(), process.getStatus(),
                DigestUtil.scale(inputQty), DigestUtil.scale(process.getOutputQty()),
                sourceVOs, new EvidenceInfo(eventId, EvidenceTask.PENDING), false);
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<ProcessVO> page(ProcessQuery query) {
        var wrapper = Wrappers.<ProcessBatch>lambdaQuery()
                .like(query.getProcessNo() != null && !query.getProcessNo().isBlank(),
                        ProcessBatch::getProcessNo, query.getProcessNo())
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        ProcessBatch::getStatus, query.getStatus())
                .like(query.getMeatType() != null && !query.getMeatType().isBlank(),
                        ProcessBatch::getMeatType, query.getMeatType())
                .orderByDesc(ProcessBatch::getId);
        Page<ProcessBatch> page = processMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> ids = page.getRecords().stream().map(ProcessBatch::getId).toList();
        Map<Long, ProcessSourceSummary> summaries = ids.isEmpty() ? Map.of()
                : sourceRelationMapper.summariseByProcessIds(ids).stream()
                        .collect(Collectors.toMap(ProcessSourceSummary::getProcessBatchId, s -> s, (a, b) -> a));

        return PageResult.of(page, p -> ProcessVO.of(p, summaries.get(p.getId())));
    }

    public ProcessVO detail(long id) {
        ProcessBatch process = processMapper.selectById(id);
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在");
        }
        return ProcessVO.detail(process, sourceVOs(relationsOf(id)));
    }

    // ------------------------------------------------------------------ 上架

    /**
     * 上架：CREATED → ON_SALE。
     *
     * <p><b>这里不生成存证事件</b>，是刻意的：TECH-02 6.4 定义的 9 类事件里没有"上架"，
     * 它是内部状态流转，不是需要向社会公示的可核验事实。为了凑数发明事件类型，
     * 只会让链上多出一堆看不懂的记录。
     *
     * <p>重复上架按幂等处理（已 ON_SALE 直接返回成功），其余状态一律 40902。
     */
    @Transactional
    public ProcessVO onSale(long id) {
        ProcessBatch process = processMapper.selectById(id);
        if (process == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "加工批次不存在");
        }
        if (ProcessBatch.ON_SALE.equals(process.getStatus())) {
            return ProcessVO.detail(process, sourceVOs(relationsOf(id)));
        }
        int rows = processMapper.update(null, Wrappers.<ProcessBatch>lambdaUpdate()
                .set(ProcessBatch::getStatus, ProcessBatch.ON_SALE)
                .set(ProcessBatch::getUpdatedAt, LocalDateTime.now())
                .eq(ProcessBatch::getId, id)
                .eq(ProcessBatch::getStatus, ProcessBatch.CREATED));
        if (rows == 0) {
            ProcessBatch latest = processMapper.selectById(id);
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "加工批次当前状态为 " + (latest == null ? "未知" : latest.getStatus()) + "，不能上架");
        }
        process.setStatus(ProcessBatch.ON_SALE);
        return ProcessVO.detail(process, sourceVOs(relationsOf(id)));
    }

    // ------------------------------------------------------------------ 内部

    /** 按批次号批量取采购批次，缺一条就报 40401（哪条缺、缺的是谁都要说清楚）。 */
    private Map<String, PurchaseBatch> loadSources(List<SourceItem> sources) {
        List<String> nos = sources.stream().map(SourceItem::purchaseBatchNo).toList();
        Map<String, PurchaseBatch> byNo = purchaseMapper.selectList(
                        Wrappers.<PurchaseBatch>lambdaQuery().in(PurchaseBatch::getBatchNo, nos))
                .stream()
                .collect(Collectors.toMap(PurchaseBatch::getBatchNo, b -> b, (a, b) -> a));
        for (String no : nos) {
            if (!byNo.containsKey(no)) {
                throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在：" + no);
            }
        }
        return byNo;
    }

    /** 当前读真实余量，只用于失败提示与二次确认（不做并发控制）。 */
    private BigDecimal currentAvailable(Long purchaseBatchId) {
        BigDecimal value = purchaseMapper.lockAvailable(purchaseBatchId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<SourceRelation> relationsOf(Long processBatchId) {        return sourceRelationMapper.selectList(Wrappers.<SourceRelation>lambdaQuery()
                .eq(SourceRelation::getProcessBatchId, processBatchId)
                .orderByAsc(SourceRelation::getId));
    }

    /** 关系 → 出参；详情页顺带把供应商名字带出来，省前端再查一次。 */
    private List<ProcessSourceVO> sourceVOs(List<SourceRelation> relations) {
        if (relations.isEmpty()) {
            return List.of();
        }
        List<Long> purchaseIds = relations.stream()
                .map(SourceRelation::getPurchaseBatchId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, PurchaseBatch> batches = purchaseIds.isEmpty() ? Map.of()
                : purchaseMapper.selectBatchIds(purchaseIds).stream()
                        .collect(Collectors.toMap(PurchaseBatch::getId, b -> b, (a, b) -> a));
        List<Long> supplierIds = batches.values().stream()
                .map(PurchaseBatch::getSupplierId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Supplier> suppliers = supplierIds.isEmpty() ? Map.of()
                : supplierMapper.selectBatchIds(supplierIds).stream()
                        .collect(Collectors.toMap(Supplier::getId, s -> s, (a, b) -> a));

        return relations.stream().map(r -> {
            PurchaseBatch batch = batches.get(r.getPurchaseBatchId());
            Supplier supplier = batch == null ? null : suppliers.get(batch.getSupplierId());
            return new ProcessSourceVO(
                    batch == null ? null : batch.getBatchNo(),
                    DigestUtil.scale(r.getUsedQty()),
                    batch == null ? null : DigestUtil.scale(batch.getAvailableQty()),
                    supplier == null ? null : supplier.getName());
        }).toList();
    }

    private ProcessCreateVO toCreateVO(ProcessBatch process, List<SourceRelation> relations,
                                       EvidenceInfo evidence, boolean replayed) {
        BigDecimal inputQty = relations.stream()
                .map(SourceRelation::getUsedQty)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProcessCreateVO(process.getId(), process.getProcessNo(), process.getStatus(),
                DigestUtil.scale(inputQty), DigestUtil.scale(process.getOutputQty()),
                sourceVOs(relations), evidence, replayed);
    }

    /** PR + yyyyMMdd + 3 位当日序号。 */
    private String nextProcessNo() {
        String day = LocalDate.now().format(DAY);
        return sequences.next("PROCESS_NO:" + day, "PR", day, 3, this::currentProcessSeq);
    }

    /** 种子：业务表里当日已有最大序号（发号器首次创建时用，之后不再查）。 */
    private long currentProcessSeq() {
        String prefix = "PR" + LocalDate.now().format(DAY);
        ProcessBatch last = processMapper.selectOne(Wrappers.<ProcessBatch>lambdaQuery()
                .likeRight(ProcessBatch::getProcessNo, prefix)
                .orderByDesc(ProcessBatch::getProcessNo)
                .last("LIMIT 1"));
        if (last == null || last.getProcessNo() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(last.getProcessNo().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
