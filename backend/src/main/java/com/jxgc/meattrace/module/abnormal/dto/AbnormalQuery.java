package com.jxgc.meattrace.module.abnormal.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 异常列表查询条件。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AbnormalQuery extends PageQuery {

    private String processBatchNo;

    private String batchNo;

    /** OPEN / HANDLING / CLOSED。 */
    private String status;

    private String abnormalType;
}
