package com.jxgc.meattrace.module.supplier.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 修改供应商备案。字段为 null 表示不修改。 */
public record SupplierUpdateRequest(
        @Size(max = 100) String name,
        @Size(max = 50) String licenseNo,
        @Size(max = 50) String contactName,
        @Size(max = 20) String contactPhone,
        @Size(max = 200) String address,
        @Pattern(regexp = "ACTIVE|SUSPENDED|BLACKLISTED", message = "只能是 ACTIVE/SUSPENDED/BLACKLISTED")
        String status) {
}
