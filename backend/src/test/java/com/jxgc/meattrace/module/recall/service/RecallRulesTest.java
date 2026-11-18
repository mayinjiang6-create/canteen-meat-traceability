package com.jxgc.meattrace.module.recall.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 召回规则单测（对应 TECH-07 的 TC-24）。 */
class RecallRulesTest {

    @Test
    @DisplayName("TC-24 在售、售罄、异常冻结的批次都可召回")
    void recallableStatuses() {
        assertDoesNotThrow(() -> RecallRules.checkRecallable("PR1", ProcessBatch.ON_SALE));
        assertDoesNotThrow(() -> RecallRules.checkRecallable("PR1", ProcessBatch.SOLD_OUT));
        assertDoesNotThrow(() -> RecallRules.checkRecallable("PR1", ProcessBatch.ABNORMAL));
        assertDoesNotThrow(() -> RecallRules.checkRecallable("PR1", ProcessBatch.CREATED));
    }

    @Test
    @DisplayName("已结案的批次不能召回（终端状态，改不了）")
    void closedNotRecallable() {
        BizException ex = assertThrows(BizException.class,
                () -> RecallRules.checkRecallable("PR1", ProcessBatch.CLOSED));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    @DisplayName("状态缺失时拒绝，而不是当成可召回")
    void nullStatusRejected() {
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED,
                assertThrows(BizException.class, () -> RecallRules.checkRecallable("PR1", null))
                        .getErrorCode());
    }
}
