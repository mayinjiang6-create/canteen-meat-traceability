package com.jxgc.meattrace.module.abnormal.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.abnormal.entity.AbnormalEvent;
import java.util.Set;

/**
 * 异常登记与处置的**纯规则**。
 *
 * <p>对应 TECH-02 6.2（ABNORMAL 状态）与 7 节事务边界：
 * 登记异常是"记录事实"，冻结销售是"风险控制"，两者必须同时发生，
 * 否则会出现"异常登记了但批次还在卖"这种最难解释的状态。
 */
public final class AbnormalRules {

    public static final Set<String> TYPES = Set.of(
            "TEMPERATURE_EXCEEDED", "SENSORY_ABNORMAL", "CERT_MISSING", "SHELF_LIFE", "OTHER");

    public static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private AbnormalRules() {
    }

    /** 至少挂一个批次：挂采购批次（到货问题）或加工批次（成品问题）。 */
    public static void checkTarget(boolean hasPurchaseBatch, boolean hasProcessBatch) {
        if (!hasPurchaseBatch && !hasProcessBatch) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "必须指定 batchNo（采购批次）或 processBatchNo（加工批次）其中之一");
        }
    }

    public static void checkType(String type) {
        if (type == null || !TYPES.contains(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "异常类型只能是 TEMPERATURE_EXCEEDED / SENSORY_ABNORMAL / CERT_MISSING / SHELF_LIFE / OTHER");
        }
    }

    public static void checkLevel(String level) {
        if (level == null || level.isBlank()) {
            return;
        }
        if (!LEVELS.contains(level)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "异常等级只能是 LOW / MEDIUM / HIGH");
        }
    }

    /** 只有未结案的异常可以处置；已结案的要改结论只能重新登记。 */
    public static void checkDisposable(String status) {
        if (AbnormalEvent.OPEN.equals(status) || AbnormalEvent.HANDLING.equals(status)) {
            return;
        }
        throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                "该异常当前状态为 " + status + "，已结案不可重复处置");
    }

    /**
     * 登记异常时是否要冻结加工批次。
     *
     * <p>只有 {@code ON_SALE} 需要冻结——它正在卖，不冻就会继续流到餐桌；
     * 其余状态（未上架 / 已售罄 / 已召回 / 已结案）本来就不能卖，
     * 硬改状态反而会把"售罄"这种事实覆盖掉。
     */
    public static boolean shouldFreeze(String processStatus) {
        return "ON_SALE".equals(processStatus);
    }
}
