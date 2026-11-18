package com.jxgc.meattrace.module.purchase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.common.TimeFormats;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import com.jxgc.meattrace.module.purchase.dto.PurchaseCreateRequest;
import com.jxgc.meattrace.module.purchase.dto.PurchaseCreateVO;
import com.jxgc.meattrace.module.purchase.dto.PurchaseQuery;
import com.jxgc.meattrace.module.purchase.dto.PurchaseVO;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import com.jxgc.meattrace.module.purchase.mapper.PurchaseBatchMapper;
import com.jxgc.meattrace.module.supplier.entity.Supplier;
import com.jxgc.meattrace.module.supplier.mapper.SupplierMapper;
import com.jxgc.meattrace.module.supplier.service.SupplierService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 采购批次。创建时同事务生成 PURCHASE_CREATE 存证事件（BR-13）。 */
@Service
public class PurchaseService {

    private static final String ENDPOINT = "POST /api/purchases";
    private static final String EVENT_TYPE = "PURCHASE_CREATE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final PurchaseBatchMapper purchaseMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierService supplierService;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;

    public PurchaseService(PurchaseBatchMapper purchaseMapper,
                           SupplierMapper supplierMapper,
                           SupplierService supplierService,
                           SnapshotFactory snapshotFactory,
                           IdempotencyService idempotencyService) {
        this.purchaseMapper = purchaseMapper;
        this.supplierMapper = supplierMapper;
        this.supplierService = supplierService;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public PurchaseCreateVO create(PurchaseCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                PurchaseBatch existing = purchaseMapper.selectById(record.getBizId());
                if (existing != null) {
                    return new PurchaseCreateVO(existing.getId(), existing.getBatchNo(),
                            existing.getStatus(), DigestUtil.scale(existing.getAvailableQty()),
                            null, true);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        Supplier supplier = supplierService.requireActive(request.supplierId());

        PurchaseBatch batch = new PurchaseBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setSupplierId(supplier.getId());
        batch.setMeatType(request.meatType());
        batch.setOrigin(request.origin());
        batch.setSlaughterNo(request.slaughterNo());
        batch.setQuarantineNo(request.quarantineNo());
        batch.setQuantity(request.quantity());
        batch.setAvailableQty(request.quantity());
        batch.setUnit(request.unit() == null || request.unit().isBlank() ? "kg" : request.unit());
        batch.setUnitPrice(request.unitPrice());
        batch.setArriveTime(TimeFormats.parseOrNow(request.arriveTime()));
        batch.setColdChainTemp(request.coldChainTemp());
        batch.setStatus(PurchaseBatch.PENDING_ACCEPT);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.insert(batch);

        String eventId = snapshotFactory.record(
                batch.getBatchNo(),
                EVENT_TYPE,
                batch.getArriveTime(),
                batch.getQuantity(),
                batch.getUnit(),
                Payloads.of(
                        "batchNo", batch.getBatchNo(),
                        "supplierCode", supplier.getSupplierCode(),
                        "meatType", batch.getMeatType(),
                        "quantity", batch.getQuantity(),
                        "quarantineNo", batch.getQuarantineNo()));

        idempotencyService.bind(requestId, batch.getId(), batch.getBatchNo());

        return new PurchaseCreateVO(batch.getId(), batch.getBatchNo(), batch.getStatus(),
                DigestUtil.scale(batch.getAvailableQty()),
                new EvidenceInfo(eventId, EvidenceTask.PENDING), false);
    }

    public PageResult<PurchaseVO> page(PurchaseQuery query) {
        var wrapper = Wrappers.<PurchaseBatch>lambdaQuery()
                .like(query.getBatchNo() != null && !query.getBatchNo().isBlank(),
                        PurchaseBatch::getBatchNo, query.getBatchNo())
                .eq(query.getSupplierId() != null, PurchaseBatch::getSupplierId, query.getSupplierId())
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        PurchaseBatch::getStatus, query.getStatus())
                .like(query.getMeatType() != null && !query.getMeatType().isBlank(),
                        PurchaseBatch::getMeatType, query.getMeatType())
                .orderByDesc(PurchaseBatch::getId);
        Page<PurchaseBatch> page = purchaseMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        Map<Long, String> names = supplierNames(page.getRecords());
        return PageResult.of(page, b -> PurchaseVO.of(b, names.get(b.getSupplierId())));
    }

    public PurchaseVO detail(long id) {
        PurchaseBatch batch = purchaseMapper.selectById(id);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在");
        }
        Supplier supplier = supplierMapper.selectById(batch.getSupplierId());
        return PurchaseVO.of(batch, supplier == null ? null : supplier.getName());
    }

    public PurchaseBatch requireByNo(String batchNo) {
        PurchaseBatch batch = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                .eq(PurchaseBatch::getBatchNo, batchNo));
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "采购批次不存在：" + batchNo);
        }
        return batch;
    }

    private Map<Long, String> supplierNames(List<PurchaseBatch> batches) {
        List<Long> ids = batches.stream()
                .map(PurchaseBatch::getSupplierId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return supplierMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName, (a, b) -> a));
    }

    /** PB + yyyyMMdd + 3 位当日序号。 */
    private String nextBatchNo() {
        String prefix = "PB" + LocalDate.now().format(DAY);
        PurchaseBatch last = purchaseMapper.selectOne(Wrappers.<PurchaseBatch>lambdaQuery()
                .likeRight(PurchaseBatch::getBatchNo, prefix)
                .orderByDesc(PurchaseBatch::getBatchNo)
                .last("LIMIT 1"));
        int seq = 1;
        if (last != null && last.getBatchNo() != null) {
            String tail = last.getBatchNo().substring(prefix.length());
            try {
                seq = Integer.parseInt(tail) + 1;
            } catch (NumberFormatException ignored) {
                seq = 1;
            }
        }
        return prefix + String.format("%03d", seq);
    }
}
