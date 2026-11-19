package com.jxgc.meattrace.module.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ProcessBatchMapper extends BaseMapper<ProcessBatch> {

    /**
     * 销售扣减可售余量（与采购侧的乐观扣减同一口径，TECH-02 §7）。
     *
     * <p>三个条件都写进 WHERE，让数据库在行锁内一次性判定：
     * ① 行存在；② 仍处于 {@code ON_SALE}（BR-06，异常冻结/召回/售罄在这里被挡住）；
     * ③ 余量够扣（BR-07）。受影响行数为 0 就是"没卖成"，具体原因由调用方当前读后判定。
     *
     * <p>不写成"先查余量再更新"：两条并发销售同时读到 5.000、各卖 5.000，
     * 一个先成功把余量清零，另一个必须失败——这件事只有数据库能在同一把行锁里裁决。
     *
     * @return 受影响行数，0 表示状态不允许或余量不足
     */
    @Update("""
            UPDATE process_batch
               SET available_qty = available_qty - #{qty},
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'ON_SALE'
               AND available_qty >= #{qty}
            """)
    int deductAvailable(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /**
     * 余量扣到 0 时置 {@code SOLD_OUT}（TECH-02 6.2：ON_SALE → SOLD_OUT）。
     *
     * <p>与扣减拆成两条语句，理由同采购侧的 {@code closeIfEmpty}：
     * MySQL 单表 UPDATE 的 SET 子句自左向右生效，同一语句里既改 available_qty 又读它，
     * 结果取决于赋值顺序——这种 SQL 不该出现在业务代码里。
     */
    @Update("""
            UPDATE process_batch
               SET status = 'SOLD_OUT',
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'ON_SALE'
               AND available_qty = 0
            """)
    int soldOutIfEmpty(@Param("id") Long id);

    /**
     * 当前读余量（{@code FOR UPDATE}），只在**失败路径**用于给准确提示。
     *
     * <p>预校验用的是普通 SELECT，在 REPEATABLE READ 下读到的是事务开始时的快照；
     * 并发卖光时它会给出"还剩 5.000"这种过期数字，导致提示自相矛盾。
     */
    @Select("SELECT available_qty FROM process_batch WHERE id = #{id} FOR UPDATE")
    BigDecimal lockAvailable(@Param("id") Long id);
}
