package com.jxgc.meattrace.module.process.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 加工批次。状态见 docs/tech/02-数据库设计.md 6.2。
 *
 * <p>{@code process_no} 同时就是对外公示的追溯码：一袋净菜包装上印的码，
 * 扫出来的就是这条记录及其上游来源关系。
 */
@Data
@TableName("process_batch")
public class ProcessBatch {

    public static final String CREATED = "CREATED";
    public static final String ON_SALE = "ON_SALE";
    public static final String SOLD_OUT = "SOLD_OUT";
    public static final String ABNORMAL = "ABNORMAL";
    public static final String RECALLED = "RECALLED";
    public static final String CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PR + yyyyMMdd + 3 位，同时就是追溯码。 */
    private String processNo;

    private String meatType;

    private BigDecimal outputQty;

    /** 可售余量；加工时为 outputQty，销售时递减。 */
    private BigDecimal availableQty;

    private String unit;

    private LocalDateTime processTime;

    private String workshop;

    private String operator;

    private String retainSampleNo;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
