package com.jxgc.meattrace.module.trace.dto;

import java.util.Map;

/**
 * 时间线上的一个节点。
 *
 * @param eventType    机器可读的事件类型
 * @param eventLabel   中文标签（页面直接显示）
 * @param batchNo      该事件所属批次号；加工批次回溯时用于区分是哪个上游批次
 * @param verifyStatus 四类核验状态之一
 * @param txHash       交易哈希；靠"查链自愈"确认的事件可能为 null
 */
public record TimelineItem(
        String eventId,
        String eventType,
        String eventLabel,
        String bizTime,
        String digest,
        String verifyStatus,
        String txHash,
        Long blockNumber,
        String batchNo,
        Map<String, Object> details) {
}
