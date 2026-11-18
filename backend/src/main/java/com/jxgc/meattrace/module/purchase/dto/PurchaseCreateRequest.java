package com.jxgc.meattrace.module.purchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 创建采购批次，口径见 docs/tech/03-接口文档.md 4.2。 */
public record PurchaseCreateRequest(
        @NotNull(message = "不能为空") Long supplierId,
        @NotBlank(message = "不能为空") @Size(max = 30) String meatType,
        @Size(max = 100) String origin,
        @Size(max = 50) String slaughterNo,
        @NotBlank(message = "检疫合格证号不能为空") @Size(max = 60) String quarantineNo,
        @NotNull(message = "不能为空")
        @DecimalMin(value = "0.001", message = "必须大于 0")
        @Digits(integer = 9, fraction = 3, message = "最多 3 位小数")
        BigDecimal quantity,
        @Size(max = 10) String unit,
        @Digits(integer = 8, fraction = 2, message = "最多 2 位小数") BigDecimal unitPrice,
        String arriveTime,
        @Digits(integer = 3, fraction = 2, message = "最多 2 位小数") BigDecimal coldChainTemp) {
}
