package com.jxgc.meattrace.module.process.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 加工批次。
 *
 * <p><b>本轮只读</b>：加工投料接口尚未实现（属阶段 B），这个实体只用于公开溯源页
 * 沿 {@code source_relation} 回溯上游采购批次。不要在这里加写逻辑。
 */
@Data
@TableName("process_batch")
public class ProcessBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PR + yyyyMMdd + 3 位，同时就是追溯码。 */
    private String processNo;

    private String meatType;

    private BigDecimal outputQty;

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
