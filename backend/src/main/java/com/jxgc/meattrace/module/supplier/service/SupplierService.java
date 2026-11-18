package com.jxgc.meattrace.module.supplier.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.common.Payloads;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.mapper.EventSnapshotMapper;
import com.jxgc.meattrace.module.evidence.service.SnapshotFactory;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.service.IdempotencyService;
import com.jxgc.meattrace.module.supplier.dto.SupplierCreateRequest;
import com.jxgc.meattrace.module.supplier.dto.SupplierCreateVO;
import com.jxgc.meattrace.module.supplier.dto.SupplierQuery;
import com.jxgc.meattrace.module.supplier.dto.SupplierUpdateRequest;
import com.jxgc.meattrace.module.supplier.dto.SupplierVO;
import com.jxgc.meattrace.module.supplier.entity.Supplier;
import com.jxgc.meattrace.module.supplier.mapper.SupplierMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商备案。创建时同事务生成 SUPPLIER_REG 存证事件。 */
@Service
public class SupplierService {

    private static final String ENDPOINT = "POST /api/suppliers";
    private static final String EVENT_TYPE = "SUPPLIER_REG";

    private final SupplierMapper supplierMapper;
    private final EventSnapshotMapper snapshotMapper;
    private final SnapshotFactory snapshotFactory;
    private final IdempotencyService idempotencyService;

    public SupplierService(SupplierMapper supplierMapper,
                           EventSnapshotMapper snapshotMapper,
                           SnapshotFactory snapshotFactory,
                           IdempotencyService idempotencyService) {
        this.supplierMapper = supplierMapper;
        this.snapshotMapper = snapshotMapper;
        this.snapshotFactory = snapshotFactory;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public SupplierCreateVO create(SupplierCreateRequest request, String requestId, Long userId) {
        Optional<RequestIdempotency> replay = idempotencyService.claim(requestId, userId, ENDPOINT);
        if (replay.isPresent()) {
            RequestIdempotency record = replay.get();
            if (record.getBizId() != null) {
                Supplier existing = supplierMapper.selectById(record.getBizId());
                if (existing != null) {
                    return new SupplierCreateVO(existing.getId(), existing.getSupplierCode(),
                            existing.getName(), existing.getStatus(),
                            findEventId(existing.getSupplierCode()), true);
                }
            }
            throw new BizException(ErrorCode.DUPLICATE_REQUEST);
        }

        Supplier supplier = new Supplier();
        supplier.setSupplierCode(nextCode());
        supplier.setName(request.name());
        supplier.setCreditCode(request.creditCode());
        supplier.setLicenseNo(request.licenseNo());
        supplier.setContactName(request.contactName());
        supplier.setContactPhone(request.contactPhone());
        supplier.setAddress(request.address());
        supplier.setStatus(Supplier.ACTIVE);
        supplier.setCreatedAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.insert(supplier);

        String eventId = snapshotFactory.record(
                supplier.getSupplierCode(),
                EVENT_TYPE,
                LocalDateTime.now(),
                null,
                null,
                Payloads.of(
                        "supplierCode", supplier.getSupplierCode(),
                        "name", supplier.getName(),
                        "licenseNo", supplier.getLicenseNo(),
                        "status", supplier.getStatus()));

        idempotencyService.bind(requestId, supplier.getId(), supplier.getSupplierCode());
        return new SupplierCreateVO(supplier.getId(), supplier.getSupplierCode(),
                supplier.getName(), supplier.getStatus(), eventId, false);
    }

    @Transactional
    public SupplierVO update(long id, SupplierUpdateRequest request) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        if (request.name() != null) {
            supplier.setName(request.name());
        }
        if (request.licenseNo() != null) {
            supplier.setLicenseNo(request.licenseNo());
        }
        if (request.contactName() != null) {
            supplier.setContactName(request.contactName());
        }
        if (request.contactPhone() != null) {
            supplier.setContactPhone(request.contactPhone());
        }
        if (request.address() != null) {
            supplier.setAddress(request.address());
        }
        if (request.status() != null) {
            supplier.setStatus(request.status());
        }
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(supplier);
        return SupplierVO.of(supplier);
    }

    public PageResult<SupplierVO> page(SupplierQuery query) {
        var wrapper = Wrappers.<Supplier>lambdaQuery()
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        Supplier::getStatus, query.getStatus())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(Supplier::getName, query.getKeyword())
                                .or().like(Supplier::getSupplierCode, query.getKeyword())
                                .or().like(Supplier::getLicenseNo, query.getKeyword()))
                .orderByDesc(Supplier::getId);
        Page<Supplier> page = supplierMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page, SupplierVO::of);
    }

    public SupplierVO getById(long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        return SupplierVO.of(supplier);
    }

    public Supplier requireActive(long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "供应商不存在");
        }
        if (!Supplier.ACTIVE.equals(supplier.getStatus())) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "供应商状态为 " + supplier.getStatus() + "，不可新增采购");
        }
        return supplier;
    }

    private String findEventId(String supplierCode) {
        EventSnapshot snapshot = snapshotMapper.selectOne(Wrappers.<EventSnapshot>lambdaQuery()
                .eq(EventSnapshot::getBatchId, supplierCode)
                .eq(EventSnapshot::getEventType, EVENT_TYPE)
                .orderByDesc(EventSnapshot::getId)
                .last("LIMIT 1"));
        return snapshot == null ? null : snapshot.getEventId();
    }

    /** 生成 SUP-0001 形式的编码：取当前最大编码 + 1。 */
    private String nextCode() {
        Supplier last = supplierMapper.selectOne(Wrappers.<Supplier>lambdaQuery()
                .orderByDesc(Supplier::getSupplierCode)
                .last("LIMIT 1"));
        int next = 1;
        if (last != null && last.getSupplierCode() != null) {
            String digits = last.getSupplierCode().replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    next = Integer.parseInt(digits) + 1;
                } catch (NumberFormatException ignored) {
                    next = 1;
                }
            }
        }
        return String.format("SUP-%04d", next);
    }
}
