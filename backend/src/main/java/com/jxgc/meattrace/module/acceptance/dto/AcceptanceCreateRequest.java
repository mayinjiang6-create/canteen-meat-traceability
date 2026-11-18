package com.jxgc.meattrace.module.acceptance.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 验收登记入参，口径见 docs/tech/03-接口文档.md 4.3。 */
public record AcceptanceCreateRequest(
        @NotNull(message = "不能为空") Long batchId,
        String acceptTime,
        @NotBlank(message = "不能为空") @Size(max = 50) String inspector,
        @Digits(integer = 3, fraction = 2, message = "最多 2 位小数") BigDecimal temperature,
        Boolean appearanceOk,
        Boolean certComplete,
        Boolean sampleRetained,
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "PASS|FAIL", message = "只能是 PASS 或 FAIL") String result,
        @Size(max = 200) String rejectReason) {
}
