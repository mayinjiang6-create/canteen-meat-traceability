package com.jxgc.meattrace.module.evidence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxgc.meattrace.chain.ChainEvent;
import com.jxgc.meattrace.chain.ChainProperties;
import com.jxgc.meattrace.chain.ChainUnavailableException;
import com.jxgc.meattrace.chain.EvidenceProperties;
import com.jxgc.meattrace.chain.RpcReadClient;
import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.PageResult;
import com.jxgc.meattrace.module.evidence.dto.EvidenceTaskVO;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.entity.EvidenceTask;
import com.jxgc.meattrace.module.evidence.entity.TxReceipt;
import com.jxgc.meattrace.module.evidence.mapper.EventSnapshotMapper;
import com.jxgc.meattrace.module.evidence.mapper.EvidenceTaskMapper;
import com.jxgc.meattrace.module.evidence.mapper.TxReceiptMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存证任务的状态流转与查询。
 *
 * <p><b>认领用条件 UPDATE</b>：{@code WHERE status='PENDING'} 命中行数为 1 才算认领成功。
 * 数据库行锁天然保证多实例/多线程下同一条任务只被一个执行者拿到——
 * 这就是不需要 Redis 分布式锁的原因。
 */
@Service
public class EvidenceTaskService {

    private final EvidenceTaskMapper taskMapper;
    private final TxReceiptMapper receiptMapper;
    private final EventSnapshotMapper snapshotMapper;
    private final EvidenceProperties properties;
    private final ChainProperties chainProperties;
    private final RpcReadClient readClient;

    public EvidenceTaskService(EvidenceTaskMapper taskMapper,
                               TxReceiptMapper receiptMapper,
                               EventSnapshotMapper snapshotMapper,
                               EvidenceProperties properties,
                               ChainProperties chainProperties,
                               RpcReadClient readClient) {
        this.taskMapper = taskMapper;
        this.receiptMapper = receiptMapper;
        this.snapshotMapper = snapshotMapper;
        this.properties = properties;
        this.chainProperties = chainProperties;
        this.readClient = readClient;
    }

    /** 取出本轮可处理的 eventId（PENDING 且已到重试时间）。 */
    public List<String> findClaimable() {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.selectList(Wrappers.<EvidenceTask>lambdaQuery()
                        .eq(EvidenceTask::getStatus, EvidenceTask.PENDING)
                        .and(w -> w.isNull(EvidenceTask::getNextRetryAt)
                                .or().le(EvidenceTask::getNextRetryAt, now))
                        .orderByAsc(EvidenceTask::getId)
                        .last("LIMIT " + Math.max(properties.getBatchSize(), 1)))
                .stream().map(EvidenceTask::getEventId).toList();
    }

    /** 找出卡在 SUBMITTED 太久的任务（进程崩溃等），需要先查链再决定。 */
    public List<String> findStuck() {
        LocalDateTime deadline =
                LocalDateTime.now().minusNanos(properties.getStuckTimeoutMs() * 1_000_000L);
        return taskMapper.selectList(Wrappers.<EvidenceTask>lambdaQuery()
                        .eq(EvidenceTask::getStatus, EvidenceTask.SUBMITTED)
                        .lt(EvidenceTask::getUpdatedAt, deadline)
                        .orderByAsc(EvidenceTask::getId)
                        .last("LIMIT " + Math.max(properties.getBatchSize(), 1)))
                .stream().map(EvidenceTask::getEventId).toList();
    }

    /** 条件认领：只有把 PENDING 改成 SUBMITTED 成功的那一方才能继续处理。 */
    @Transactional
    public boolean claim(String eventId) {
        EvidenceTask update = new EvidenceTask();
        update.setStatus(EvidenceTask.SUBMITTED);
        update.setUpdatedAt(LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        int rows = taskMapper.update(update, Wrappers.<EvidenceTask>lambdaUpdate()
                .eq(EvidenceTask::getEventId, eventId)
                .eq(EvidenceTask::getStatus, EvidenceTask.PENDING)
                .and(w -> w.isNull(EvidenceTask::getNextRetryAt)
                        .or().le(EvidenceTask::getNextRetryAt, now)));
        return rows == 1;
    }

    @Transactional
    public void markConfirmed(String eventId, String txHash, Long blockNumber, Long gasUsed,
                              String rawReceipt, String submitter) {
        updateStatus(eventId, EvidenceTask.CONFIRMED, null, null);
        if (txHash != null) {
            try {
                saveReceipt(eventId, txHash, blockNumber, gasUsed, rawReceipt, submitter);
            } catch (RuntimeException e) {
                // 回执只是附件，写不进去不能影响"已确认"这个主状态
                org.slf4j.LoggerFactory.getLogger(EvidenceTaskService.class)
                        .warn("回执落库失败 eventId={} : {}", eventId, e.toString());
            }
        }
    }

    /** 回执按 event_id 去重：幂等重放（返回码 1）可能产生新 txHash，但一条事件只留一条回执。 */
    @Transactional
    public void saveReceipt(String eventId, String txHash, Long blockNumber, Long gasUsed,
                            String rawReceipt, String submitter) {
        Long existing = receiptMapper.selectCount(Wrappers.<TxReceipt>lambdaQuery()
                .eq(TxReceipt::getEventId, eventId));
        if (existing != null && existing > 0) {
            return;
        }
        TxReceipt receipt = new TxReceipt();
        receipt.setEventId(eventId);
        receipt.setTxHash(txHash);
        receipt.setBlockNumber(blockNumber);
        receipt.setGasUsed(gasUsed);
        // 回执状态：成功是 0x1（注意与 handleWithSign 的 status=0 是两套约定）
        receipt.setTxStatus("0x1");
        receipt.setSubmitterAddress(submitter);
        receipt.setRawReceipt(rawReceipt);
        receipt.setReceiptTime(LocalDateTime.now());
        receipt.setCreatedAt(LocalDateTime.now());
        receiptMapper.insert(receipt);
    }

    @Transactional
    public void markConflict(String eventId, String message) {
        updateStatus(eventId, EvidenceTask.CONFLICT, message, null);
    }

    @Transactional
    public void markFailed(String eventId, String message) {
        updateStatus(eventId, EvidenceTask.FAILED, message, null);
    }

    /** 结果不可达：累加重试次数并排下次重试；超过上限置 FAILED。 */
    @Transactional
    public void markUnreachable(String eventId, String message) {
        EvidenceTask task = findByEventId(eventId);
        int retry = task == null || task.getRetryCount() == null ? 0 : task.getRetryCount();
        retry = retry + 1;
        if (retry >= properties.getMaxRetry()) {
            updateStatus(eventId, EvidenceTask.FAILED,
                    "重试 " + retry + " 次仍失败：" + message, retry);
            return;
        }
        LocalDateTime next =
                LocalDateTime.now().plusNanos(properties.backoffFor(retry) * 1_000_000L);
        updateStatus(eventId, EvidenceTask.PENDING, message, retry);
        EvidenceTask update = new EvidenceTask();
        update.setNextRetryAt(next);
        taskMapper.update(update, Wrappers.<EvidenceTask>lambdaUpdate()
                .eq(EvidenceTask::getEventId, eventId));
    }

    /** 人工重试：清空退避时间，让下一轮 worker 立即捡起。 */
    @Transactional
    public void resetToPending(String eventId, int retryCount) {
        EvidenceTask update = new EvidenceTask();
        update.setStatus(EvidenceTask.PENDING);
        update.setRetryCount(retryCount);
        update.setNextRetryAt(null);
        update.setLastError(null);
        update.setUpdatedAt(LocalDateTime.now());
        taskMapper.update(update, Wrappers.<EvidenceTask>lambdaUpdate()
                .eq(EvidenceTask::getEventId, eventId));
    }

    /**
     * 人工重试（BR-14）：**先查链再决定**。
     *
     * <p>如果链上已经有同摘要的记录，说明之前其实写成功了（只是回执没来得及落库），
     * 直接置 CONFIRMED，不重复提交；只有确认链上没有才重新排队。
     */
    @Transactional
    public EvidenceTaskVO retry(String eventId) {
        EvidenceTask task = findByEventId(eventId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "存证任务不存在");
        }
        if (EvidenceTask.CONFIRMED.equals(task.getStatus())) {
            return toVO(task, findSnapshot(eventId), findReceipt(eventId));
        }

        EventSnapshot snapshot = findSnapshot(eventId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "事件快照不存在，无法重试");
        }

        String contract = chainProperties.getWebase().getContractAddress();
        Optional<ChainEvent> onChain;
        try {
            onChain = readClient.queryEventByEventId(contract, eventId);
        } catch (ChainUnavailableException e) {
            throw new BizException(ErrorCode.CHAIN_UNREACHABLE,
                    "链不可达，无法判断是否已写入：" + e.getMessage());
        }

        if (onChain.isPresent()) {
            if (normalize(onChain.get().dataDigest()).equals(normalize(snapshot.getDataDigest()))) {
                markConfirmed(eventId, null, null, null, null, null);
            } else {
                markConflict(eventId, "链上摘要与本地不一致");
            }
        } else {
            int retryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
            resetToPending(eventId, retryCount);
        }
        return toVO(findByEventId(eventId), snapshot, findReceipt(eventId));
    }

    public PageResult<EvidenceTaskVO> page(String status, int page, int size) {
        Page<EvidenceTask> result = taskMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                Wrappers.<EvidenceTask>lambdaQuery()
                        .eq(status != null && !status.isBlank(), EvidenceTask::getStatus, status)
                        .orderByDesc(EvidenceTask::getId));

        List<String> eventIds = result.getRecords().stream()
                .map(EvidenceTask::getEventId).toList();
        Map<String, EventSnapshot> snapshots = eventIds.isEmpty() ? Map.of()
                : snapshotMapper.selectList(Wrappers.<EventSnapshot>lambdaQuery()
                                .in(EventSnapshot::getEventId, eventIds))
                        .stream().collect(Collectors.toMap(
                                EventSnapshot::getEventId, Function.identity(), (a, b) -> a));
        Map<String, TxReceipt> receipts = eventIds.isEmpty() ? Map.of()
                : receiptMapper.selectList(Wrappers.<TxReceipt>lambdaQuery()
                                .in(TxReceipt::getEventId, eventIds))
                        .stream().collect(Collectors.toMap(
                                TxReceipt::getEventId, Function.identity(), (a, b) -> a));

        return PageResult.of(result, t -> toVO(t, snapshots.get(t.getEventId()),
                receipts.get(t.getEventId())));
    }

    public EvidenceTaskVO detail(String eventId) {
        EvidenceTask task = findByEventId(eventId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "存证任务不存在");
        }
        return toVO(task, findSnapshot(eventId), findReceipt(eventId));
    }

    public EvidenceTask findByEventId(String eventId) {
        return taskMapper.selectOne(Wrappers.<EvidenceTask>lambdaQuery()
                .eq(EvidenceTask::getEventId, eventId));
    }

    public EventSnapshot findSnapshot(String eventId) {
        return snapshotMapper.selectOne(Wrappers.<EventSnapshot>lambdaQuery()
                .eq(EventSnapshot::getEventId, eventId));
    }

    public TxReceipt findReceipt(String eventId) {
        return receiptMapper.selectOne(Wrappers.<TxReceipt>lambdaQuery()
                .eq(TxReceipt::getEventId, eventId)
                .orderByDesc(TxReceipt::getId)
                .last("LIMIT 1"));
    }

    private EvidenceTaskVO toVO(EvidenceTask task, EventSnapshot snapshot, TxReceipt receipt) {
        return new EvidenceTaskVO(
                task.getEventId(),
                snapshot == null ? null : snapshot.getBatchId(),
                snapshot == null ? null : snapshot.getEventType(),
                task.getStatus(),
                task.getRetryCount(),
                task.getNextRetryAt(),
                task.getLastError(),
                receipt == null ? null : receipt.getTxHash(),
                receipt == null ? null : receipt.getBlockNumber(),
                receipt == null ? null : receipt.getReceiptTime());
    }

    private void updateStatus(String eventId, String status, String error, Integer retryCount) {
        EvidenceTask update = new EvidenceTask();
        update.setStatus(status);
        update.setLastError(error == null ? null
                : error.substring(0, Math.min(error.length(), 480)));
        update.setRetryCount(retryCount);
        update.setUpdatedAt(LocalDateTime.now());
        taskMapper.update(update, Wrappers.<EvidenceTask>lambdaUpdate()
                .eq(EvidenceTask::getEventId, eventId));
    }

    private static String normalize(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.toLowerCase(Locale.ROOT).replace("0x", "");
    }
}
