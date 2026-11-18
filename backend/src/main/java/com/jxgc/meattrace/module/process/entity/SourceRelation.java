package com.jxgc.meattrace.module.process.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 加工批次的来源关系（多来源投料的核心表）。
 *
 * <p>一个加工批次可以消耗多个采购批次，每个来源投了多少记一行。
 * 公开溯源页靠它把"加工批次 → 上游采购批次"这条链接起来。
 */
@Data
@TableName("source_relation")
public class SourceRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long processBatchId;

    private Long purchaseBatchId;

    private BigDecimal usedQty;

    private LocalDateTime createdAt;
}
