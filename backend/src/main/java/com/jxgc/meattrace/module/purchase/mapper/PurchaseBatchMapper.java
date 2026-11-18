package com.jxgc.meattrace.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxgc.meattrace.module.purchase.entity.PurchaseBatch;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PurchaseBatchMapper extends BaseMapper<PurchaseBatch> {

    /**
     * 乐观扣减可用量（TECH-02 §7 的口径，**不要改成 SELECT … FOR UPDATE**）。
     *
     * <p>把"够不够扣"的判断放进 WHERE，交给数据库在行锁内原子完成：
     * 两条并发请求同时扣同一批次的 40 kg、余量只有 60 kg 时，
     * 必有且只有一条能把 60 变成 20，另一条 affected=0。
     *
     * <p>把 {@code status='PASSED'} 也写进 WHERE，是 BR-01/BR-04 的最后一道闸：
     * 即便上游漏判，扣减语句本身也不会让拒收/未验收批次流进加工环节。
     *
     * @return 受影响行数，0 表示余量不足或状态不允许
     */
    @Update("""
            UPDATE purchase_batch
               SET available_qty = available_qty - #{qty},
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PASSED'
               AND available_qty >= #{qty}
            """)
    int deductAvailable(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /**
     * 可用量扣到 0 时结案（TECH-02 6.1：PASSED → CLOSED）。
     *
     * <p>刻意与扣减拆成两条语句，而不是在扣减里写
     * {@code SET status = IF(available_qty - #{qty} = 0, 'CLOSED', status)}：
     * MySQL 单表 UPDATE 的 SET 子句是自左向右生效的，同一语句里既改 available_qty 又读它，
     * 结果取决于赋值顺序——这种"看顺序才知道对不对"的 SQL 不该出现在答辩代码里。
     */
    @Update("""
            UPDATE purchase_batch
               SET status = 'CLOSED',
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PASSED'
               AND available_qty = 0
            """)
    int closeIfEmpty(@Param("id") Long id);

    /**
     * 当前读余量（{@code FOR UPDATE}）。
     *
     * <p>只在**失败路径**用：普通 SELECT 在 REPEATABLE READ 下读到的是事务开始时的快照，
     * 并发请求被别的线程扣光时会给出"剩余 60.000"这种明显不对的提示。
     * 这里用当前读拿真实值，让提示和数据库此刻的状态对得上。
     *
     * <p>别拿它做预校验——那会变成悲观锁，违背 TECH-02 §7 的乐观扣减口径。
     */
    @Select("SELECT available_qty FROM purchase_batch WHERE id = #{id} FOR UPDATE")
    BigDecimal lockAvailable(@Param("id") Long id);
}
