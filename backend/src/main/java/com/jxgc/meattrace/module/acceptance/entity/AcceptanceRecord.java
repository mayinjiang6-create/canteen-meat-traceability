package com.jxgc.meattrace.module.acceptance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 入库验收记录。结果 PASS / FAIL。 */
@Data
@TableName("acceptance_record")
public class AcceptanceRecord {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AC + yyyyMMdd + 3 位序号。 */
    private String acceptNo;

    private Long batchId;

    private LocalDateTime acceptTime;

    private String inspector;

    private BigDecimal temperature;

    private Boolean appearanceOk;

    private Boolean certComplete;

    private Boolean sampleRetained;

    private String result;

    private String rejectReason;

    private LocalDateTime createdAt;
}
