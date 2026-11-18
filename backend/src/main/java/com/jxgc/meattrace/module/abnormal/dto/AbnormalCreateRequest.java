package com.jxgc.meattrace.module.abnormal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 异常登记入参，口径见 docs/tech/03-接口文档.md 4.7。
 *
 * <p>{@code processBatchNo} 与 {@code batchNo} 至少填一个：
 * 前者的场景是"成品抽检不合格"，后者的场景是"到货就发现票证不齐"。
 */
public record AbnormalCreateRequest(
        @Size(max = 40) String processBatchNo,
        @Size(max = 40) String batchNo,
        @NotBlank(message = "不能为空") @Size(max = 30) String abnormalType,
        @Size(max = 10) String level,
        @NotBlank(message = "不能为空") @Size(max = 300) String description,
        String occurredAt,
        @Size(max = 50) String handler) {
}
