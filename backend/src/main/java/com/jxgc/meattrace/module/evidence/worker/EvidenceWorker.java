package com.jxgc.meattrace.module.evidence.worker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxgc.meattrace.chain.AppendResult;
import com.jxgc.meattrace.chain.ChainProperties;
import com.jxgc.meattrace.chain.ChainRejectedException;
import com.jxgc.meattrace.chain.ChainUnavailableException;
import com.jxgc.meattrace.chain.ChainWriteClient;
import com.jxgc.meattrace.chain.EvidenceProperties;
import com.jxgc.meattrace.chain.RpcReadClient;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.mapper.EventSnapshotMapper;
import com.jxgc.meattrace.module.evidence.service.EvidenceTaskService;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存证工作器：把 PENDING 任务送上链，并把结果写回。
 *
 * <p><b>为什么必须异步</b>：上链 HTTP 要 100ms~2s，若放在业务事务里会长时间占用
 * Hikari 连接并锁住 evidence_task 行，并发下直接拖死系统。所以业务事务只负责
 * "落库 + 建任务"，真正上链在这里做，且这里**没有任何事务跨越 HTTP 调用**。
 *
 * <p><b>BR-14</b>：网络超时属于"结果不明"，绝不盲目重发——先按 eventId 查链，
 * 查到即确认，查不到才重试。合约本身也是幂等的，构成第二道保险。
 */
@Component
public class EvidenceWorker {

    private static final Logger log = LoggerFactory.getLogger(EvidenceWorker.class);
    private static final ZoneOffset CN = ZoneOffset.ofHours(8);

    private final EvidenceProperties evidenceProperties;
    private final ChainProperties chainProperties;
    private final ChainWriteClient writeClient;
    private final RpcReadClient readClient;
    private final EvidenceTaskService taskService;
    private final EventSnapshotMapper snapshotMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EvidenceWorker(EvidenceProperties evidenceProperties,
                          ChainProperties chainProperties,
                          ChainWriteClient writeClient,
                          RpcReadClient readClient,
                          EvidenceTaskService taskService,
                          EventSnapshotMapper snapshotMapper) {
        this.evidenceProperties = evidenceProperties;
        this.chainProperties = chainProperties;
        this.writeClient = writeClient;
        this.readClient = readClient;
        this.taskService = taskService;
        this.snapshotMapper = snapshotMapper;
    }

    @Scheduled(fixedDelayString = "${chain.evidence.poll-interval-ms:2000}",
            initialDelayString = "${chain.evidence.poll-interval-ms:2000}")
    public void poll() {
        if (!evidenceProperties.isWorkerEnabled()) {
            return;
        }
        // 单实例内防重入：上一轮没跑完不开下一轮
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (String eventId : taskService.findClaimable()) {
                if (taskService.claim(eventId)) {
                    submit(eventId);
                }
            }
            for (String eventId : taskService.findStuck()) {
                log.warn("发现卡在 SUBMITTED 的任务，先查链再说 eventId={}", eventId);
                resolveUnknown(eventId);
            }
        } catch (Exception e) {
            log.error("存证轮询异常", e);
        } finally {
            running.set(false);
        }
    }

    /** 真正发起上链。 */
    private void submit(String eventId) {
        EventSnapshot snapshot = findSnapshot(eventId);
        if (snapshot == null) {
            taskService.markFailed(eventId, "事件快照不存在，无法上链");
            return;
        }
        try {
            long bizTimeSeconds = snapshot.getBizTime().atOffset(CN).toEpochSecond();
            AppendResult result = writeClient.append(
                    eventId,
                    snapshot.getBatchId(),
                    snapshot.getEventType(),
                    bizTimeSeconds,
                    snapshot.getDataDigest());

            if (result.success()) {
                taskService.markConfirmed(eventId, result.txHash(), result.blockNumber(),
                        result.gasUsed(), result.raw(), chainProperties.getWebase().getUserAddress());
                log.info("存证成功 eventId={} 返回码={} txHash={} block={}",
                        eventId, result.returnCode(), result.txHash(), result.blockNumber());
            } else if (result.conflict()) {
                taskService.markConflict(eventId, "同 eventId 但摘要不同，链上保留原记录");
                log.error("存证冲突 eventId={}，需人工排查", eventId);
            } else {
                taskService.markFailed(eventId, "未知返回码：" + result.returnCode());
            }
        } catch (ChainRejectedException e) {
            taskService.markFailed(eventId, e.getMessage());
        } catch (ChainUnavailableException e) {
            // 结果不明：先查链再决定，绝不盲目重发（BR-14）
            resolveUnknown(eventId);
        } catch (Exception e) {
            log.error("上链未预期异常 eventId={}", eventId, e);
            taskService.markUnreachable(eventId, e.toString());
        }
    }

    /**
     * 结果不明时的判定：查链。
     *
     * <p>链上有且摘要一致 → 已写入，直接确认（拿不到 txHash 也无所谓，核验不依赖它）；
     * 链上有但摘要不同 → 冲突；链上确实没有 → 排下次重试。
     * 链本身不可达 → 累加重试次数并退避。
     */
    private void resolveUnknown(String eventId) {
        EventSnapshot snapshot = findSnapshot(eventId);
        if (snapshot == null) {
            taskService.markFailed(eventId, "事件快照不存在，无法核验");
            return;
        }
        String contract = chainProperties.getWebase().getContractAddress();
        try {
            Optional<com.jxgc.meattrace.chain.ChainEvent> onChain =
                    readClient.queryEventByEventId(contract, eventId);

            if (onChain.isEmpty()) {
                taskService.markUnreachable(eventId, "结果不明，已查链确认未写入");
                return;
            }
            String chainDigest = normalizeDigest(onChain.get().dataDigest());
            if (chainDigest.equals(normalizeDigest(snapshot.getDataDigest()))) {
                taskService.markConfirmed(eventId, null, null, null, null, null);
                log.info("查链确认已写入 eventId={}（结果自愈）", eventId);
            } else {
                taskService.markConflict(eventId, "链上摘要与本地不一致");
                log.error("链上摘要不一致 eventId={} chain={} local={}",
                        eventId, chainDigest, snapshot.getDataDigest());
            }
        } catch (ChainUnavailableException e) {
            taskService.markUnreachable(eventId, "链不可达：" + e.getMessage());
            log.warn("查链失败 eventId={} : {}", eventId, e.getMessage());
        }
    }

    private EventSnapshot findSnapshot(String eventId) {
        return snapshotMapper.selectOne(Wrappers.<EventSnapshot>lambdaQuery()
                .eq(EventSnapshot::getEventId, eventId));
    }

    private static String normalizeDigest(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.toLowerCase(Locale.ROOT).replace("0x", "");
    }
}
