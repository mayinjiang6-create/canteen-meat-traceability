package com.jxgc.meattrace.module.coldchain.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 冷链温度阈值，对应 {@code application-*.yml} 的 {@code trace.cold-chain.*}。
 *
 * <p><b>阈值为什么按品类而不是全局一个</b>：冷鲜猪肉与冷冻肉的合规区间完全不同
 * （前者 0~4℃，后者 −18℃ 以下）。用一个全局阈值会出现"冷冻肉 −16℃ 被判超标"这种
 * 荒唐报警，报警一旦不可信，整条冷链记录就没人看了。
 *
 * <p>匹配用"品类名包含关键字"而不是精确等值：食堂录入的品类名是自由文本
 * （"冷鲜猪肉""猪后腿""冷冻牛腩"），精确匹配必然漏。
 */
@ConfigurationProperties(prefix = "trace.cold-chain")
public class ColdChainProperties {

    private BigDecimal porkMaxTemp = new BigDecimal("4.0");

    private BigDecimal beefMaxTemp = new BigDecimal("4.0");

    private BigDecimal frozenMaxTemp = new BigDecimal("-15.0");

    /** 未识别品类时的兜底阈值，取冷鲜肉上限。 */
    private BigDecimal defaultMaxTemp = new BigDecimal("4.0");

    /** 品类 → 阈值上限（℃）。 */
    public BigDecimal thresholdFor(String meatType) {
        if (meatType == null || meatType.isBlank()) {
            return defaultMaxTemp;
        }
        if (meatType.contains("冻")) {
            return frozenMaxTemp;
        }
        if (meatType.contains("牛")) {
            return beefMaxTemp;
        }
        if (meatType.contains("猪")) {
            return porkMaxTemp;
        }
        return defaultMaxTemp;
    }

    public BigDecimal getPorkMaxTemp() {
        return porkMaxTemp;
    }

    public void setPorkMaxTemp(BigDecimal porkMaxTemp) {
        this.porkMaxTemp = porkMaxTemp;
    }

    public BigDecimal getBeefMaxTemp() {
        return beefMaxTemp;
    }

    public void setBeefMaxTemp(BigDecimal beefMaxTemp) {
        this.beefMaxTemp = beefMaxTemp;
    }

    public BigDecimal getFrozenMaxTemp() {
        return frozenMaxTemp;
    }

    public void setFrozenMaxTemp(BigDecimal frozenMaxTemp) {
        this.frozenMaxTemp = frozenMaxTemp;
    }

    public BigDecimal getDefaultMaxTemp() {
        return defaultMaxTemp;
    }

    public void setDefaultMaxTemp(BigDecimal defaultMaxTemp) {
        this.defaultMaxTemp = defaultMaxTemp;
    }
}
