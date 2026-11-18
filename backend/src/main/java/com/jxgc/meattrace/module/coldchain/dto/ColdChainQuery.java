package com.jxgc.meattrace.module.coldchain.dto;

import com.jxgc.meattrace.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 冷链记录查询条件。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ColdChainQuery extends PageQuery {

    private String batchNo;

    /** ORIGIN_TO_WAREHOUSE / WAREHOUSE_TO_CANTEEN。 */
    private String segment;

    /** true 只看超限记录；不传看全部。 */
    private Boolean exceeded;
}
