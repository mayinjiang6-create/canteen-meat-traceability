package com.jxgc.meattrace.module.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 销售记录。表结构与口径见 docs/tech/02-数据库设计.md 4.7。
 *
 * <p>一条记录 = 一次窗口售出，不是"某批次卖了多少"的汇总。
 * 累计销量由 {@code process_batch.output_qty - available_qty} 反推，
 * 销售记录本身只负责"哪一刻、哪个窗口、卖了多少、谁卖的"。
 *
 * <p>{@code status} 只有两态：正常售卖 {@code NORMAL}、被召回 {@code RECALLED}。
 * 召回不是删除记录——已售出的事实不能被抹掉，只能被标记。
 */
@Data
@TableName("sale_record")
public class SaleRecord {

    public static final String NORMAL = "NORMAL";
    public static final String RECALLED = "RECALLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** SL + yyyyMMdd + 3 位当日序号。 */
    private String saleNo;

    private Long processBatchId;

    /** 档口 / 窗口。 */
    private String channel;

    private BigDecimal quantity;

    private BigDecimal unitPrice;

    private LocalDateTime saleTime;

    private String operator;

    private String status;

    private LocalDateTime createdAt;
}
