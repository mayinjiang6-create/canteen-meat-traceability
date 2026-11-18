package com.jxgc.meattrace.module.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新增供应商备案。{@code supplierCode} 由服务端生成，不接受客户端传入。 */
public record SupplierCreateRequest(
        @NotBlank(message = "不能为空") @Size(max = 100) String name,
        @Size(max = 18) String creditCode,
        @Size(max = 50) String licenseNo,
        @Size(max = 50) String contactName,
        @Size(max = 20) String contactPhone,
        @Size(max = 200) String address) {
}
