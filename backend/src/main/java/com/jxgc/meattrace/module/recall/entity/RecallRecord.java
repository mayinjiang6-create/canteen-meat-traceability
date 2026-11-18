package com.jxgc.meattrace.module.recall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 召回记录。表结构见 db/migration/V2__missing_tables.sql 第 14 张表。
 *
 * <p>召回是"一件事"，不是一个状态字段：{@code recall_record} 记下谁在什么时候、
 * 因为什么、召回了多少、波及几笔销售；加工批次与销售记录的状态随之被改写。
 * 两条信息都要留——状态会被后续操作覆盖，记录不会。
 */
@Data
@TableName("recall_record")
public class RecallRecord {

    public static final String INITIATED = "INITIATED";
    public static final String COMPLETED = "COMPLETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** RC + yyyyMMdd + 3 位当日序号。 */
    private String recallNo;

    private Long processBatchId;

    private String reason;

    private String scope;

    /** 受影响销售笔数（= 本次被置为 RECALLED 的行数）。 */
    private Integer affectedSales;

    /** 已售出、需要追回的数量。 */
    private BigDecimal recalledQty;

    private String operator;

    private LocalDateTime recallTime;

    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
