#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RAGAS 口径的**零依赖近似实现** + 与自建确定性断言的双口径对照。

为什么要它：`demo.py` 的指标是自建断言（答案含关键词、引用落在标注批次），
优点是零 Key、可复现，缺点是被问「这不是你自己出题自己考吗」。
RAGAS（`explodinggradients/ragas`）是 RAG 评测的通用口径，四个指标：
faithfulness / answer relevance / context precision / context recall。

**但它依赖 LLM-as-judge，要 Key、要花钱、结果不稳定。** 所以这里做的是：
用同一份评测集、按 RAGAS 的**指标定义**给出确定性近似值，并把两者的适用边界写清楚：
  - 自建断言：可进 CI 当门禁（0 成本、稳定、但只测"有没有"）
  - RAGAS 口径：能测"答得贴不贴"，本实现是词面近似，真实项目应接 ragas + LLM 评审

四个指标怎么近似（有偏差就写出来，不装成官方实现）：
  faithfulness      答案每一行去掉 [n] 后，必须能在对应上下文里原文找到 → 支持行占比
                    （抽取式生成天然接近 1.00，这个指标只有接 LLM 生成层才有区分度）
  answer relevance  问句实词在答案中的覆盖率（词面近似，不懂同义）
  context precision 前 k 个上下文里"相关"的占比；相关 = 批次号等于标注批次，或含标注关键词
  context recall    标注批次是否出现在上下文里（无标注批次的规则类问题不计入）

    python ragas_like.py
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from trusted_rag import TrustedRAG, tokenize

ROOT = Path(__file__).resolve().parent
EVAL = ROOT / "eval_set.jsonl"
OUT = ROOT / "ragas_like.json"

CITATION_RE = re.compile(r"\[\d+\]")
# 虚词不参与 answer relevance，否则「的/是/多少」这类词会把分数撑高
STOP = set("的了是在有和与及或请问一下这个那批多少什么哪些怎么哪个吗呢吧啊")


def content_tokens(text: str) -> set[str]:
    return {t for t in tokenize(text) if len(t) > 1 and t not in STOP}


def score_case(rag: TrustedRAG, case: dict) -> dict | None:
    if case["expect"] != "answer":
        return None
    r = rag.answer(case["question"])
    if not r["grounded"]:
        # 该答没答：四个指标都记 0，保留在明细里让阅读者看到，不做静默剔除
        return {"id": case["id"], "faithfulness": 0.0, "answerRelevance": 0.0,
                "contextPrecision": 0.0, "contextRecall": 0.0 if case.get("expectBatch") else None,
                "note": "该答未答（mode=%s）" % r["mode"]}

    contexts = r["contexts"]
    keywords = case.get("keywords", [])
    expect_batch = case.get("expectBatch")

    # faiththfulness：逐行核对答案是否真的来自上下文（抽取式应当是 1.00）
    lines = [CITATION_RE.sub("", ln).strip() for ln in r["answer"].splitlines() if ln.strip()]
    supported = sum(1 for ln in lines if any(ln in c["content"] for c in contexts))
    faithfulness = supported / len(lines) if lines else 0.0

    # answer relevance：问句实词被答案覆盖的比例
    q_tokens = content_tokens(case["question"])
    a_tokens = content_tokens(r["answer"])
    answer_relevance = (len(q_tokens & a_tokens) / len(q_tokens)) if q_tokens else 0.0

    # context precision：上下文里相关的比例
    def relevant(c: dict) -> bool:
        if expect_batch and (c.get("batchId") or "").upper() == expect_batch.upper():
            return True
        return any(k in c["content"] for k in keywords)

    ctx_precision = sum(1 for c in contexts if relevant(c)) / len(contexts) if contexts else 0.0

    # context recall：标注批次是否被检索到（规则类问题没有标注批次，记 None 不计入均值）
    ctx_recall = None
    if expect_batch:
        ctx_recall = 1.0 if any((c.get("batchId") or "").upper() == expect_batch.upper()
                                for c in contexts) else 0.0

    return {"id": case["id"], "faithfulness": round(faithfulness, 4),
            "answerRelevance": round(answer_relevance, 4),
            "contextPrecision": round(ctx_precision, 4),
            "contextRecall": ctx_recall}


def main() -> None:
    rag = TrustedRAG()
    cases = [json.loads(l) for l in EVAL.read_text(encoding="utf-8").splitlines() if l.strip()]
    rows = [x for x in (score_case(rag, c) for c in cases) if x]

    def avg(key: str) -> float | None:
        vals = [r[key] for r in rows if r[key] is not None]
        return round(sum(vals) / len(vals), 4) if vals else None

    metrics = {
        "answerCases": len(rows),
        "faithfulness": avg("faithfulness"),
        "answerRelevance": avg("answerRelevance"),
        "contextPrecision": avg("contextPrecision"),
        "contextRecall": avg("contextRecall"),
        "note": "RAGAS 指标定义的零依赖近似：词面判定，非 LLM-as-judge，数值不可与官方 ragas 直接对比",
    }
    report = {"meta": {"eval": EVAL.name, "cases": len(cases),
                       "implementation": "deterministic approximation of RAGAS triad",
                       "realRagas": "需 API Key 与额外依赖，本仓库默认不跑；生产化建议接 ragas + LLM 评审"},
              "metrics": metrics, "rows": rows}
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== RAGAS 口径（零依赖近似）===")
    print(f"  应答案例：{metrics['answerCases']}")
    print(f"  faithfulness      {metrics['faithfulness']}")
    print(f"  answer relevance  {metrics['answerRelevance']}")
    print(f"  context precision {metrics['contextPrecision']}")
    print(f"  context recall    {metrics['contextRecall']}")
    low = [r for r in rows if (r["contextPrecision"] or 0) < 0.5]
    print(f"\n  context precision < 0.5 的用例：{len(low)} 条")
    for r in low[:6]:
        print(f"    {r['id']} precision={r['contextPrecision']}")
    print(f"\n报告：{OUT}")


if __name__ == "__main__":
    main()
