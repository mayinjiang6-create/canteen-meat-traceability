package com.jxgc.meattrace.chain;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 存证工作器配置，对应 application-*.yml 的 {@code chain.evidence.*}。 */
@ConfigurationProperties(prefix = "chain.evidence")
public class EvidenceProperties {

    /** 关掉后业务照常落库并生成 PENDING 任务，只是不上链——联调业务时可先关。 */
    private boolean workerEnabled = true;

    private long pollIntervalMs = 2000;

    /** 每轮最多处理几条，避免一次跑太久。 */
    private int batchSize = 5;

    /** 超过该次数置 FAILED，等人工处理。 */
    private int maxRetry = 5;

    /** 指数退避：1s / 5s / 30s / 300s，超出取最后一位。 */
    private List<Long> retryBackoffMs = List.of(1000L, 5000L, 30000L, 300000L);

    /** 卡在 SUBMITTED 超过该时长视为"结果不明"，先查链再决定（BR-14）。 */
    private long stuckTimeoutMs = 60000;

    public long backoffFor(int retryCount) {
        if (retryBackoffMs == null || retryBackoffMs.isEmpty()) {
            return 5000L;
        }
        int index = Math.min(Math.max(retryCount - 1, 0), retryBackoffMs.size() - 1);
        return retryBackoffMs.get(index);
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public List<Long> getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(List<Long> retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public long getStuckTimeoutMs() {
        return stuckTimeoutMs;
    }

    public void setStuckTimeoutMs(long stuckTimeoutMs) {
        this.stuckTimeoutMs = stuckTimeoutMs;
    }
}
