package com.jxgc.meattrace.module.trace.service;

import com.jxgc.meattrace.chain.ChainEvent;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.entity.TxReceipt;

/**
 * 一条事件的核验结果。
 *
 * @param snapshot 本地快照（含 canonicalText 与原始 digest）
 * @param status   四类状态之一
 * @param onChain  链上记录，UNCONFIRMED/UNREACHABLE 时为 null
 * @param receipt  交易回执，可能为 null（例如靠"查链自愈"确认的事件不会产生回执）
 */
public record VerifiedEvent(
        EventSnapshot snapshot,
        String status,
        ChainEvent onChain,
        TxReceipt receipt) {
}
