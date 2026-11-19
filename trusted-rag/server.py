#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""可信 RAG 问答 HTTP 服务（零第三方依赖，可直接容器化）。

提供三个接口：
  GET  /health          健康检查
  POST /ask              {"question": "..."} → answer / grounded / citations
  GET  /metrics         评测指标摘要（读 evaluation.json）

设计目的：让 Demo 从「一张静态页」升级为「可对接业务系统的 AI 服务」，
这是 AI 交付 / 实施岗 JD 里反复出现的「API 对接 / 全栈系统集成」能力证据。

启动：
  PORT=8000 python server.py
容器：
  docker compose up --build
"""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from trusted_rag import TrustedRAG

ROOT = Path(__file__).resolve().parent
PORT = int(os.environ.get("PORT", "8000"))
rag = TrustedRAG()


class Handler(BaseHTTPRequestHandler):
    server_version = "TrustedRAG/1.0"

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            self._send(200, {
                "status": "ok",
                "chunks": len(rag.chunks),
                "chainVerified": rag.meta.get("chainVerified"),
                "generator": "llm" if rag.generator else "extractive-offline",
            })
        elif path == "/metrics":
            p = ROOT / "evaluation.json"
            if not p.exists():
                self._send(503, {"error": "evaluation.json 不存在，请先运行 python demo.py"})
                return
            report = json.loads(p.read_text(encoding="utf-8"))
            self._send(200, {"meta": report["meta"], "metrics": report["metrics"]})
        elif path == "/":
            p = ROOT / "index.html"
            body = p.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self._send(404, {"error": "not found", "endpoints": ["/health", "/ask", "/metrics", "/"]})

    def do_POST(self):
        if urlparse(self.path).path != "/ask":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            question = body.get("question", "")
            if not isinstance(question, str) or not question.strip():
                self._send(400, {"error": "question 必填且不能为空"})
                return
            result = rag.answer(question)
            # 对外只暴露必要字段，内部检索分数不暴露给调用方
            self._send(200, {
                "question": result["question"],
                "answer": result["answer"],
                "grounded": result["grounded"],
                "citations": result["citations"],
                "mode": result["mode"],
                "generator": result["generator"],
                "latencyMs": result["latencyMs"],
            })
        except json.JSONDecodeError:
            self._send(400, {"error": "请求体必须是合法 JSON"})
        except Exception as exc:
            self._send(500, {"error": f"服务内部错误：{type(exc).__name__}"})

    def log_message(self, fmt, *args):
        # 保留简洁访问日志，便于交付现场排障
        print(f"[rag] {self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[rag] Trusted RAG API 已启动：http://0.0.0.0:{PORT}", flush=True)
    print(f"[rag] 语料片段 {len(rag.chunks)}，链核验={rag.meta.get('chainVerified')}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
