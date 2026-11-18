package com.jxgc.meattrace.module.sequence.service;

import com.jxgc.meattrace.module.sequence.mapper.BizSequenceMapper;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务编号发号器。
 *
 * <p><b>为什么需要它</b>：编号若用「查当日最大值 + 1」生成，并发下必然撞号。
 * 2026-11-18 的并发实测里，4 个同时投料请求算出了同一个 {@code PR20261118006}，
 * 3 个撞唯一键直接 500。这不是业务逻辑错，是发号方式错。
 *
 * <p><b>怎么解决的</b>：
 * <ol>
 *   <li>热路径一条 {@code UPDATE ... SET next_val = LAST_INSERT_ID(next_val + 1)}，
 *       当前读 + 行锁把并发请求排队；</li>
 *   <li>冷路径（当天第一次发号）用 {@code INSERT ... ON DUPLICATE KEY UPDATE}，
 *       直接拿 X 锁，避免"撞重复键拿 S 锁再升级"的经典死锁。</li>
 * </ol>
 *
 * <p><b>约束</b>：必须运行在调用方的事务里（{@link Propagation#MANDATORY}）——
 * {@code LAST_INSERT_ID()} 是**连接级**会话变量，两条语句跑在不同连接上就会读到别人的号。
 */
@Service
public class SequenceService {

    private static final Logger log = LoggerFactory.getLogger(SequenceService.class);

    private final BizSequenceMapper mapper;

    public SequenceService(BizSequenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取下一个号码。
     *
     * @param seqName  序列名（同一类共用，如 {@code PROCESS_NO:20261118}）
     * @param prefix   号码前缀，如 {@code PB}、{@code SUP-}
     * @param datePart 日期段，如 {@code 20261118}；没有日期概念时传空串
     * @param width    序号补零宽度，如 3 → {@code 001}
     * @param seed     序列首次创建时的种子（= 业务表现有最大序号）；只在当天第一次发号时才会被求值
     * @return 形如 {@code PR20261118004} 的业务编号
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String seqName, String prefix, String datePart, int width, LongSupplier seed) {
        if (mapper.bump(seqName) == 0) {
            // 冷启动：序列还没建。种子查的是业务表当前最大号，所以历史数据不用手工迁移
            long seedValue = Math.max(seed.getAsLong(), 0L);
            mapper.bumpOrSeed(seqName, seedValue);
            log.info("发号器初始化：{} 起始值 {}", seqName, seedValue + 1);
        }
        long value = mapper.lastIssued();
        if (value <= 0) {
            throw new IllegalStateException("发号失败，序列返回值异常：" + seqName + " → " + value);
        }
        return prefix + (datePart == null ? "" : datePart) + String.format("%0" + width + "d", value);
    }
}
