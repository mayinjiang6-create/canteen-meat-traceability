package com.jxgc.meattrace.module.abnormal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.abnormal.entity.AbnormalEvent;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 异常规则单测（对应 TECH-07 的 TC-22 / TC-23）。 */
class AbnormalRulesTest {

    @Test
    @DisplayName("TC-22 登记异常必须挂批次：两个都不填 → 40001")
    void targetRequired() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> AbnormalRules.checkTarget(false, false))
                        .getErrorCode());
        // 挂任意一个都合法
        AbnormalRules.checkTarget(true, false);
        AbnormalRules.checkTarget(false, true);
    }

    @Test
    @DisplayName("只有 ON_SALE 的批次需要被冻结，其余状态不覆盖")
    void onlyOnSaleFreezes() {
        assertTrue(AbnormalRules.shouldFreeze(ProcessBatch.ON_SALE));
        assertFalse(AbnormalRules.shouldFreeze(ProcessBatch.CREATED));
        assertFalse(AbnormalRules.shouldFreeze(ProcessBatch.SOLD_OUT), "售罄是事实，不能被异常覆盖");
        assertFalse(AbnormalRules.shouldFreeze(ProcessBatch.RECALLED));
        assertFalse(AbnormalRules.shouldFreeze(null));
    }

    @Test
    @DisplayName("TC-23 已结案的异常不可重复处置 → 40902")
    void closedIsNotDisposable() {
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED,
                assertThrows(BizException.class, () -> AbnormalRules.checkDisposable(AbnormalEvent.CLOSED))
                        .getErrorCode());
        // OPEN / HANDLING 可以处置
        AbnormalRules.checkDisposable(AbnormalEvent.OPEN);
        AbnormalRules.checkDisposable(AbnormalEvent.HANDLING);
    }

    @Test
    @DisplayName("异常类型与等级都有白名单，拼错的字符串不该悄悄落库")
    void typeAndLevelValidated() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> AbnormalRules.checkType("抽检不合格"))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> AbnormalRules.checkLevel("CRITICAL"))
                        .getErrorCode());
        AbnormalRules.checkLevel(null);
        AbnormalRules.checkLevel("");
    }
}
