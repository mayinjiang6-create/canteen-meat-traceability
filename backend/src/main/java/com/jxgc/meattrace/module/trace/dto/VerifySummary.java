package com.jxgc.meattrace.module.trace.dto;

/**
 * 核验汇总。**与 {@link ConclusionVO} 分栏展示，不得合并**（BR-15）。
 *
 * @param overall PASS（全 MATCHED）/ FAIL（含 MISMATCHED）/ PARTIAL（其余）
 */
public record VerifySummary(
        long matched,
        long mismatched,
        long unconfirmed,
        long unreachable,
        String overall,
        String checkedAt) {
}
