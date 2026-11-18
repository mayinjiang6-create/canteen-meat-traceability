package com.jxgc.meattrace.module.sale.dto;

import com.jxgc.meattrace.module.sale.entity.SaleRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 销售记录出参（列表与详情共用）。
 *
 * <p>{@code amount} 是 quantity × unitPrice，服务端算好返回：
 * 让前端各自乘一遍，迟早出现两个页面显示两个金额。
 */
public record SaleVO(
        Long id,
        String saleNo,
        String processNo,
        String meatType,
        String channel,
        String quantity,
        String unitPrice,
        String amount,
        LocalDateTime saleTime,
        String operator,
        String status) {

    public static SaleVO of(SaleRecord record, String processNo, String meatType) {
        return new SaleVO(
                record.getId(),
                record.getSaleNo(),
                processNo,
                meatType,
                record.getChannel(),
                scale(record.getQuantity()),
                money(record.getUnitPrice()),
                amount(record.getQuantity(), record.getUnitPrice()),
                record.getSaleTime(),
                record.getOperator(),
                record.getStatus());
    }

    /** 金额 = 数量 × 单价，保留 2 位；单价缺失时返回 null（不编造 0 元）。 */
    public static String amount(BigDecimal quantity, BigDecimal unitPrice) {
        if (quantity == null || unitPrice == null) {
            return null;
        }
        return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String scale(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
