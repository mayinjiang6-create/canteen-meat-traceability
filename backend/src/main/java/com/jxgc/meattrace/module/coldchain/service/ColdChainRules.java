package com.jxgc.meattrace.module.coldchain.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.coldchain.entity.ColdChainLog;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 冷链判定的**纯规则**（不碰数据库，便于单测）。
 *
 * <p>对应 TECH-02 第 8 节 BR-08：温度超出品类阈值 → 生成异常。
 * 这里只负责"超没超、超得有多严重"，落库与事件生成在 Service 里。
 */
public final class ColdChainRules {

    public static final Set<String> SEGMENTS = Set.of(
            ColdChainLog.ORIGIN_TO_WAREHOUSE,
            ColdChainLog.WAREHOUSE_TO_CANTEEN);

    /** 超阈值 0.5℃ 以内算轻微，2℃ 以内算中等，再多算严重。 */
    private static final BigDecimal MINOR = new BigDecimal("0.5");
    private static final BigDecimal SERIOUS = new BigDecimal("2.0");

    private ColdChainRules() {
    }

    /** 运输段只允许两段：产地→仓库、仓库→食堂（与表上的 CHECK 约束一致）。 */
    public static void checkSegment(String segment) {
        if (segment == null || !SEGMENTS.contains(segment)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "运输段只能是 ORIGIN_TO_WAREHOUSE（产地→仓库）或 WAREHOUSE_TO_CANTEEN（仓库→食堂）");
        }
    }

    /** 严格大于阈值才算超限；恰好等于阈值是合规的（冻肉 −15.0℃ 不该报异常）。 */
    public static boolean isExceeded(BigDecimal temperature, BigDecimal threshold) {
        if (temperature == null || threshold == null) {
            return false;
        }
        return temperature.compareTo(threshold) > 0;
    }

    /**
     * 超限等级：按超出的度数分级，而不是一律 HIGH。
     *
     * <p>理由很实际——4.1℃ 和 12℃ 都叫"温度超标"，但前者可能是开门瞬间的波动，
     * 后者意味着整批肉可能已经不能要了。一律 HIGH 会让真正的严重事件被淹没。
     */
    public static String levelFor(BigDecimal temperature, BigDecimal threshold) {
        if (!isExceeded(temperature, threshold)) {
            return "LOW";
        }
        BigDecimal delta = temperature.subtract(threshold);
        if (delta.compareTo(MINOR) <= 0) {
            return "LOW";
        }
        if (delta.compareTo(SERIOUS) <= 0) {
            return "MEDIUM";
        }
        return "HIGH";
    }
}
