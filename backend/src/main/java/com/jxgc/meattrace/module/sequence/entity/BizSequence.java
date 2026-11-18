package com.jxgc.meattrace.module.sequence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 业务编号序列（发号器）。
 *
 * <p>不是业务表：它只存"某个前缀的号码发到第几号"，与溯源、存证都无关。
 * 删掉它最多让编号从头开始，不会影响任何已上链的数据。
 */
@Data
@TableName("biz_sequence")
public class BizSequence {

    /** 序列名，如 {@code PROCESS_NO:20261118}、{@code SUPPLIER}。 */
    @TableId(value = "seq_name", type = IdType.INPUT)
    private String seqName;

    /** 下一个待发号码；发号时先 +1 再取回。 */
    private Long nextVal;

    private LocalDateTime updatedAt;
}
