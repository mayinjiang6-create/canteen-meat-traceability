package com.jxgc.meattrace.module.supplier.dto;

import com.jxgc.meattrace.module.supplier.entity.Supplier;
import java.time.LocalDateTime;

/** 供应商出参。 */
public record SupplierVO(
        Long id,
        String supplierCode,
        String name,
        String creditCode,
        String licenseNo,
        String contactName,
        String contactPhone,
        String address,
        String status,
        LocalDateTime createdAt) {

    public static SupplierVO of(Supplier s) {
        return new SupplierVO(s.getId(), s.getSupplierCode(), s.getName(), s.getCreditCode(),
                s.getLicenseNo(), s.getContactName(), s.getContactPhone(), s.getAddress(),
                s.getStatus(), s.getCreatedAt());
    }
}
