package com.jxgc.meattrace.module.sequence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxgc.meattrace.module.sequence.entity.BizSequence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 发号器 SQL。
 *
 * <p><b>两条写语句都不能拆</b>，配套 {@link #lastIssued()} 才构成一次发号：
 * {@code LAST_INSERT_ID(expr)} 把值写进当前连接的会话变量，
 * {@code SELECT LAST_INSERT_ID()} 再取回来，所以这三条必须跑在同一个连接上
 * （由调用方的 {@code @Transactional(Propagation.MANDATORY)} 保证）。
 *
 * <p><b>为什么冷启动用 INSERT … ON DUPLICATE KEY UPDATE</b>：
 * 2026-11-18 的并发实测里，原来「先 SELECT 判断有没有、没有就 INSERT」的写法在
 * 4 并发下会死锁——INSERT 撞重复键时 InnoDB 会给已存在的行加 **S 锁**并持有到事务结束，
 * 两个事务各持 S 锁、又都要升级成 X 锁做自增，互等成环。
 * 改成 ON DUPLICATE KEY UPDATE 后直接拿 X 锁，没有升级动作，也就没有环。
 */
public interface BizSequenceMapper extends BaseMapper<BizSequence> {

    /**
     * 热路径：序列已存在时原子自增。
     *
     * <p>UPDATE 是当前读，行锁把并发请求排队，不会像快照读那样"大家都读到 7"。
     *
     * @return 受影响行数；0 表示该序列还没建（冷启动，转 {@link #bumpOrSeed}）
     */
    @Update("UPDATE biz_sequence SET next_val = LAST_INSERT_ID(next_val + 1) WHERE seq_name = #{seqName}")
    int bump(@Param("seqName") String seqName);

    /**
     * 冷路径：建序列并直接发出第一个号（种子 + 1）。
     *
     * <p>两个分支都要把值写进 LAST_INSERT_ID：
     * INSERT 分支用 {@code LAST_INSERT_ID(seed + 1)}，UPDATE 分支用
     * {@code LAST_INSERT_ID(next_val + 1)}——这样无论走哪条路，调用方都能取到本次发的号。
     */
    @Insert("""
            INSERT INTO biz_sequence (seq_name, next_val)
            VALUES (#{seqName}, LAST_INSERT_ID(#{seed} + 1))
            ON DUPLICATE KEY UPDATE next_val = LAST_INSERT_ID(next_val + 1)
            """)
    int bumpOrSeed(@Param("seqName") String seqName, @Param("seed") long seed);

    /** 取回本次发的号（连接级会话变量，必须紧接在上面的写语句之后）。 */
    @Select("SELECT LAST_INSERT_ID()")
    long lastIssued();
}
