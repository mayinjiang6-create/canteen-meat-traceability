package com.jxgc.meattrace.module.supplier.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 供应商分页查询条件。{@code keyword} 模糊匹配名称/编码/许可证号。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplierQuery extends PageQuery {

    private String keyword;

    private String status;
}
