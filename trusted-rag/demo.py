#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条命令跑完评测 + 示例问答，输出 evaluation.json。

    python demo.py                 # 离线模式：零 API Key、零第三方依赖
    LLM_API_KEY=xxx python demo.py # 生成式模式：接 OpenAI 兼容接口（可选）

可选环境变量：LLM_BASE_URL（默认 https://api.deepseek.com）、LLM_MODEL（默认 deepseek-chat）。
两种模式共用同一套数据门槛、引用回链与 grounded 校验，切生成层不影响拒答纪律。

退出码：0 = 拒答正确率 100% 且评测完成；1 = 拒答失守（这是硬红线）。
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request
from pathlib import Path

from trusted_rag import TrustedRAG

ROOT = Path(__file__).resolve().parent
EVAL = ROOT / "eval_set.jsonl"
OUT = ROOT / "evaluation.json"

PROMPT = """你是高校食堂肉类溯源系统的查询助手。只能依据下面【资料片段】回答。
规则：
1. 每个结论后必须标注来源编号，格式如 [1]；没有编号的结论无效。
2. 资料里没有的信息，直接回答"未在已核验记录中找到依据"，禁止推测或补充常识。
3. 不评价食品是否安全，只陈述记录里写了什么。
4. 数量带单位，时间带日期；多个批次分条列出，不合并成一个数。

【资料片段】
{blocks}

【问题】{question}
【回答】"""


def build_generator():
    """可选 LLM 生成层。没有 key 就返回 None，走离线抽取式。"""
    key = os.environ.get("LLM_API_KEY")
    if not key:
        return None
    base = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/")
    model = os.environ.get("LLM_MODEL", "deepseek-chat")

    def generate(question: str, contexts: list[dict]) -> str:
        blocks = "\n".join(f"[{c['index']}] {c['content']}" for c in contexts)
        body = {
            "model": model,
            "messages": [{"role": "user", "content": PROMPT.format(blocks=blocks, question=question)}],
            "temperature": 0.1,
        }
        req = urllib.request.Request(
            base + "/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
        raw = urllib.request.urlopen(req, timeout=30).read().decode("utf-8")
        return json.loads(raw)["choices"][0]["message"]["content"]

    return generate


def main() -> int:
    gen = build_generator()
    rag = TrustedRAG(generator=gen)
    print(f"语料：{len(rag.chunks)} 个片段（事件 {sum(1 for c in rag.chunks if c['sourceType']=='EVENT')} "
          f"/ 规则 {sum(1 for c in rag.chunks if c['sourceType']=='STANDARD')}）")
    print(f"生成层：{'LLM（'+os.environ.get('LLM_MODEL','deepseek-chat')+'）' if gen else '离线抽取式（零 API Key）'}")
    print(f"链核验：{'逐条 MATCHED' if rag.meta.get('chainVerified') else '降级为 CONFIRMED，见 meta'}")

    report = rag.evaluate(EVAL)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    m = report["metrics"]
    print("\n=== 评测结果 ===")
    for k, v in m.items():
        print(f"  {k}: {v}")
    print("\n=== 未通过用例 ===")
    failed = [r for r in report["rows"] if not r["pass"]]
    for r in failed:
        print(f"  {r['id']} {r['question']} → {r['detail']} 引用={r['citedBatches']}")
    if not failed:
        print("  无，20 条全部通过")

    print("\n=== 示例问答 ===")
    for q in ["PB20261118001 这批猪肉的检验结论是什么？",
              "召回之后还能继续销售吗？",
              "PB29991111001 的检疫证号是多少？",
              "这批肉吃了安全吗？"]:
        r = rag.answer(q)
        print(f"\nQ: {q}")
        print(f"  grounded={r['grounded']} mode={r['mode']} 引用={len(r['citations'])}")
        print(f"  A: {r['answer'][:220]}")
        if r["citations"]:
            print(f"  出处: {r['citations'][0]['eventType']} / {r['citations'][0]['batchId']} / "
                  f"{r['citations'][0]['verifyStatus']} / score={r['citations'][0]['score']}")
    print(f"\n完整评测报告：{OUT}")
    return 0 if m["refusalAccuracy"] == 1.0 else 1


if __name__ == "__main__":
    sys.exit(main())
