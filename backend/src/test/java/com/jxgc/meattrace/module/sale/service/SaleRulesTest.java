package com.jxgc.meattrace.module.sale.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 销售规则单测（对应 TECH-07 的 TC-19 / TC-20 / TC-25）。
 *
 * <p>规则抽成 {@link SaleRules} 的价值就在这里：不启 Spring、不连数据库，
 * 把"哪种状态能卖、卖多少算超量"一条一条钉死，几百毫秒跑完。
 */
class SaleRulesTest {

    private static final String NO = "PR20261118001";

    @Test
    @DisplayName("TC-18 在售批次允许销售")
    void onSaleIsSellable() {
        assertDoesNotThrow(() -> SaleRules.checkSellable(NO, ProcessBatch.ON_SALE));
    }

    @Test
    @DisplayName("TC-19 未上架（CREATED）销售 → 40902，提示要能指导操作")
    void createdIsNotSellable() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkSellable(NO, ProcessBatch.CREATED));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("尚未上架"), ex.getMessage());
    }

    @Test
    @DisplayName("已售罄（SOLD_OUT）销售 → 40902")
    void soldOutIsNotSellable() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkSellable(NO, ProcessBatch.SOLD_OUT));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("售罄"), ex.getMessage());
    }

    @Test
    @DisplayName("TC-25 已召回（RECALLED）销售 → 40902，提示明确禁止")
    void recalledIsNotSellable() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkSellable(NO, ProcessBatch.RECALLED));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已召回"), ex.getMessage());
    }

    @Test
    @DisplayName("异常冻结（ABNORMAL）销售 → 40902，与召回区分开")
    void abnormalIsNotSellable() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkSellable(NO, ProcessBatch.ABNORMAL));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("异常"), ex.getMessage());
    }

    @Test
    @DisplayName("TC-20 超出可售余量 → 40901，提示带当前余量")
    void quantityExceedsAvailable() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkRemain(NO, new BigDecimal("100.000"), new BigDecimal("95.000")));
        assertEquals(ErrorCode.QTY_NOT_ENOUGH, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("95.000"), ex.getMessage());
    }

    @Test
    @DisplayName("TC-21 恰好卖完余量：允许（边界），随后由 soldOutIfEmpty 转 SOLD_OUT")
    void quantityEqualsAvailable() {
        assertDoesNotThrow(() ->
                SaleRules.checkRemain(NO, new BigDecimal("5.000"), new BigDecimal("5.000")));
    }

    @Test
    @DisplayName("余量为 null 按 0 处理，任何正数都算超量")
    void nullAvailableTreatedAsZero() {
        BizException ex = assertThrows(BizException.class,
                () -> SaleRules.checkRemain(NO, new BigDecimal("1.000"), null));
        assertEquals(ErrorCode.QTY_NOT_ENOUGH, ex.getErrorCode());
    }

    @Test
    @DisplayName("数量为 0 或负数 → 40001")
    void nonPositiveQuantityRejected() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> SaleRules.checkPositive(BigDecimal.ZERO)).getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> SaleRules.checkPositive(new BigDecimal("-1.000"))).getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> SaleRules.checkPositive(null)).getErrorCode());
    }
}
