package com.jxgc.meattrace.module.trace.dto;

import java.util.List;

/**
 * 溯源概览。数量一律字符串（3 位小数），避免 JS 浮点误差。
 *
 * @param sourceCount   上游来源批次数（加工批次多来源时 &gt; 1）
 * @param totalInputQty 投入总量
 * @param quarantineNos 涉及的全部检疫合格证号——这是食堂最关心的信息
 */
public record SummaryVO(
        int sourceCount,
        String totalInputQty,
        String outputQty,
        String soldQty,
        List<String> quarantineNos) {
}
