package com.jxgc.meattrace.module.coldchain.dto;

import com.jxgc.meattrace.module.coldchain.entity.ColdChainLog;
import com.jxgc.meattrace.module.evidence.dto.EvidenceInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 冷链记录出参。
 *
 * <p>{@code exceeded} 与 {@code threshold} 一起返回：只给一个"超标"红标，
 * 用户无法判断到底超了多少，也就无法判断该不该整批退。
 * {@code abnormalNo} 非空表示已自动开出异常工单，页面可直接跳到处置页。
 */
public record ColdChainVO(
        Long id,
        String logNo,
        String batchNo,
        String meatType,
        String segment,
        String transportNo,
        String temperature,
        String threshold,
        Boolean exceeded,
        LocalDateTime recordedAt,
        String recorder,
        String remark,
        String abnormalNo,
        EvidenceInfo evidence) {

    public static ColdChainVO of(ColdChainLog log, String batchNo, String meatType,
                                 String abnormalNo, EvidenceInfo evidence) {
        return new ColdChainVO(
                log.getId(),
                log.getLogNo(),
                batchNo,
                meatType,
                log.getSegment(),
                log.getTransportNo(),
                temp(log.getTemperature()),
                temp(log.getThreshold()),
                log.getExceeded(),
                log.getRecordedAt(),
                log.getRecorder(),
                log.getRemark(),
                abnormalNo,
                evidence);
    }

    private static String temp(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
