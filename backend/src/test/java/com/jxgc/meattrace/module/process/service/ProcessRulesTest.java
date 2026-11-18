package com.jxgc.meattrace.module.process.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.dto.ProcessCreateRequest.SourceItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 加工投料校验规则的单测（对应 TECH-07 的 TC-15 / TC-16 / TC-17）。
 *
 * <p>这几条规则被单独抽成 {@link ProcessRules}，就是为了能这样一条一条钉住：
 * 规则本身是纯函数，不需要起 Spring、不需要连数据库，跑一次几百毫秒。
 */
class ProcessRulesTest {

    private static SourceItem item(String no, String qty) {
        return new SourceItem(no, new BigDecimal(qty));
    }

    @Test
    @DisplayName("TC-16 重复引用同一采购批次 → 40904")
    void duplicateSourceRejected() {
        List<SourceItem> sources = List.of(
                item("PB20261118001", "10.000"),
                item("PB20261118002", "20.000"),
                item("PB20261118001", "5.000"));

        BizException ex = assertThrows(BizException.class, () -> ProcessRules.normalizeSources(sources));
        assertEquals(ErrorCode.SOURCE_DUPLICATED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("PB20261118001"), "提示里要指出是哪个批次重复了");
    }

    @Test
    @DisplayName("来源按批次号升序排列：并发投料时锁顺序必须全局一致")
    void sourcesSortedByBatchNo() {
        List<SourceItem> sources = List.of(
                item("PB20261118004", "30.000"),
                item("PB20261118001", "80.000"),
                item("PB20261118002", "40.000"));

        List<SourceItem> normalized = ProcessRules.normalizeSources(sources);
        assertEquals(List.of("PB20261118001", "PB20261118002", "PB20261118004"),
                normalized.stream().map(SourceItem::purchaseBatchNo).toList());
        // 排序不能改变任何一条的数量
        assertEquals(0, ProcessRules.totalInput(normalized).compareTo(new BigDecimal("150.000")));
    }

    @Test
    @DisplayName("TC-15 单来源投入超过可用余量 → 40901，提示带剩余量")
    void usedQtyExceedsAvailable() {
        BizException ex = assertThrows(BizException.class,
                () -> ProcessRules.checkRemain("PB20261118002", new BigDecimal("200.000"), new BigDecimal("40.000")));
        assertEquals(ErrorCode.QTY_NOT_ENOUGH, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("40.000"), "提示要给出当前实际剩余量，否则验收人无法处理");
    }

    @Test
    @DisplayName("投入恰好等于余量：允许（边界），扣完由 closeIfEmpty 结案")
    void usedQtyEqualsAvailable() {
        assertDoesNotThrow(() ->
                ProcessRules.checkRemain("PB20261118002", new BigDecimal("40.000"), new BigDecimal("40.000")));
    }

    @Test
    @DisplayName("余量为 null 按 0 处理，不允许投入")
    void nullAvailableTreatedAsZero() {
        BizException ex = assertThrows(BizException.class,
                () -> ProcessRules.checkRemain("PB20261118003", new BigDecimal("1.000"), null));
        assertEquals(ErrorCode.QTY_NOT_ENOUGH, ex.getErrorCode());
    }

    @Test
    @DisplayName("TC-17 产出大于投入总量 → 40001（出品率不得大于 1）")
    void outputExceedsInput() {
        BizException ex = assertThrows(BizException.class,
                () -> ProcessRules.checkOutput(new BigDecimal("150.000"), new BigDecimal("120.000")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("120.000"), "提示要给出投入总量，方便回头核数");
    }

    @Test
    @DisplayName("产出等于投入：允许（边界）")
    void outputEqualsInput() {
        assertDoesNotThrow(() ->
                ProcessRules.checkOutput(new BigDecimal("120.000"), new BigDecimal("120.000")));
    }

    @Test
    @DisplayName("空来源列表 → 40001")
    void emptySourcesRejected() {
        BizException ex = assertThrows(BizException.class, () -> ProcessRules.normalizeSources(List.of()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("投入总量按 DECIMAL 累加，不出现浮点尾差")
    void totalInputIsExact() {
        List<SourceItem> sources = List.of(
                item("PB20261118001", "0.100"),
                item("PB20261118002", "0.200"));
        assertEquals("0.300", ProcessRules.totalInput(sources).toPlainString());
    }
}
