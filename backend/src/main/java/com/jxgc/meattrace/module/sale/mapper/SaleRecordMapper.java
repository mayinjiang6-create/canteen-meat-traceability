package com.jxgc.meattrace.module.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxgc.meattrace.module.sale.entity.SaleRecord;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SaleRecordMapper extends BaseMapper<SaleRecord> {

    /**
     * 召回时把该加工批次的全部销售记录批量置 {@code RECALLED}（BR-09）。
     *
     * <p>只更新 {@code NORMAL} 的行：重复召回不会把 {@code affected_sales} 算成两倍，
     * 也不会因为"再改一次同样的值"产生无意义的行锁。
     *
     * @return 受影响行数 = 本次真正被标记的销售笔数
     */
    @Update("""
            UPDATE sale_record
               SET status = 'RECALLED'
             WHERE process_batch_id = #{processBatchId}
               AND status = 'NORMAL'
            """)
    int markRecalledByProcess(@Param("processBatchId") Long processBatchId);

    /** 该批次已售出总量（召回时记入 {@code recall_record.recalled_qty}）。 */
    @Select("""
            SELECT COALESCE(SUM(quantity), 0)
              FROM sale_record
             WHERE process_batch_id = #{processBatchId}
            """)
    BigDecimal sumSoldQty(@Param("processBatchId") Long processBatchId);

    /** 该批次销售笔数（含已召回），召回时用于说明波及范围。 */
    @Select("SELECT COUNT(*) FROM sale_record WHERE process_batch_id = #{processBatchId}")
    int countByProcess(@Param("processBatchId") Long processBatchId);
}
