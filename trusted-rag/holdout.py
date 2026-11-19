#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跑 held-out 集：20 条**未参与任何规则调整**的用例。

为什么单独一个脚本：
  公开集 `eval_set.jsonl`（60 条）是调参用的——改检索、改拦截时对着它调，
  所以它上面的满分说明不了泛化能力。这 20 条是在规则冻结之后另写的，
  用来回答「是不是自己出题自己考」。

纪律（写进 README，别自己破）：
  1. 这 20 条的期望**只在首次运行前写定**，跑完不改标注去凑通过率
  2. 如果某条失败，只允许改代码/规则，并把失败原因记进 README 的 held-out 记录
  3. 任何针对这 20 条的特判（写死批次号、写死问法）都算作弊

    python holdout.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from trusted_rag import TrustedRAG

ROOT = Path(__file__).resolve().parent
EVAL = ROOT / "eval_holdout.jsonl"
OUT = ROOT / "evaluation_holdout.json"


def main() -> int:
    rag = TrustedRAG()
    report = rag.evaluate(EVAL)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    m = report["metrics"]
    print("=== held-out 集（20 条，未参与调参）===")
    for k in ("cases", "answerCases", "refuseCases", "answerAccuracy",
              "refusalAccuracy", "citationConsistency"):
        print(f"  {k}: {m[k]}")
    failed = [r for r in report["rows"] if not r["pass"]]
    print(f"\n未通过：{len(failed)} 条")
    for r in failed:
        print(f"  {r['id']} {r['question']} → {r['detail']} 引用={r['citedBatches']} mode={r['mode']}")
    print(f"\n报告：{OUT}")
    # 公开集是调参集，held-out 才是泛化证据：任何一条失败都要让命令失败，避免"报喜不报忧"
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
