package com.jxgc.meattrace.module.coldchain.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 冷链登记入参，口径见 docs/tech/03-接口文档.md 4.6。
 *
 * <p>只登记"某批次在某一段路上、某一刻、实测多少度"。
 * 阈值不由录入人填——填了就能改，改了就不叫监管；阈值从配置按品类取。
 */
public record ColdChainCreateRequest(
        @NotBlank(message = "采购批次号不能为空") @Size(max = 40) String batchNo,
        @NotBlank(message = "运输段不能为空") @Size(max = 30) String segment,
        @Size(max = 40) String transportNo,
        @NotNull(message = "实测温度不能为空")
        @Digits(integer = 3, fraction = 2, message = "最多 2 位小数")
        BigDecimal temperature,
        String recordedAt,
        @Size(max = 50) String recorder,
        @Size(max = 200) String remark) {
}
