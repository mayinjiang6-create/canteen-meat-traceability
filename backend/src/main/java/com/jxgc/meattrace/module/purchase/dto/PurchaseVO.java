package com.jxgc.meattrace.module.purchase.dto;

import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 采购批次出参。
 *
 * <p>数量与金额一律以**字符串**输出（接口文档 1.3）：JavaScript 的 Number 是双精度浮点，
 * 往返一趟 {@code 120.001} 可能变成 {@code 120.00099999999999}，直接导致摘要比对失败。
 */
public record PurchaseVO(
        Long id,
        String batchNo,
        Long supplierId,
        String supplierName,
        String meatType,
        String origin,
        String slaughterNo,
        String quarantineNo,
        String quantity,
        String availableQty,
        String unit,
        String unitPrice,
        LocalDateTime arriveTime,
        String coldChainTemp,
        String status,
        LocalDateTime createdAt) {

    public static PurchaseVO of(PurchaseBatch batch, String supplierName) {
        return new PurchaseVO(
                batch.getId(),
                batch.getBatchNo(),
                batch.getSupplierId(),
                supplierName,
                batch.getMeatType(),
                batch.getOrigin(),
                batch.getSlaughterNo(),
                batch.getQuarantineNo(),
                scale(batch.getQuantity(), 3),
                scale(batch.getAvailableQty(), 3),
                batch.getUnit(),
                scale(batch.getUnitPrice(), 2),
                batch.getArriveTime(),
                scale(batch.getColdChainTemp(), 2),
                batch.getStatus(),
                batch.getCreatedAt());
    }

    private static String scale(BigDecimal value, int digits) {
        if (value == null) {
            return null;
        }
        return value.setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }
}
