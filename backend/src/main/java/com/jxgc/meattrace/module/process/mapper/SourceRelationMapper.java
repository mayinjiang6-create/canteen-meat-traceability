package com.jxgc.meattrace.module.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxgc.meattrace.module.process.dto.ProcessSourceSummary;
import com.jxgc.meattrace.module.process.entity.SourceRelation;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SourceRelationMapper extends BaseMapper<SourceRelation> {

    /**
     * 按加工批次汇总投入量与来源数，供列表页一次取全（避免 N+1 查询）。
     *
     * <p>{@code SUM(used_qty)} 走 DECIMAL，不会出现浮点尾差。
     */
    @Select("""
            <script>
            SELECT process_batch_id AS processBatchId,
                   SUM(used_qty)   AS inputQty,
                   COUNT(*)        AS sourceCount
              FROM source_relation
             WHERE process_batch_id IN
             <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY process_batch_id
            </script>
            """)
    List<ProcessSourceSummary> summariseByProcessIds(@Param("ids") List<Long> ids);
}
