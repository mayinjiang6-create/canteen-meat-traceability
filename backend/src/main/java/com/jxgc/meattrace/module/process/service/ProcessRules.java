package com.jxgc.meattrace.module.process.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.dto.ProcessCreateRequest.SourceItem;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加工投料的**纯校验规则**（不碰数据库，便于单测逐条覆盖）。
 *
 * <p>为什么单独抽出来：这几条规则是全项目唯一值得逐行讲的业务判断，
 * 混在 Service 里会与事务、Mapper 缠在一起，既不好测也不好按条陈述。
 *
 * <p>规则编号见 docs/tech/02-数据库设计.md 第 8 节：
 * <ul>
 *   <li>BR-03 同一加工批次不得重复引用同一采购批次 → 40904</li>
 *   <li>BR-02 单个来源投入量 ≤ 该来源可用量 → 40901</li>
 *   <li>BR-05 产出量 ≤ 投入总量 → 40001</li>
 * </ul>
 */
public final class ProcessRules {

    private ProcessRules() {
    }

    /**
     * 校验来源列表并**按批次号升序**返回。
     *
     * <p>排序不是装饰：并发投料时两个事务若以相反顺序扣减 A、B 两个批次，
     * 就会互相等对方的行锁形成死锁。统一升序后，锁获取顺序全局一致。
     */
    public static List<SourceItem> normalizeSources(List<SourceItem> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少需要一个来源批次");
        }
        Set<String> seen = new HashSet<>();
        for (SourceItem item : sources) {
            if (!seen.add(item.purchaseBatchNo())) {
                throw new BizException(ErrorCode.SOURCE_DUPLICATED,
                        "同一来源批次不可重复引用：" + item.purchaseBatchNo());
            }
        }
        return sources.stream()
                .sorted(Comparator.comparing(SourceItem::purchaseBatchNo))
                .toList();
    }

    /** 投入总量 = Σ 各来源 usedQty。 */
    public static BigDecimal totalInput(List<SourceItem> sources) {
        return sources.stream()
                .map(SourceItem::usedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** BR-05：产出量不得大于投入总量（出品率 ≤ 1）。 */
    public static void checkOutput(BigDecimal outputQty, BigDecimal inputQty) {
        if (outputQty == null || outputQty.compareTo(inputQty) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "产出量不得大于投入总量（投入 " + DigestUtil.scale(inputQty) + " kg）");
        }
    }

    /** BR-02：单来源预校验。真正的并发安全由条件 UPDATE 兜底。 */
    public static void checkRemain(String purchaseBatchNo, BigDecimal usedQty, BigDecimal availableQty) {
        BigDecimal remain = availableQty == null ? BigDecimal.ZERO : availableQty;
        if (usedQty.compareTo(remain) > 0) {
            throw new BizException(ErrorCode.QTY_NOT_ENOUGH,
                    "来源批次 " + purchaseBatchNo + " 可用余量不足，当前剩余 " + DigestUtil.scale(remain) + " kg");
        }
    }
}
