package com.jxgc.meattrace.module.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 交易回执。
 *
 * <p><b>注意两套 status 约定</b>：
 * WeBASE-Front {@code handleWithSign} 的 {@code status=0} 表示交易提交成功；
 * 而标准 JSON-RPC {@code eth_getTransactionReceipt} 的 {@code status=0x1} 才表示执行成功。
 * 本表 {@code txStatus} 存的是**回执值**，实测为 {@code "0x1"}。
 * （V1 建表注释写"0x0 成功"是错误的，历史遗留，以实测为准。）
 */
@Data
@TableName("tx_receipt")
public class TxReceipt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String txHash;

    private Long blockNumber;

    private String blockHash;

    private String contractAddress;

    /** 回执状态，成功为 "0x1"。 */
    private String txStatus;

    private Long gasUsed;

    private String submitterAddress;

    private String rawReceipt;

    private LocalDateTime receiptTime;

    private LocalDateTime createdAt;
}
