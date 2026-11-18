package com.jxgc.meattrace.module.process.dto;

import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import java.util.List;

/**
 * 加工投料出参，口径见 docs/tech/03-接口文档.md 4.4。
 *
 * <p>{@code inputQty} 是各来源 {@code usedQty} 之和——**加工批次本身不存这个字段**，
 * 它由 source_relation 实时汇总得出。这样来源关系一旦变动（补录、纠错），
 * 投入量自动跟着变，不会出现"主表写的和明细对不上"。
 */
public record ProcessCreateVO(
        Long id,
        String processNo,
        String status,
        String inputQty,
        String outputQty,
        List<ProcessSourceVO> sources,
        EvidenceInfo evidence,
        Boolean replayed) {
}
