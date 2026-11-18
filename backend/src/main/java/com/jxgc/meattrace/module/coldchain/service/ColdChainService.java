package com.jxgc.meattrace.module.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.abnormal.service.AbnormalService;
import com.jxgc.meattrace.module.coldchain.config.ColdChainProperties;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainCreateRequest;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainQuery;
import com.jxgc.meattrace.module.coldchain.dto.ColdChainVO;
import com.jxgc.meattrace.module.coldchain.entity.ColdChainLog;
import com.jxgc.meattrace.module.coldchain.mapper.ColdChainLogMapper;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 冷链温控登记。
 *
 * <p>三件事在同一个事务里：写记录、判超限、超限则开异常工单 + 生成 {@code COLD_CHAIN} 事件。
 * 之所以不敢拆开——"记录写了但异常没开"意味着这批肉会一路卖到餐桌，
 * 而且事后查起来只剩一条温度记录，没有任何人被告知。
 *
 * <p>登记<b>不改变采购批次状态</b>：温度超标不等于整批拒收，要不要退、
 * 要不要销毁是处置环节的判断（{@code abnormal_event}），不是登记环节的。
 */
@Service
public class ColdChainService {

    private static final String ENDPOINT = "POST /api/cold-chain-logs";
    private static final String EVENT_TYPE = "COLD_CHAIN";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ColdChainLogMapper coldMapper;
    private final ColdChainProperties properties;
    private final PurchaseBatchMapper purchaseMapper;
    private final AbnormalService abnormalService;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;
    private final SequenceService sequences;

    public ColdChainService(ColdChainLogMapper coldMapper,
                            ColdChainProperties properties,
                            PurchaseBatchMapper purchaseMapper,
                            AbnormalService abnormalService,
                            SnapshotFactory snapshotFactory,
                            IdempotencyService idempotencyService,
                            SequenceService sequences) {
        this.coldMapper = coldMapper;
        this.properties = properties;
        this.purchaseMapper = purchaseMapper;
        this.abnormalService = abnormalService;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
        this.sequences = sequences;
    }

    @Transactional
    public ColdChainVO create(ColdChainCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                ColdChainLog existing = coldMapper.selectById(record.getBizId());
                if (existing != null) {
                    PurchaseBatch batch = purchaseMapper.selectById(existing.getBatchId());
                    return toVO(existing, batch, null, null);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        ColdChainRules.checkSegment(request.segment());
        PurchaseBatch batch = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                .eq(PurchaseBatch::getBatchNo, request.batchNo()));
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在：" + request.batchNo());
        }

        LocalDateTime recordedAt = TimeFormats.parseOrNow(request.recordedAt());
        BigDecimal threshold = properties.thresholdFor(batch.getMeatType());
        boolean exceeded = ColdChainRules.isExceeded(request.temperature(), threshold);

        ColdChainLog log = new ColdChainLog();
        log.setLogNo(nextLogNo());
        log.setBatchId(batch.getId());
        log.setSegment(request.segment());
        log.setTransportNo(request.transportNo());
        log.setTemperature(request.temperature());
        log.setThreshold(threshold);
        log.setExceeded(exceeded);
        log.setRecordedAt(recordedAt);
        log.setRecorder(request.recorder());
        log.setRemark(request.remark());
        log.setCreatedAt(LocalDateTime.now());
        coldMapper.insert(log);

        String abnormalNo = null;
        if (exceeded) {
            String description = "运输段 " + request.segment() + " 实测 " + request.temperature()
                    + "℃ 超出阈值 " + threshold + "℃（运输单号 "
                    + (request.transportNo() == null ? "无" : request.transportNo()) + "）";
            abnormalNo = abnormalService.raiseForColdChain(
                    batch.getId(), batch.getBatchNo(), description, recordedAt,
                    ColdChainRules.levelFor(request.temperature(), threshold));
        }

        String eventId = snapshotFactory.record(
                batch.getBatchNo(),
                EVENT_TYPE,
                recordedAt,
                batch.getQuantity(),
                batch.getUnit(),
                Payloads.of(
                        "logNo", log.getLogNo(),
                        "segment", log.getSegment(),
                        "transportNo", log.getTransportNo(),
                        "temperature", log.getTemperature(),
                        "threshold", log.getThreshold(),
                        "exceeded", log.getExceeded(),
                        "abnormalNo", abnormalNo));

        idempotencyService.bind(requestId, log.getId(), log.getLogNo());

        return toVO(log, batch, abnormalNo, new EvidenceInfo(eventId, EvidenceTask.PENDING));
    }

    public PageResult<ColdChainVO> page(ColdChainQuery query) {
        Long batchId = null;
        if (query.getBatchNo() != null && !query.getBatchNo().isBlank()) {
            PurchaseBatch batch = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                    .eq(PurchaseBatch::getBatchNo, query.getBatchNo()));
            if (batch == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在：" + query.getBatchNo());
            }
            batchId = batch.getId();
        }
        final Long resolvedId = batchId;

        var wrapper = Wrappers.<ColdChainLog>lambdaQuery()
                .eq(resolvedId != null, ColdChainLog::getBatchId, resolvedId)
                .eq(query.getSegment() != null && !query.getSegment().isBlank(),
                        ColdChainLog::getSegment, query.getSegment())
                .eq(query.getExceeded() != null, ColdChainLog::getExceeded, query.getExceeded())
                .orderByDesc(ColdChainLog::getId);
        Page<ColdChainLog> page = coldMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        List<Long> ids = page.getRecords().stream()
                .map(ColdChainLog::getBatchId).distinct().toList();
        Map<Long, PurchaseBatch> batches = ids.isEmpty() ? Map.of()
                : purchaseMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(PurchaseBatch::getId, b -> b, (a, b) -> a));

        return PageResult.of(page, l -> toVO(l, batches.get(l.getBatchId()), null, null));
    }

    public ColdChainVO detail(long id) {
        ColdChainLog log = coldMapper.selectById(id);
        if (log == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "冷链记录不存在");
        }
        return toVO(log, purchaseMapper.selectById(log.getBatchId()), null, null);
    }

    private ColdChainVO toVO(ColdChainLog log, PurchaseBatch batch, String abnormalNo,
                             EvidenceInfo evidence) {
        return ColdChainVO.of(log,
                batch == null ? null : batch.getBatchNo(),
                batch == null ? null : batch.getMeatType(),
                abnormalNo,
                evidence);
    }

    /** CC + yyyyMMdd + 3 位当日序号。 */
    private String nextLogNo() {
        String day = LocalDate.now().format(DAY);
        return sequences.next("COLD_NO:" + day, "CC", day, 3, this::currentLogSeq);
    }

    private long currentLogSeq() {
        String prefix = "CC" + LocalDate.now().format(DAY);
        ColdChainLog last = coldMapper.selectOne(Wrappers.<ColdChainLog>lambdaQuery()
                .likeRight(ColdChainLog::getLogNo, prefix)
                .orderByDesc(ColdChainLog::getLogNo)
                .last("LIMIT 1"));
        if (last == null || last.getLogNo() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(last.getLogNo().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
