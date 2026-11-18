package com.jxgc.meattrace.module.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 采购批次。状态 PENDING_ACCEPT / PASSED / REJECTED / CLOSED。 */
@Data
@TableName("purchase_batch")
public class PurchaseBatch {

    public static final String PENDING_ACCEPT = "PENDING_ACCEPT";
    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";
    public static final String CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PB + yyyyMMdd + 3 位序号。 */
    private String batchNo;

    private Long supplierId;

    private String meatType;

    private String origin;

    private String slaughterNo;

    private String quarantineNo;

    private BigDecimal quantity;

    /** 可用量；拒收后归零，且不参与采购量守恒校验。 */
    private BigDecimal availableQty;

    private String unit;

    private BigDecimal unitPrice;

    private LocalDateTime arriveTime;

    private BigDecimal coldChainTemp;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
