package com.jxgc.meattrace.chain;

/**
 * 链上一条事件记录的读结果。
 *
 * @param batchId        业务批次号
 * @param eventType      事件类型
 * @param bizTime        业务时间（Unix 秒）
 * @param dataDigest     数据摘要（64 位小写 hex）
 * @param submitter      提交账户
 * @param createdAtMillis 上链时间（<b>毫秒</b>：FISCO BCOS 3.x 的 block.timestamp 为毫秒）
 * @param exists         是否已写入
 */
public record ChainEvent(
        String batchId,
        String eventType,
        long bizTime,
        String dataDigest,
        String submitter,
        long createdAtMillis,
        boolean exists) {
}
