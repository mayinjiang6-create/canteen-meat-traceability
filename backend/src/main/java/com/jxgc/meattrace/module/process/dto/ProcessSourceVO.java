package com.jxgc.meattrace.module.process.dto;

/**
 * 来源明细出参。
 *
 * @param purchaseBatchNo 上游采购批次号
 * @param usedQty         本次投入量（3 位小数字符串）
 * @param remainQty       扣减后该批次剩余可用量
 * @param supplierName    供应商名称，仅详情接口填（列表接口为 null）
 */
public record ProcessSourceVO(
        String purchaseBatchNo,
        String usedQty,
        String remainQty,
        String supplierName) {

    public ProcessSourceVO(String purchaseBatchNo, String usedQty, String remainQty) {
        this(purchaseBatchNo, usedQty, remainQty, null);
    }
}
