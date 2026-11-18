package com.jxgc.meattrace.module.abnormal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 异常处置入参，对应 TECH-07 的 TC-23「处置通过恢复销售」。
 *
 * <p>{@code resumeSale} 显式传入而不是"有结论就自动恢复"：
 * 处置结论可能是"整批销毁"，那时候恢复销售是灾难。
 */
public record AbnormalDisposeRequest(
        @NotBlank(message = "不能为空") @Size(max = 200) String action,
        @Size(max = 50) String handler,
        @NotBlank(message = "不能为空") @Size(max = 200) String conclusion,
        Boolean resumeSale,
        String disposeTime) {
}
