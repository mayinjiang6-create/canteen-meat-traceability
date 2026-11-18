package com.jxgc.meattrace.module.process.dto;

import java.math.BigDecimal;

/**
 * 来源关系按加工批次汇总的中间结果（SQL 的 GROUP BY 行）。
 *
 * <p>列表页需要显示"这批用了几个来源、一共投了多少"，逐行去查 source_relation
 * 会变成 N+1 查询；用一次 GROUP BY 拿全页数据。
 */
public class ProcessSourceSummary {

    private Long processBatchId;

    private BigDecimal inputQty;

    private Integer sourceCount;

    public Long getProcessBatchId() {
        return processBatchId;
    }

    public void setProcessBatchId(Long processBatchId) {
        this.processBatchId = processBatchId;
    }

    public BigDecimal getInputQty() {
        return inputQty;
    }

    public void setInputQty(BigDecimal inputQty) {
        this.inputQty = inputQty;
    }

    public Integer getSourceCount() {
        return sourceCount;
    }

    public void setSourceCount(Integer sourceCount) {
        this.sourceCount = sourceCount;
    }
}
