package com.jxgc.meattrace.module.recall.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 召回记录查询条件。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RecallQuery extends PageQuery {

    private String processBatchNo;

    /** INITIATED / COMPLETED。 */
    private String status;
}
