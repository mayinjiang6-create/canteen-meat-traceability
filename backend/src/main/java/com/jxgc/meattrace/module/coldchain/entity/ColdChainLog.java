package com.jxgc.meattrace.module.coldchain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 冷链温控记录。表结构见 db/migration/V2__missing_tables.sql 第 12 张表。
 *
 * <p>一个采购批次可以有多段、多次记录（产地→仓库、仓库→食堂）。
 * 记录是<b>事实</b>，超限与否只是这条事实上的一个结论字段
 * （{@code is_exceeded}），不是"记录存在的前提"——把超限记录删掉才是真的丢证据。
 */
@Data
@TableName("cold_chain_log")
public class ColdChainLog {

    public static final String ORIGIN_TO_WAREHOUSE = "ORIGIN_TO_WAREHOUSE";
    public static final String WAREHOUSE_TO_CANTEEN = "WAREHOUSE_TO_CANTEEN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** CC + yyyyMMdd + 3 位当日序号。 */
    private String logNo;

    /** 采购批次主键。 */
    private Long batchId;

    private String segment;

    private String transportNo;

    private BigDecimal temperature;

    /** 本段适用的阈值上限（℃，落库留痕，避免口径变更后无法复盘）。 */
    private BigDecimal threshold;

    /** 字段名不带 is 前缀，避免 Lombok 生成 getIsExceeded() 这种别扭的访问器。 */
    @TableField("is_exceeded")
    private Boolean exceeded;

    private LocalDateTime recordedAt;

    private String recorder;

    private String remark;

    private LocalDateTime createdAt;
}
