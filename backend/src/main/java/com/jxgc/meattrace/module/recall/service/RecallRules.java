package com.jxgc.meattrace.module.recall.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;

/**
 * 召回的**纯规则**。
 *
 * <p>BR-09：召回后该批次全部销售记录置 {@code RECALLED}，禁止继续销售。
 *
 * <p>哪些状态可以召回：{@code CREATED}（已加工未上架，也能召回）、
 * {@code ON_SALE}、{@code SOLD_OUT}（卖完了才发现问题，更要召回）、
 * {@code ABNORMAL}（异常升级为召回）。不能召回的是 {@code CLOSED}（已结案归档）
 * 与 {@code RECALLED}（重复召回由 Service 按幂等处理，不在这里报错）。
 */
public final class RecallRules {

    private RecallRules() {
    }

    public static void checkRecallable(String processNo, String status) {
        if (ProcessBatch.CLOSED.equals(status)) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "加工批次 " + processNo + " 已结案，无法召回");
        }
        if (status == null) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "加工批次 " + processNo + " 状态未知，无法召回");
        }
    }
}
