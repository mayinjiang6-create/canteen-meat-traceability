package com.jxgc.meattrace.module.acceptance.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 验收记录查询条件。{@code batchId} 与 {@code batchNo} 二选一即可。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AcceptanceQuery extends PageQuery {

    private Long batchId;

    private String batchNo;

    private String result;
}
