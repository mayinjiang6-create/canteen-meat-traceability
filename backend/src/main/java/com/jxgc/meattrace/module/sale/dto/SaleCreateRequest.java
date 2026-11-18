package com.jxgc.meattrace.module.sale.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 销售登记入参，口径见 docs/tech/03-接口文档.md 4.5。
 *
 * <p>用加工批次号（追溯码 {@code PR...}）而不是主键：窗口人员扫的就是包装上的码，
 * 让他再记一个数据库 id 不现实。
 *
 * <p>数量用字符串接收（{@link BigDecimal}）：前端传浮点会在序列化层丢精度，摘要随之对不上。
 */
public record SaleCreateRequest(
        @NotBlank(message = "追溯码不能为空") @Size(max = 40) String processBatchNo,
        @Size(max = 30) String channel,
        @NotNull(message = "不能为空")
        @DecimalMin(value = "0.001", message = "必须大于 0")
        @Digits(integer = 9, fraction = 3, message = "最多 3 位小数")
        BigDecimal quantity,
        @DecimalMin(value = "0.00", message = "不得为负")
        @Digits(integer = 8, fraction = 2, message = "最多 2 位小数")
        BigDecimal unitPrice,
        String saleTime,
        @Size(max = 50) String operator) {
}
