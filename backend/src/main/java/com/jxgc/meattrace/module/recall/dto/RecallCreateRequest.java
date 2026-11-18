package com.jxgc.meattrace.module.recall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 召回入参，口径见 docs/tech/03-接口文档.md 4.8。
 *
 * <p>召回必须写清原因：{@code reason} 会进链上事件的 payload，
 * 半年后有人问"这批为什么召回"，答案在链上而不是在某个人脑子里。
 */
public record RecallCreateRequest(
        @NotBlank(message = "追溯码不能为空") @Size(max = 40) String processBatchNo,
        @NotBlank(message = "召回原因不能为空") @Size(max = 300) String reason,
        @Size(max = 200) String scope,
        @Size(max = 50) String operator,
        String recallTime,
        @Size(max = 200) String remark) {
}
