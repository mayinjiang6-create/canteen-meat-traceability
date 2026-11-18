package com.jxgc.meattrace.module.trace.dto;

/**
 * 检验结论。**必须与核验状态分栏展示**（BR-15）。
 *
 * <p>这两个东西经常被混为一谈，本项目在需求阶段就把它们拆开了：
 * 我们能证明的是"这条记录写进去之后没被改过"，<b>不是</b>"这批肉合格"。
 * 后者只能由检验记录说，前者才由区块链说。
 *
 * @param inspectionResult 检验结论 PASS / FAIL
 * @param inspectionNote   结论说明
 * @param source           结论来源，如"入库验收记录 AC20261118001"
 */
public record ConclusionVO(String inspectionResult, String inspectionNote, String source) {
}
