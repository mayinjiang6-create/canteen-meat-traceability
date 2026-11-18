package com.jxgc.meattrace.module.coldchain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.coldchain.config.ColdChainProperties;
import com.jxgc.meattrace.module.coldchain.entity.ColdChainLog;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 冷链阈值判定的单测（对应 TECH-07 的 TC-10 / TC-11）。 */
class ColdChainRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("TC-10 阈值按品类取：猪肉 4℃、牛肉 4℃、冷冻 −15℃")
    void thresholdByMeatType() {
        ColdChainProperties props = new ColdChainProperties();
        assertEquals(0, props.thresholdFor("冷鲜猪肉").compareTo(d("4.0")));
        assertEquals(0, props.thresholdFor("冷鲜牛肉").compareTo(d("4.0")));
        assertEquals(0, props.thresholdFor("冷冻牛腩").compareTo(d("-15.0")),
                "品类里同时出现「冻」和「牛」时，冷冻优先——冷冻标准更严");
        assertEquals(0, props.thresholdFor("白条鸡").compareTo(d("4.0")),
                "未识别品类走兜底阈值，不能返回 null 让整条记录算不出超限");
    }

    @Test
    @DisplayName("恰好等于阈值不算超限（边界）")
    void equalThresholdIsNotExceeded() {
        assertFalse(ColdChainRules.isExceeded(d("4.00"), d("4.0")));
        assertTrue(ColdChainRules.isExceeded(d("4.01"), d("4.0")));
    }

    @Test
    @DisplayName("TC-11 超限分级：0.5℃ 内 LOW、2℃ 内 MEDIUM、再多 HIGH")
    void levelBands() {
        assertEquals("LOW", ColdChainRules.levelFor(d("4.30"), d("4.0")));
        assertEquals("MEDIUM", ColdChainRules.levelFor(d("5.50"), d("4.0")));
        assertEquals("HIGH", ColdChainRules.levelFor(d("9.00"), d("4.0")));
        assertEquals("LOW", ColdChainRules.levelFor(d("3.00"), d("4.0")), "没超限时等级无意义，取 LOW");
    }

    @Test
    @DisplayName("运输段只允许两段，其余 40001")
    void segmentValidated() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> ColdChainRules.checkSegment("TRUCK"))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> ColdChainRules.checkSegment(null))
                        .getErrorCode());
        // 两个合法值不抛
        ColdChainRules.checkSegment(ColdChainLog.ORIGIN_TO_WAREHOUSE);
        ColdChainRules.checkSegment(ColdChainLog.WAREHOUSE_TO_CANTEEN);
    }
}
