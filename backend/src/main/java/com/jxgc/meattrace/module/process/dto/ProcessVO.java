package com.jxgc.meattrace.module.process.dto;

import com.jxgc.meattrace.module.process.entity.ProcessBatch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 加工批次出参（列表与详情共用）。
 *
 * <p>列表页带汇总（{@code inputQty} / {@code sourceCount}），{@code sources} 为 null；
 * 详情页才把来源明细铺开——避免列表返回几十条 source 把响应撑大。
 */
public record ProcessVO(
        Long id,
        String processNo,
        String meatType,
        String outputQty,
        String availableQty,
        String inputQty,
        Integer sourceCount,
        String unit,
        LocalDateTime processTime,
        String workshop,
        String operator,
        String retainSampleNo,
        String status,
        LocalDateTime createdAt,
        List<ProcessSourceVO> sources) {

    public static ProcessVO of(ProcessBatch batch, ProcessSourceSummary summary) {
        return new ProcessVO(
                batch.getId(),
                batch.getProcessNo(),
                batch.getMeatType(),
                scale(batch.getOutputQty()),
                scale(batch.getAvailableQty()),
                summary == null ? null : scale(summary.getInputQty()),
                summary == null ? null : summary.getSourceCount(),
                batch.getUnit(),
                batch.getProcessTime(),
                batch.getWorkshop(),
                batch.getOperator(),
                batch.getRetainSampleNo(),
                batch.getStatus(),
                batch.getCreatedAt(),
                null);
    }

    public static ProcessVO detail(ProcessBatch batch, List<ProcessSourceVO> sources) {
        BigDecimal inputQty = sources == null ? BigDecimal.ZERO
                : sources.stream()
                        .map(s -> s.usedQty() == null ? BigDecimal.ZERO : new BigDecimal(s.usedQty()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProcessVO(
                batch.getId(),
                batch.getProcessNo(),
                batch.getMeatType(),
                scale(batch.getOutputQty()),
                scale(batch.getAvailableQty()),
                scale(inputQty),
                sources == null ? 0 : sources.size(),
                batch.getUnit(),
                batch.getProcessTime(),
                batch.getWorkshop(),
                batch.getOperator(),
                batch.getRetainSampleNo(),
                batch.getStatus(),
                batch.getCreatedAt(),
                sources);
    }

    private static String scale(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }
}
