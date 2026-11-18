package com.jxgc.meattrace.module.supplier.dto;

/** 新增供应商出参。{@code replayed=true} 表示命中了 BR-10 幂等，数据未二次落库。 */
public record SupplierCreateVO(
        Long id,
        String supplierCode,
        String name,
        String status,
        String eventId,
        Boolean replayed) {
}
