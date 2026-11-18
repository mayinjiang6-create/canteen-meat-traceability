package com.jxgc.meattrace.module.trace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxgc.meattrace.chain.ChainEvent;
import com.jxgc.meattrace.chain.ChainProperties;
import com.jxgc.meattrace.chain.ChainUnavailableException;
import com.jxgc.meattrace.chain.RpcReadClient;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.module.evidence.entity.EventSnapshot;
import com.jxgc.meattrace.module.evidence.entity.TxReceipt;
import com.jxgc.meattrace.module.evidence.mapper.TxReceiptMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 核验编排：取快照 → 重算摘要 → 读链 → 交给 {@link VerifyStatusResolver} 判定。
 *
 * <p><b>摘要必须用库里存的 {@code canonical_text} 重算</b>，不能拿业务实体重新拼装：
 * 一旦字段顺序或格式调整，历史数据会全部变成 MISMATCHED，那才是真正的灾难。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final RpcReadClient readClient;
    private final ChainProperties chainProperties;
    private final TxReceiptMapper receiptMapper;

    public VerificationService(RpcReadClient readClient,
                               ChainProperties chainProperties,
                               TxReceiptMapper receiptMapper) {
        this.readClient = readClient;
        this.chainProperties = chainProperties;
        this.receiptMapper = receiptMapper;
    }

    /**
     * 批量核验。
     *
     * <p>带<b>熔断</b>：一旦发现链不可达，剩下的事件直接标 UNREACHABLE 不再逐条等超时。
     * 否则一次溯源 6 条事件、每条等 5 秒，公开页会卡 30 秒——用户以为系统死了。
     */
    public List<VerifiedEvent> verifyAll(List<EventSnapshot> snapshots) {
        List<VerifiedEvent> results = new ArrayList<>(snapshots.size());
        boolean chainDown = false;
        for (EventSnapshot snapshot : snapshots) {
            if (chainDown) {
                results.add(new VerifiedEvent(snapshot, VerifyStatusResolver.UNREACHABLE, null,
                        findReceipt(snapshot.getEventId())));
                continue;
            }
            VerifiedEvent verified = verify(snapshot);
            if (VerifyStatusResolver.UNREACHABLE.equals(verified.status())) {
                chainDown = true;
            }
            results.add(verified);
        }
        if (chainDown) {
            log.warn("核验过程中链不可达，本轮剩余事件统一标记 UNREACHABLE");
        }
        return results;
    }

    public VerifiedEvent verify(EventSnapshot snapshot) {
        // 永远用库里的规范串重算，不重新拼装
        String recalc = DigestUtil.sha256Hex(snapshot.getCanonicalText());
        TxReceipt receipt = findReceipt(snapshot.getEventId());
        String contract = chainProperties.getWebase().getContractAddress();
        try {
            Optional<ChainEvent> onChain =
                    readClient.queryEventByEventId(contract, snapshot.getEventId());
            String status = VerifyStatusResolver.resolve(recalc, onChain);
            return new VerifiedEvent(snapshot, status, onChain.orElse(null), receipt);
        } catch (ChainUnavailableException e) {
            // 只有这里才允许产出 UNREACHABLE
            log.debug("核验读链失败 eventId={} : {}", snapshot.getEventId(), e.getMessage());
            return new VerifiedEvent(snapshot, VerifyStatusResolver.UNREACHABLE, null, receipt);
        }
    }

    public TxReceipt findReceipt(String eventId) {
        return receiptMapper.selectOne(Wrappers.<TxReceipt>lambdaQuery()
                .eq(TxReceipt::getEventId, eventId)
                .orderByDesc(TxReceipt::getId)
                .last("LIMIT 1"));
    }
}
