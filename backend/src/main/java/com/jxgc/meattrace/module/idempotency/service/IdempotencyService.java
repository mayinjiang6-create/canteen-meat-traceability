package com.jxgc.meattrace.module.idempotency.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxgc.meattrace.module.idempotency.entity.RequestIdempotency;
import com.jxgc.meattrace.module.idempotency.mapper.RequestIdempotencyMapper;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务层幂等（BR-10）。
 *
 * <p>用法：在业务事务内先 {@link #claim}，返回空表示首次请求、继续业务；
 * 返回非空表示重复请求、直接把首次的业务对象捞出来返回（标 replayed=true）。
 * 业务落库成功后调 {@link #bind} 记下业务主键。
 *
 * <p>放在同一个事务里是关键：若业务失败回滚，幂等占位也一起回滚，
 * 不会出现"占了坑但业务没成"导致后续重试永远打不进去。
 */
@Service
public class IdempotencyService {

    private final RequestIdempotencyMapper mapper;

    public IdempotencyService(RequestIdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 占位。
     *
     * @return 非空 = 该 requestId 已被处理过，调用方应直接返回首次结果
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RequestIdempotency> claim(String requestId, Long userId, String endpoint) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        Optional<RequestIdempotency> existing = find(requestId);
        if (existing.isPresent()) {
            return existing;
        }
        RequestIdempotency record = new RequestIdempotency();
        record.setRequestId(requestId);
        record.setUserId(userId);
        record.setEndpoint(endpoint);
        try {
            mapper.insert(record);
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            // 同一 requestId 并发进入，另一个请求已抢先插入
            return find(requestId);
        }
    }

    /** 业务落库后绑定主键，方便重放时直接按主键取回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(String requestId, Long bizId, String businessNo) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        RequestIdempotency update = new RequestIdempotency();
        update.setBizId(bizId);
        update.setBusinessNo(businessNo);
        mapper.update(update, Wrappers.<RequestIdempotency>lambdaUpdate()
                .eq(RequestIdempotency::getRequestId, requestId));
    }

    public Optional<RequestIdempotency> find(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<RequestIdempotency>lambdaQuery()
                .eq(RequestIdempotency::getRequestId, requestId)));
    }
}
