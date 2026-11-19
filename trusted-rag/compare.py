#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""公平的前后对比：v1 baseline（单路 TF-IDF）vs v2（BM25+RRF+意图路由）。

两者跑**同一份 40 条评测集**，指标用统一口径重算（不依赖各自 evaluate 的实现差异），
输出 comparison.json。用来回答「这次改动到底改好了什么、哪些没变」。

    python compare.py
"""
from __future__ import annotations

import importlib.util
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v1 = load(ROOT / "trusted_rag_v1.py", "trag_v1")
v2 = load(ROOT / "trusted_rag.py", "trag_v2")
cases = [json.loads(l) for l in
         (ROOT / "eval_set.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]


def measure(module, tag: str) -> dict:
    rag = module.TrustedRAG()
    rows = []
    for c in cases:
        r = rag.answer(c["question"])
        expect = c["expect"]
        if expect == "refuse":
            ok = not r["grounded"]
        else:
            batches = {x["batchId"] for x in r["citations"]}
            kw_ok = all(k in r["answer"] for k in c.get("keywords", []))
            batch_ok = (not c.get("expectBatch") or c["expectBatch"] in batches)
            ok = r["grounded"] and kw_ok and batch_ok
        eb = c.get("expectBatch")
        rank = next((i + 1 for i, x in enumerate(r["retrieved"][:5])
                     if eb and x["batchId"] == eb), None)
        rows.append({"id": c["id"], "expect": expect, "pass": ok,
                     "rank": rank, "mode": r["mode"],
                     "hasBatch": bool(eb), "grounded": r["grounded"]})

    ans = [r for r in rows if r["expect"] == "answer"]
    ref = [r for r in rows if r["expect"] == "refuse"]
    ranked = [r for r in ans if r["hasBatch"]]
    return {
        "tag": tag,
        "metrics": {
            "cases": len(rows),
            "answerCases": len(ans),
            "refuseCases": len(ref),
            "answerAccuracy": round(sum(r["pass"] for r in ans) / len(ans), 4),
            "refusalAccuracy": round(sum(r["pass"] for r in ref) / len(ref), 4),
            "recallAt5": round(sum(1 for r in ranked if r["rank"]) / len(ranked), 4),
            "top1Accuracy": round(sum(1 for r in ranked if r["rank"] == 1) / len(ranked), 4),
            "mrr": round(sum(1.0 / r["rank"] if r["rank"] else 0.0
                             for r in ranked) / len(ranked), 4),
            "refusalBreakdown": dict(Counter(
                r["mode"] for r in ref if not r["grounded"])),
            "failedCaseIds": [r["id"] for r in rows if not r["pass"]],
        },
        "rows": rows,
    }


def query_rewrite_gain() -> dict:
    """量化查询改写：同义改写类问题上，改写前后 Top-1 词面余弦的变化。"""
    rag = v2.TrustedRAG()

    def best_cos(q: str) -> float:
        qv = rag._qvec(q)
        return max((sum(w * vec.get(t, 0.0) for t, w in qv.items())
                    for vec in rag.vecs), default=0.0)

    syn = {f"Q{i}" for i in range(21, 29)}
    pairs = []
    for c in cases:
        if c["id"] not in syn:
            continue
        raw = best_cos(c["question"])
        rew = best_cos(v2.rewrite(c["question"]))
        pairs.append({"id": c["id"], "question": c["question"],
                      "before": round(raw, 4), "after": round(rew, 4)})
    gains = [p["after"] - p["before"] for p in pairs]
    return {
        "cases": len(pairs),
        "avgBefore": round(sum(p["before"] for p in pairs) / len(pairs), 4),
        "avgAfter": round(sum(p["after"] for p in pairs) / len(pairs), 4),
        "avgGain": round(sum(gains) / len(gains), 4),
        "improved": sum(1 for g in gains if g > 0),
        "detail": pairs,
    }


def main() -> None:
    a = measure(v1, "v1 baseline（单路 TF-IDF）")
    b = measure(v2, "v2（BM25 + RRF + 意图路由）")
    gain = query_rewrite_gain()
    report = {
        "note": "同一份 40 条评测集、统一口径重算。答案类指标两者均满分，"
                "说明语料与问题同分布；真实提升在拒答纪律。",
        "baseline": a, "improved": b, "queryRewrite": gain,
    }
    out = ROOT / "comparison.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== 前后对比（40 条同一评测集）===")
    keys = ["cases", "answerAccuracy", "refusalAccuracy", "recallAt5",
            "top1Accuracy", "mrr"]
    print(f"  {'指标':<20}{'v1':>10}{'v2':>10}{'变化':>12}")
    for k in keys:
        x, y = a["metrics"][k], b["metrics"][k]
        delta = round(y - x, 4) if isinstance(x, (int, float)) and isinstance(y, (int, float)) else "-"
        print(f"  {k:<20}{str(x):>10}{str(y):>10}{str(delta):>12}")
    print(f"\n  v1 失败用例: {a['metrics']['failedCaseIds']}")
    print(f"  v2 失败用例: {b['metrics']['failedCaseIds']}")
    print(f"\n  v1 拒答原因: {a['metrics']['refusalBreakdown']}")
    print(f"  v2 拒答原因: {b['metrics']['refusalBreakdown']}")
    print(f"\n  查询改写（8 条同义改写）平均 Top-1 余弦: "
          f"{gain['avgBefore']} → {gain['avgAfter']}，提升 {gain['avgGain']}，"
          f"{gain['improved']}/{gain['cases']} 条改善")
    print(f"\n完整对比报告: {out}")


if __name__ == "__main__":
    main()
