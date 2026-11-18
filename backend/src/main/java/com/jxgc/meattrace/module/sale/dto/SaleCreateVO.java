package com.jxgc.meattrace.module.sale.dto;

import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 销售登记出参，口径见 docs/tech/03-接口文档.md 4.5。
 *
 * <p>{@code processStatus} 与 {@code availableQty} 一并返回，是为了让窗口端立刻知道
 * "这一单卖完是不是就售罄了"——不必再发一次详情请求。售罄时这里会是
 * {@code SOLD_OUT} + {@code 0.000}，前端据此把销售按钮置灰。
 */
public record SaleCreateVO(
        Long id,
        String saleNo,
        String processNo,
        String channel,
        String quantity,
        String unitPrice,
        String amount,
        LocalDateTime saleTime,
        String operator,
        String status,
        String processStatus,
        String availableQty,
        EvidenceInfo evidence,
        Boolean replayed) {

    public static SaleCreateVO of(SaleVO sale, String processStatus, BigDecimal availableQty,
                                  EvidenceInfo evidence, Boolean replayed) {
        return new SaleCreateVO(
                sale.id(),
                sale.saleNo(),
                sale.processNo(),
                sale.channel(),
                sale.quantity(),
                sale.unitPrice(),
                sale.amount(),
                sale.saleTime(),
                sale.operator(),
                sale.status(),
                processStatus,
                availableQty == null ? null
                        : availableQty.setScale(3, RoundingMode.HALF_UP).toPlainString(),
                evidence,
                replayed);
    }
}
