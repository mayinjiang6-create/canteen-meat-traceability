package com.jxgc.meattrace.module.purchase.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 采购批次分页查询条件。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseQuery extends PageQuery {

    private String batchNo;

    private Long supplierId;

    private String status;

    private String meatType;
}
