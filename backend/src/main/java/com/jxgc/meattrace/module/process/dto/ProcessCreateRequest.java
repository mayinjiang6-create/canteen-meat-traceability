package com.jxgc.meattrace.module.process.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 加工投料入参，口径见 docs/tech/03-接口文档.md 4.4。
 *
 * <p>数量用 {@link BigDecimal} 接收字符串（{@code "80.000"}）：
 * 前端传浮点数会在序列化层就丢精度，摘要随之对不上。
 *
 * <p>{@code sources} 至少一条、最多 10 条：实际业务上一批净菜最多拌几个来源，
 * 上限既是防御性校验，也避免单次事务扣减太多行而拉长锁持有时间。
 */
public record ProcessCreateRequest(
        @NotBlank(message = "不能为空") @Size(max = 30) String meatType,
        String processTime,
        @Size(max = 50) String workshop,
        @Size(max = 50) String operator,
        @Size(max = 40) String retainSampleNo,
        @NotNull(message = "不能为空")
        @DecimalMin(value = "0.001", message = "必须大于 0")
        @Digits(integer = 9, fraction = 3, message = "最多 3 位小数")
        BigDecimal outputQty,
        @NotEmpty(message = "至少需要一个来源批次")
        @Size(max = 10, message = "单次投料最多 10 个来源批次")
        @Valid
        List<SourceItem> sources) {

    /** 单个来源：哪个采购批次、投了多少。 */
    public record SourceItem(
            @NotBlank(message = "批次号不能为空") @Size(max = 40) String purchaseBatchNo,
            @NotNull(message = "不能为空")
            @DecimalMin(value = "0.001", message = "必须大于 0")
            @Digits(integer = 9, fraction = 3, message = "最多 3 位小数")
            BigDecimal usedQty) {
    }
}
