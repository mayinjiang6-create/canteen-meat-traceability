package com.jxgc.meattrace.module.process.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 加工批次查询条件。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProcessQuery extends PageQuery {

    private String processNo;

    private String status;

    private String meatType;
}
