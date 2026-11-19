#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把已核验语料导出成 Dify 知识库可导入的 Markdown（一条事件一个二级标题）。

为什么单独导出：Dify 的知识库按标题/分隔符切块，块内文本就是模型能看到的全部内容。
本项目要求「只回答已核验记录」，所以在**文本层面**把 eventId / 批次 / 核验状态写进每个块，
让模型和引用都能对上；同时把 4 条业务规则作为独立块。

    python deploy/dify/build_kb.py        # 产物 deploy/dify/kb_meat_trace.md
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVENTS = ROOT / "trusted-rag" / "data" / "events.json"
DOCS = ROOT / "trusted-rag" / "data" / "knowledge_docs.json"
OUT = Path(__file__).resolve().parent / "kb_meat_trace.md"


def main() -> None:
    data = json.loads(EVENTS.read_text(encoding="utf-8"))
    docs = json.loads(DOCS.read_text(encoding="utf-8"))
    events = [e for e in data["events"] if e.get("verifyStatus") == "MATCHED"]

    lines = [
        "# 高校食堂肉类溯源 · 已核验事件知识库",
        "",
        f"> 共 {len(events)} 条链上核验一致（MATCHED）的事件 + {len(docs)} 条业务规则。",
        f"> 导出时间：{data['meta'].get('generatedAt')}；来源：{data['meta'].get('source')}。",
        "> 规则：**块内没有 eventId 的内容不得作为回答依据**；核验状态不是 MATCHED 的记录不在此库。",
        "",
        "# 一、业务规则",
        "",
    ]
    for d in docs:
        lines += [f"## {d['docId']}", "", d["content"], ""]

    lines += ["# 二、已核验事件", ""]
    for e in events:
        lines += [
            f"## {e['batchId']} · {e['eventType']}",
            "",
            f"- 事件ID：{e['eventId']}",
            f"- 批次：{e['batchId']}",
            f"- 类型：{e['eventType']}",
            f"- 业务时间：{e['bizTime']}",
            f"- 核验状态：{e['verifyStatus']}",
            f"- 摘要：{e['dataDigest']}",
            f"- 记录内容：{e['text']}",
            "",
        ]

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"已生成 {OUT}")
    print(f"  事件块 {len(events)} + 规则块 {len(docs)} = {len(events) + len(docs)} 个块")
    print(f"  文件大小 {OUT.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
