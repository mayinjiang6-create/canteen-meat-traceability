#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把语料 / 规则 / 评测集内嵌进单文件产品页，生成 product.html。

    python build_product.py

产物 `product.html` 是一个完全离线的交互式产品：
双击即可打开，不需要 Python、不需要服务器、不需要链、不需要 API Key。
语料更新后重跑本脚本即可，逻辑与页面模板分离。
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / "product.template.html"
OUT = ROOT / "product.html"

corpus = json.loads((ROOT / "data/events.json").read_text(encoding="utf-8"))
events = [{k: e[k] for k in ("eventId", "batchId", "eventType", "bizTime",
                             "verifyStatus", "dataDigest", "text")}
          for e in corpus["events"]]
docs = [{"docId": d["docId"], "content": d["content"]}
        for d in json.loads((ROOT / "data/knowledge_docs.json").read_text(encoding="utf-8"))]
cases = [json.loads(l) for l in
         (ROOT / "eval_set.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]

payload = {"meta": corpus["meta"], "events": events, "docs": docs, "evalCases": cases}
data_js = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

html = TEMPLATE.read_text(encoding="utf-8")
if "__RAG_DATA__" not in html:
    raise SystemExit("模板里找不到 __RAG_DATA__ 占位符，请先检查 product.template.html")
OUT.write_text(html.replace("__RAG_DATA__", data_js), encoding="utf-8")
print(f"已生成 {OUT}")
print(f"  事件 {len(events)} / 规则 {len(docs)} / 评测 {len(cases)}")
print(f"  文件大小 {OUT.stat().st_size / 1024:.1f} KB（单文件，双击即可打开）")
