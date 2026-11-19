#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""可信 RAG：只回答能被链上核验证明的记录。

设计来源：docs/tech/09-AI模块设计.md（TECH-09）。
与通用 RAG Demo 的区别在数据门槛和拒答纪律，不在模型：

  1. 语料只收录链上摘要与本地一致的 MATCHED 事件（build_corpus.py 逐条查链）
  2. 答案必须带 [n] 引用，引用可回跳到 eventId / batchId / 核验状态
  3. grounded 三重校验：有引用 + 相似度达阈 + 答案真的标了编号，缺一即拒答
  4. 越界问题（安全性判定、预测、写作）直接拒答，不交给模型自由发挥
  5. 生成层可插拔：默认抽取式（零 API Key、零第三方依赖），
     传 generator 即可换成真 LLM，上面 1~4 条约束完全不变

**诚实声明**：离线模式使用字符 n-gram + TF-IDF 词面检索，不是语义向量模型；
答案为命中片段的抽取式呈现，不是语言模型生成。这两点在 README 里明写，
避免把规则模拟包装成「智能」。接 embedding / LLM 后评测数字会变，需重跑。
"""
from __future__ import annotations

import json
import math
import re
import time
from collections import Counter
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent
CORPUS = ROOT / "data" / "events.json"
DOCS = ROOT / "data" / "knowledge_docs.json"

REFUSAL = "未在已核验记录中找到依据。"
OUT_OF_SCOPE = "该问题超出溯源记录范围。"

# 越界问题：这些不是「检索不到」，是「本来就不该答」，必须在进检索器前拦掉
OUT_OF_SCOPE_PATTERNS = [
    r"安全吗|能不能吃|会不会中毒|好不好|质量怎么样",
    r"明天|下周|预测|该进多少|建议采购",
    r"帮我写|写一段|总结全文|翻译|作诗",
    r"你是谁|你是.*模型|GPT|ChatGPT",
]
BATCH_RE = re.compile(r"\b((?:PB|PR|SUP|SL|RC|AB|AC|CC|LY)[A-Z0-9-]{4,})\b", re.I)
CJK_RE = re.compile(r"[\u4e00-\u9fff]+")
ASCII_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# 答案覆盖性校验：问了某个字段，答案里就必须真的出现该字段的证据。
# 否则会出现「有引用、有编号、但答非所问」——比编造更隐蔽的失败。
FIELD_EVIDENCE = [
    (r"检疫证号|检疫合格证号", r"检疫合格证号|JX-JY-|JY-360"),
    (r"许可证号", r"许可证号"),
    (r"留样号", r"留样号"),
    (r"验收人|谁验的", r"验收人"),
    (r"到货.*温度|中心温度|温度是多少", r"℃"),
    (r"销售.*窗口|哪个窗口", r"窗口"),
    (r"产出多少|产出量", r"产出"),
    (r"检验结论|合格吗|合格不|是否合格", r"检验结论"),
]


def tokenize(text: str) -> list[str]:
    """零依赖中文检索：单字 + 相邻双字 + ASCII 词（含完整批次号）。"""
    tokens: list[str] = []
    for m in ASCII_RE.finditer(text):
        tokens.append(m.group(0).lower())
    for seg in CJK_RE.findall(text):
        for ch in seg:
            tokens.append(ch)
        for i in range(len(seg) - 1):
            tokens.append(seg[i:i + 2])
    return tokens


class Hit:
    __slots__ = ("chunk", "score", "index")

    def __init__(self, chunk: dict, score: float, index: int):
        self.chunk, self.score, self.index = chunk, score, index


class TrustedRAG:
    def __init__(self, corpus_path: Path = CORPUS, docs_path: Path = DOCS,
                 threshold: float = 0.08, top_k: int = 5,
                 generator: Callable[[str, list[dict]], str] | None = None):
        self.threshold = threshold
        self.top_k = top_k
        self.generator = generator
        self.chunks: list[dict] = []
        self._load(corpus_path, docs_path)
        self._build_index()

    # ---------------------------------------------------------------- 语料
    def _load(self, corpus_path: Path, docs_path: Path) -> None:
        data = json.loads(corpus_path.read_text(encoding="utf-8"))
        self.meta = data.get("meta", {})
        for ev in data["events"]:
            # 数据门槛：链不可达时的降级语料（CONFIRMED_UNVERIFIED）不进知识库
            if ev.get("verifyStatus") != "MATCHED":
                continue
            self.chunks.append({
                "sourceType": "EVENT", "sourceId": ev["eventId"],
                "batchId": ev["batchId"], "eventType": ev["eventType"],
                "bizTime": ev["bizTime"], "verifyStatus": ev["verifyStatus"],
                "dataDigest": ev["dataDigest"], "content": ev["text"],
            })
        if docs_path.exists():
            for d in json.loads(docs_path.read_text(encoding="utf-8")):
                self.chunks.append({
                    "sourceType": "STANDARD", "sourceId": d["docId"],
                    "batchId": None, "eventType": None, "bizTime": None,
                    "verifyStatus": "DOCUMENT", "dataDigest": None,
                    "content": d["content"],
                })

    # ---------------------------------------------------------------- 索引
    def _build_index(self) -> None:
        n = len(self.chunks)
        raw = [Counter(tokenize(c["content"])) for c in self.chunks]
        df = Counter()
        for counter in raw:
            df.update(counter.keys())
        self.idf = {t: math.log((n + 1) / (d + 1)) + 1.0 for t, d in df.items()}
        self.vecs = []
        for counter in raw:
            v = {t: (1 + math.log(f)) * self.idf.get(t, 1.0) for t, f in counter.items()}
            norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
            self.vecs.append({t: x / norm for t, x in v.items()})

    def _qvec(self, question: str) -> dict[str, float]:
        counter = Counter(tokenize(question))
        v = {t: (1 + math.log(f)) * self.idf.get(t, 1.0) for t, f in counter.items()}
        norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
        return {t: x / norm for t, x in v.items()}

    def retrieve(self, question: str) -> list[Hit]:
        q = self._qvec(question)
        batch_ids = {m.upper() for m in BATCH_RE.findall(question)}
        scored = []
        for i, vec in enumerate(self.vecs):
            # 向量已归一化，点积即余弦
            score = sum(w * vec.get(t, 0.0) for t, w in q.items())
            chunk = self.chunks[i]
            if batch_ids and chunk["batchId"] and chunk["batchId"].upper() in batch_ids:
                score += 0.15  # 批次号精确命中加权（TECH-09 §5.3）
            scored.append((score, i))
        scored.sort(key=lambda x: (-x[0], x[1]))
        top = scored[:self.top_k]
        return [Hit(self.chunks[i], round(s, 4), rank + 1)
                for rank, (s, i) in enumerate(top)]

    # ---------------------------------------------------------------- 生成层
    @staticmethod
    def _extractive(question: str, contexts: list[dict]) -> str:
        """离线默认：只把命中的已核验片段按引用编号陈列，不编造、不推断。"""
        lines = []
        for c in contexts:
            lines.append(f"{c['content']} [{c['index']}]")
        return "\n".join(lines)

    # ---------------------------------------------------------------- 问答
    def answer(self, question: str) -> dict:
        t0 = time.perf_counter()
        q = question.strip()
        if any(re.search(p, q) for p in OUT_OF_SCOPE_PATTERNS):
            return self._result(q, OUT_OF_SCOPE, [], [], t0, "out_of_scope")

        hits = self.retrieve(q)
        best = hits[0].score if hits else 0.0
        if not hits or best < self.threshold:
            return self._result(q, REFUSAL, [], hits, t0, "low_score")

        # 问句点名了某个批次号，但语料里根本没有这个批次 → 无依据。
        # 不能因为「检疫证号」这类高频词撞上了别的批次就凑一个答案出来。
        asked = {m.upper() for m in BATCH_RE.findall(q)}
        known = {c["batchId"].upper() for c in self.chunks if c["batchId"]}
        if asked and not (asked & known):
            return self._result(q, REFUSAL, [], hits, t0, "unknown_batch")

        # 「哪些 / 几批 / 有没有」类聚合问题给 3 条上下文，其余给 1 条
        multi = bool(re.search(r"哪些|哪几|几批|有没有|列出|对比", q))
        chosen = hits[:3] if multi else hits[:1]
        contexts = [{
            "index": h.index, "eventId": h.chunk["sourceId"],
            "batchId": h.chunk["batchId"], "eventType": h.chunk["eventType"],
            "verifyStatus": h.chunk["verifyStatus"], "score": h.score,
            "content": h.chunk["content"],
        } for h in chosen]

        if self.generator:
            raw_answer = self.generator(q, contexts)
        else:
            raw_answer = self._extractive(q, contexts)

        # 覆盖性校验：所问字段的证据必须真的出现在答案里，否则宁可拒答
        for qpat, apat in FIELD_EVIDENCE:
            if re.search(qpat, q) and not re.search(apat, raw_answer):
                return self._result(q, REFUSAL, [], hits, t0, "evidence_not_covered")

        # grounded 三重校验：有引用 / 相似度达阈 / 答案里真的标了编号
        has_marker = bool(re.search(r"\[\d+\]", raw_answer))
        grounded = bool(contexts) and best >= self.threshold and has_marker
        if not grounded:
            return self._result(q, REFUSAL, [], hits, t0, "grounded_rejected")

        # 引用编号越界 / 指向未提供的片段 → 丢弃并记 hallucinated-citation
        valid_idx = {c["index"] for c in contexts}
        used = {int(x) for x in re.findall(r"\[(\d+)\]", raw_answer)}
        hallucinated = sorted(used - valid_idx)
        citations = [{
            "index": c["index"], "eventId": c["eventId"], "batchId": c["batchId"],
            "eventType": c["eventType"], "verifyStatus": c["verifyStatus"],
            "score": c["score"],
        } for c in contexts if c["index"] in used]

        return self._result(q, raw_answer, citations, hits, t0,
                            "ok", hallucinated=hallucinated)

    def _result(self, q, ans, citations, hits, t0, mode, hallucinated=None) -> dict:
        return {
            "question": q, "answer": ans,
            "grounded": bool(citations) and ans not in (REFUSAL, OUT_OF_SCOPE),
            "citations": citations,
            "retrieved": [{"batchId": h.chunk["batchId"],
                           "eventType": h.chunk["eventType"],
                           "sourceType": h.chunk["sourceType"],
                           "score": h.score} for h in hits],
            "mode": mode,
            "generator": "llm" if self.generator else "extractive-offline",
            "hallucinatedCitations": hallucinated or [],
            "latencyMs": round((time.perf_counter() - t0) * 1000, 2),
        }

    # ---------------------------------------------------------------- 评测
    def evaluate(self, eval_path: Path) -> dict:
        cases = [json.loads(l) for l in eval_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        rows = []
        for case in cases:
            r = self.answer(case["question"])
            expect = case["expect"]
            if expect == "refuse":
                ok = not r["grounded"]
                detail = "正确拒答" if ok else "错误作答"
            else:
                batches = {c["batchId"] for c in r["citations"]}
                kw_ok = all(k in r["answer"] for k in case.get("keywords", []))
                batch_ok = (not case.get("expectBatch") or case["expectBatch"] in batches)
                ok = r["grounded"] and kw_ok and batch_ok
                detail = f"grounded={r['grounded']} 关键词={kw_ok} 批次={batch_ok}"
            top5 = [x["batchId"] for x in r["retrieved"][:5]]
            cited = [c["batchId"] for c in r["citations"]]
            allowed = set(case.get("allowBatches") or
                          ([case["expectBatch"]] if case.get("expectBatch") else []))
            # 引用命中率：返回的引用必须全部落在人工标注的集合内（TECH-09 §8.2）
            cite_ok = bool(r["citations"]) and (all(b in allowed for b in cited) if allowed else True)
            rows.append({
                "id": case["id"], "question": case["question"], "expect": expect,
                "pass": ok, "detail": detail, "citeOk": cite_ok,
                "recallAt5": (case.get("expectBatch") in top5) if case.get("expectBatch") else None,
                "citedBatches": cited,
                "grounded": r["grounded"], "latencyMs": r["latencyMs"],
            })

        answer_cases = [r for r in rows if r["expect"] == "answer"]
        refuse_cases = [r for r in rows if r["expect"] == "refuse"]
        recall_rows = [r["recallAt5"] for r in answer_cases if r["recallAt5"] is not None]
        correct_cite = sum(1 for r in answer_cases if r["citeOk"])
        return {
            "meta": {
                "corpus": self.meta, "chunks": len(self.chunks),
                "threshold": self.threshold, "topK": self.top_k,
                "generator": "llm" if self.generator else "extractive-offline",
                "note": "离线模式为词面检索 + 抽取式回答，非语义 embedding / 非 LLM 生成",
            },
            "metrics": {
                "cases": len(rows),
                "answerCases": len(answer_cases),
                "refuseCases": len(refuse_cases),
                "answerAccuracy": round(sum(r["pass"] for r in answer_cases) / len(answer_cases), 4),
                "refusalAccuracy": round(sum(r["pass"] for r in refuse_cases) / len(refuse_cases), 4),
                "recallAt5": round(sum(recall_rows) / len(recall_rows), 4) if recall_rows else None,
                "citationConsistency": round(correct_cite / len(answer_cases), 4),
                "avgLatencyMs": round(sum(r["latencyMs"] for r in rows) / len(rows), 2),
                "maxLatencyMs": max(r["latencyMs"] for r in rows),
            },
            "rows": rows,
        }
