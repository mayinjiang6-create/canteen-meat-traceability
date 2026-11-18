package com.jxgc.meattrace.module.abnormal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 异常事件与处置。表结构见 db/migration/V2__missing_tables.sql 第 13 张表。
 *
 * <p>一条异常要么挂在采购批次上（到货温度、票证缺失），要么挂在加工批次上
 * （抽检不合格、感官异常），由数据库 CHECK 保证至少挂一个。
 *
 * <p>状态机：{@code OPEN}（已登记）→ {@code HANDLING}（处置中）→ {@code CLOSED}（已结案）。
 * 只有 {@code CLOSED} 才会写 {@code closed_at}，且处置结论一旦写下就不再改——
 * 要改只能再登记一条新的异常，历史不可篡改。
 */
@Data
@TableName("abnormal_event")
public class AbnormalEvent {

    public static final String OPEN = "OPEN";
    public static final String HANDLING = "HANDLING";
    public static final String CLOSED = "CLOSED";

    public static final String TEMPERATURE_EXCEEDED = "TEMPERATURE_EXCEEDED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AB + yyyyMMdd + 3 位当日序号。 */
    private String abnormalNo;

    private Long batchId;

    private Long processBatchId;

    private String abnormalType;

    private String level;

    private String description;

    private String action;

    private String handler;

    private String conclusion;

    private String status;

    private LocalDateTime occurredAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
