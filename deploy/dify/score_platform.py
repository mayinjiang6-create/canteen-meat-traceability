#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给「平台版答案」打分：与 demo.py 同一份评测集、同一套判定口径。

平台（Dify / Coze / 百炼）不会返回我们自定义的 citations 结构，所以判定改成：
  - 应回答：答案里必须出现全部标注关键词，且（若标注了批次）必须出现该批次号
  - 应拒答：答案里出现任一拒答短语，或调用方显式给了 "grounded": false

用法：
    # 1) 把平台逐条答案整理成 jsonl，一行一条
    {"id":"Q01","answer":"……","grounded":true}
    # 2) 打分
    python deploy/dify/score_platform.py deploy/dify/answers_dify.jsonl

输出：控制台账 + `deploy/dify/score_platform.json`，并自动与仓库里的
`trusted-rag/evaluation.json` 并列，方便直接看差距。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "trusted-rag" / "eval_set.jsonl"
BASELINE = ROOT / "trusted-rag" / "evaluation.json"

# 与手写链路的拒答语保持同一套说法，避免"平台说了别的话就算没拒"
REFUSAL_MARKERS = [
    "未在已核验记录中找到依据",
    "该问题超出溯源记录范围",
    "请补充", "无法回答", "没有找到", "未找到", "不能回答", "无法确定",
]


def is_refusal(answer: str) -> bool:
    return any(m in answer for m in REFUSAL_MARKERS)


def main() -> int:
    if len(sys.argv) < 2:
        print("用法：python score_platform.py <answers.jsonl>")
        return 2
    answers_path = Path(sys.argv[1])
    if not answers_path.is_absolute():
        answers_path = Path.cwd() / answers_path
    if not answers_path.exists():
        print(f"找不到答案文件：{answers_path}")
        return 2

    cases = {json.loads(l)["id"]: json.loads(l)
             for l in EVAL.read_text(encoding="utf-8").splitlines() if l.strip()}
    answers = {}
    for line in answers_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        answers[row["id"]] = row

    rows = []
    for cid, case in cases.items():
        got = answers.get(cid)
        if not got:
            rows.append({"id": cid, "expect": case["expect"], "pass": False,
                         "reason": "平台未返回该题"})
            continue
        text = got.get("answer", "")
        if case["expect"] == "refuse":
            ok = got.get("grounded") is False or is_refusal(text)
            reason = "正确拒答" if ok else "错误作答（该拒未拒）"
        else:
            kw_ok = all(k in text for k in case.get("keywords", []))
            batch_ok = (not case.get("expectBatch")) or case["expectBatch"] in text
            ok = kw_ok and batch_ok and not is_refusal(text)
            reason = f"关键词={kw_ok} 批次={batch_ok}"
        rows.append({"id": cid, "expect": case["expect"], "pass": ok, "reason": reason})

    ans = [r for r in rows if r["expect"] == "answer"]
    ref = [r for r in rows if r["expect"] == "refuse"]

    def rate(xs):
        return round(sum(r["pass"] for r in xs) / len(xs), 4) if xs else None

    result = {
        "source": str(answers_path),
        "metrics": {
            "cases": len(rows),
            "answerCases": len(ans),
            "refuseCases": len(ref),
            "answerAccuracy": rate(ans),
            "refusalAccuracy": rate(ref),
            "failed": [r["id"] for r in rows if not r["pass"]],
        },
        "rows": rows,
    }

    print("=== 平台版答案打分（与 demo.py 同口径）===")
    print(f"  题目：{len(rows)} 条（应回答 {len(ans)} / 应拒答 {len(ref)}）")
    print(f"  答案正确率：{result['metrics']['answerAccuracy']}")
    print(f"  拒答正确率：{result['metrics']['refusalAccuracy']}")
    print(f"  失败用例：{result['metrics']['failed'] or '无'}")

    if BASELINE.exists():
        base = json.loads(BASELINE.read_text(encoding="utf-8"))["metrics"]
        print("\n=== 与手写链路并列（同一份题）===")
        print(f"  {'指标':<16}{'手写':>10}{'平台':>10}")
        print(f"  {'答案正确率':<16}{str(base.get('answerAccuracy')):>10}"
              f"{str(result['metrics']['answerAccuracy']):>10}")
        print(f"  {'拒答正确率':<16}{str(base.get('refusalAccuracy')):>10}"
              f"{str(result['metrics']['refusalAccuracy']):>10}")
        result["handwritten"] = {"answerAccuracy": base.get("answerAccuracy"),
                                 "refusalAccuracy": base.get("refusalAccuracy")}

    out = answers_path.parent / "score_platform.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n报告：{out}")
    # 拒答正确率没到 100% 就返回非零——平台版也按同一条红线要求
    return 0 if result["metrics"]["refusalAccuracy"] == 1.0 else 1


if __name__ == "__main__":
    sys.exit(main())
