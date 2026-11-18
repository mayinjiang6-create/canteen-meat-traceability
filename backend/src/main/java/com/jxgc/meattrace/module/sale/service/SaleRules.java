package com.jxgc.meattrace.module.sale.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.DigestUtil;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import java.math.BigDecimal;

/**
 * 销售环节的**纯校验规则**（不碰数据库，便于单测逐条覆盖）。
 *
 * <p>对应 TECH-02 第 8 节：
 * <ul>
 *   <li>BR-06 只有 {@code ON_SALE} 的加工批次可销售 → 40902</li>
 *   <li>BR-07 累计销售 ≤ 可售余量 → 40901</li>
 *   <li>BR-09 召回后禁止继续销售 → 40902</li>
 * </ul>
 *
 * <p>为什么每种状态单独给一句话：窗口人员看到的提示必须能直接告诉他该找谁——
 * "状态不允许"这五个字在食堂现场没有任何用。而未上架、已售罄、异常冻结、已召回
 * 是四种完全不同的处置路径，混成一句话等于没提示。
 */
public final class SaleRules {

    private SaleRules() {
    }

    /** BR-06 / BR-09：状态闸门。非 {@code ON_SALE} 一律拒绝，并给出可执行的提示。 */
    public static void checkSellable(String processNo, String status) {
        if (ProcessBatch.ON_SALE.equals(status)) {
            return;
        }
        String message = switch (status == null ? "" : status) {
            case ProcessBatch.CREATED -> "该批次尚未上架，无法销售";
            case ProcessBatch.SOLD_OUT -> "该批次已售罄，无法继续销售";
            case ProcessBatch.ABNORMAL -> "该批次异常冻结中，暂停销售";
            case ProcessBatch.RECALLED -> "该批次已召回，禁止销售";
            case ProcessBatch.CLOSED -> "该批次已结案，无法销售";
            default -> "加工批次 " + processNo + " 当前状态为 " + status + "，不能销售";
        };
        throw new BizException(ErrorCode.STATUS_NOT_ALLOWED, message);
    }

    /** BR-07：销售数量不得超过可售余量。提示里的数字来自调用方给的当前值。 */
    public static void checkRemain(String processNo, BigDecimal quantity, BigDecimal availableQty) {
        BigDecimal remain = availableQty == null ? BigDecimal.ZERO : availableQty;
        if (quantity == null || quantity.compareTo(remain) > 0) {
            throw new BizException(ErrorCode.QTY_NOT_ENOUGH,
                    "超出可售余量 " + DigestUtil.scale(remain) + " kg");
        }
    }

    /** 数量必须为正；0.001 是 DECIMAL(12,3) 能表达的最小正数。 */
    public static void checkPositive(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(new BigDecimal("0.001")) < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "销售数量必须大于 0");
        }
    }
}
