package com.jxgc.meattrace.module.sale.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 销售记录查询条件。{@code processBatchNo} 与 {@code processBatchId} 二选一即可。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SaleQuery extends PageQuery {

    private Long processBatchId;

    private String processBatchNo;

    private String channel;

    /** NORMAL / RECALLED。 */
    private String status;
}
