package com.jxgc.meattrace.module.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 事件快照（上链原件）。
 *
 * <p>{@code canonicalText} 是规范化原串，核验时**必须**用它重算摘要，
 * 不能拿业务实体的字段重新拼接——否则字段顺序或格式一变，历史数据全部核验失败。
 */
@Data
@TableName("event_snapshot")
public class EventSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 32 字节事件的 64 位小写 hex。 */
    private String eventId;

    private String batchId;

    /** SUPPLIER_REG / PURCHASE_CREATE / ACCEPT_RESULT / COLD_CHAIN / PROCESS_CREATE /
     *  SALE_CREATE / DISPOSAL / RECALL / STOCK_CHECK。 */
    private String eventType;

    private LocalDateTime bizTime;

    private BigDecimal quantity;

    private String unit;

    private String payloadJson;

    private String canonicalText;

    private String dataDigest;

    private String schemaVersion;

    private LocalDateTime createdAt;
}
