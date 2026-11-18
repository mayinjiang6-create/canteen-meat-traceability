package com.jxgc.meattrace.chain;

/**
 * append 的结果。
 *
 * @param txHash      交易哈希
 * @param returnCode  合约返回码：0 新增 / 1 幂等（同摘要）/ 2 冲突（异摘要，保留原记录）
 * @param blockNumber 区块号
 * @param gasUsed     消耗 gas
 * @param raw         原始响应，排查问题时用
 */
public record AppendResult(
        String txHash,
        int returnCode,
        Long blockNumber,
        Long gasUsed,
        String raw) {

    public boolean appended() {
        return returnCode == ChainCodec.RESULT_APPENDED;
    }

    public boolean duplicate() {
        return returnCode == ChainCodec.RESULT_DUPLICATE;
    }

    public boolean conflict() {
        return returnCode == ChainCodec.RESULT_CONFLICT;
    }

    /** 新增与幂等都算存证成功；冲突不算。 */
    public boolean success() {
        return appended() || duplicate();
    }
}
