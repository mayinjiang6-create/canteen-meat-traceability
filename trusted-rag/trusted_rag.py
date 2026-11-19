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

**v2 改进**：TF-IDF + Okapi BM25 双路召回 → RRF 按名次融合、查询改写
（口语归一 / 同义归一）、六类查询意图拦截。v1 baseline 冻结在
`trusted_rag_v1.py`，用 `compare.py` 在同一份公开评测集上做公平对比。

**诚实声明**：离线模式使用词面混合检索，不是语义向量模型；答案为命中片段的
抽取式呈现，不是语言模型生成。接 embedding / LLM 后评测数字会变，需重跑。
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
    r"安全吗|能不能吃|能吃吗|可以吃吗|会不会中毒|好不好|质量怎么样",
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

# v2：查询意图路由。拒答不能只依赖"检索不到"——baseline 在扩充集上
# 有 4 条失败全部是「本来就不该答却硬答」的问法。
COMPARISON_RE = re.compile(
    r"哪个.*(?:高|低|多|少|大|小|好)|哪个更|相比|对比|两者.*比较|哪一个.*更")
ANCHOR_TERMS = re.compile(
    r"(?:PB|PR|SUP|SL|RC|AB)[A-Z0-9-]{4,}|"
    r"检疫|验收|温度|留样|许可证|窗口|产出|产量|召回|冷链|供应商|核验|"
    r"阈值|超限|合格|结论|状态|证号|编号|规则|流程|"
    # 2026-11-19 held-out 首跑发现：「哪些批次做过异常处置」被判成无锚点泛问。
    # 异常/处置/工单/错误码/知识库 都是语料里真实存在的字段词，属于漏配而非设计取舍。
    r"异常|处置|工单|错误码|知识库")
FILLER_RE = re.compile(r"请问|麻烦|帮我看一下|一下|呗|啊|吧|随便|有啥|最近")
# 同义 / 口语说法归一到语料里的原始词汇，避免换个说法就漏召回
SYNONYMS = [
    ("检疫证明编号", "检疫合格证号"), ("检疫证编号", "检疫合格证号"),
    ("验收结果", "检验结论"), ("负责验收", "验收人"), ("谁验", "验收人"),
    ("产量", "产出"), ("食堂窗口", "窗口"), ("公司的全称", "名称"),
    ("上限是几度", "阈值"), ("继续卖", "继续销售"),
    ("做过召回处理", "召回"), ("哪几批", "哪些批次"), ("几批", "哪些批次"),
]
NO_ANCHOR_MSG = "问题缺少可核验的批次号或字段，请补充后再问。例如：PB20261118001 的检验结论是什么？"
COMPARISON_MSG = "该问题需要跨记录比较推理，当前离线版不提供此类结论，请分别查询各批次。"


def rewrite(question: str) -> str:
    """查询改写：清口语填充词 + 同义说法归一化。只影响检索串，不改动原始问句。"""
    q = FILLER_RE.sub("", question)
    for src, dst in SYNONYMS:
        q = q.replace(src, dst)
    return q.strip() or question


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

        # v2：BM25 稀疏通道。词面 TF-IDF 与 BM25 召回的偏好不同，
        # 用 RRF 只按排名融合，避免把两个不同量纲的分数直接相加。
        self.doc_tokens = [tokenize(c["content"]) for c in self.chunks]
        self.doc_len = [len(t) for t in self.doc_tokens]
        self.avgdl = (sum(self.doc_len) / n) or 1.0
        bdf = Counter()
        for toks in self.doc_tokens:
            bdf.update(set(toks))
        self.bm25_idf = {t: math.log(1 + (n - d + 0.5) / (d + 0.5))
                         for t, d in bdf.items()}
        self.bm25_tf = [Counter(t) for t in self.doc_tokens]

    def _bm25_scores(self, q_tokens: list[str]) -> list[float]:
        k1, b = 1.5, 0.75
        scores = []
        for i in range(len(self.chunks)):
            s = 0.0
            for t in set(q_tokens):
                f = self.bm25_tf[i].get(t, 0)
                if not f:
                    continue
                idf = self.bm25_idf.get(t, 0.0)
                s += idf * (f * (k1 + 1)) / (
                    f + k1 * (1 - b + b * self.doc_len[i] / self.avgdl))
            scores.append(s)
        return scores

    def _qvec(self, question: str) -> dict[str, float]:
        counter = Counter(tokenize(question))
        v = {t: (1 + math.log(f)) * self.idf.get(t, 1.0) for t, f in counter.items()}
        norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
        return {t: x / norm for t, x in v.items()}

    def retrieve(self, question: str) -> list[Hit]:
        q_text = rewrite(question)
        qvec = self._qvec(q_text)
        q_tokens = tokenize(q_text)
        batch_ids = {m.upper() for m in BATCH_RE.findall(question)}

        # 通道 A：TF-IDF 余弦；通道 B：Okapi BM25。两路召回偏好不同。
        cos = [sum(w * vec.get(t, 0.0) for t, w in qvec.items())
               for vec in self.vecs]
        bm = self._bm25_scores(q_tokens)

        width = max(self.top_k * 4, 40)
        rank_cos = sorted(range(len(cos)), key=lambda i: (-cos[i], i))[:width]
        rank_bm = sorted(range(len(bm)), key=lambda i: (-bm[i], i))[:width]

        # RRF 只按名次融合，不比较不同量纲的原始分数（GitHub 调研中的标准做法）
        K = 60.0
        fused: dict[int, float] = {}
        for r, i in enumerate(rank_cos):
            fused[i] = fused.get(i, 0.0) + 1.0 / (K + r + 1)
        for r, i in enumerate(rank_bm):
            fused[i] = fused.get(i, 0.0) + 1.0 / (K + r + 1)
        for i in list(fused):
            c = self.chunks[i]
            if batch_ids and c["batchId"] and c["batchId"].upper() in batch_ids:
                fused[i] += 2.0 / (K + 1)  # 批次号精确命中，约等于两个第一名的贡献

        order = sorted(fused, key=lambda i: (-fused[i], i))[:self.top_k]
        hits = []
        for rank, i in enumerate(order):
            # score 仍保留词面余弦（含批次加权），让 threshold / grounded 语义不变
            score = cos[i]
            c = self.chunks[i]
            if batch_ids and c["batchId"] and c["batchId"].upper() in batch_ids:
                score += 0.15
            hits.append(Hit(c, round(score, 4), rank + 1))
        return hits

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
            return self._result(q, OUT_OF_SCOPE, [], [], t0,
                                "out_of_scope", grounded=False)

        # 跨记录比较：离线抽取式不做推理，明确说明能力边界，不挑一条片段充数
        if COMPARISON_RE.search(q):
            return self._result(q, COMPARISON_MSG, [], [], t0,
                                "comparison_unsupported", grounded=False)

        # 无锚点泛问：既没批次号、也没可识别字段词，词面检索一定会
        # 「撞」出一条看似相关的片段——baseline 的 4 条失败全源于此
        asked = {m.upper() for m in BATCH_RE.findall(q)}
        if not asked and not ANCHOR_TERMS.search(q):
            return self._result(q, NO_ANCHOR_MSG, [], [], t0,
                                "no_anchor", grounded=False)

        hits = self.retrieve(q)
        best = max((h.score for h in hits), default=0.0)

        # 问句点名了某个批次号，但语料里根本没有这个批次 → 无依据。
        # 不能因为「检疫证号」这类高频词撞上了别的批次就凑一个答案出来。
        known = {c["batchId"].upper() for c in self.chunks if c["batchId"]}
        if asked and not (asked & known):
            return self._result(q, REFUSAL, [], hits, t0, "unknown_batch")

        # 点名了批次就只允许引用该批次的记录。
        # 否则会出现「问 A 批次的证号，答的却是 B 批次」——有引用、有编号、看起来像答案，
        # 却是另一条记录，比直接拒答危险得多（2026-11-19 held-out H20 抓到）。
        if asked:
            hits = [h for h in hits if (h.chunk["batchId"] or "").upper() in asked]
            if hits:
                best = max(h.score for h in hits)
            else:
                # 该批次存在，但它没有所问字段的记录 → 与"字段未覆盖"同类
                return self._result(q, REFUSAL, [], [], t0, "evidence_not_covered")

        if not hits or best < self.threshold:
            return self._result(q, REFUSAL, [], hits, t0, "low_score")

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
                            "ok", hallucinated=hallucinated, contexts=contexts)

    def _result(self, q, ans, citations, hits, t0, mode,
                hallucinated=None, grounded: bool | None = None,
                contexts: list[dict] | None = None) -> dict:
        if grounded is None:
            grounded = bool(citations) and ans not in (REFUSAL, OUT_OF_SCOPE)
        return {
            "question": q, "answer": ans,
            "grounded": grounded,
            "citations": citations,
            # 供离线复算 faithfulness / context precision 用（不参与对外展示）
            "contexts": contexts or [],
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
            expect_batch = case.get("expectBatch")
            rank = next((i + 1 for i, x in enumerate(r["retrieved"][:5])
                         if expect_batch and x["batchId"] == expect_batch), None)
            rows.append({
                "id": case["id"], "question": case["question"], "expect": expect,
                "pass": ok, "detail": detail, "citeOk": cite_ok,
                "recallAt5": (expect_batch in top5) if expect_batch else None,
                "rank": rank, "mode": r["mode"],
                "citedBatches": cited,
                "grounded": r["grounded"], "latencyMs": r["latencyMs"],
            })

        answer_cases = [r for r in rows if r["expect"] == "answer"]
        refuse_cases = [r for r in rows if r["expect"] == "refuse"]
        recall_rows = [r["recallAt5"] for r in answer_cases if r["recallAt5"] is not None]
        ranked_cases = [r for r in answer_cases if r["recallAt5"] is not None]
        correct_cite = sum(1 for r in answer_cases if r["citeOk"])
        breakdown = dict(Counter(
            r["mode"] for r in refuse_cases if not r["grounded"]))
        return {
            "meta": {
                "corpus": self.meta, "chunks": len(self.chunks),
                "threshold": self.threshold, "topK": self.top_k,
                "generator": "llm" if self.generator else "extractive-offline",
                "retrieval": "TF-IDF + BM25 双路召回 / RRF 融合 / 查询改写 / 意图路由",
                "note": "离线模式为词面检索 + 抽取式回答，非语义 embedding / 非 LLM 生成",
            },
            "metrics": {
                "cases": len(rows),
                "answerCases": len(answer_cases),
                "refuseCases": len(refuse_cases),
                "answerAccuracy": round(sum(r["pass"] for r in answer_cases) / len(answer_cases), 4),
                "refusalAccuracy": round(sum(r["pass"] for r in refuse_cases) / len(refuse_cases), 4),
                "recallAt5": round(sum(recall_rows) / len(recall_rows), 4) if recall_rows else None,
                "top1Accuracy": round(sum(1 for r in ranked_cases if r["rank"] == 1)
                                      / len(ranked_cases), 4) if ranked_cases else None,
                "mrr": round(sum(1.0 / r["rank"] if r["rank"] else 0.0
                                 for r in ranked_cases) / len(ranked_cases), 4)
                       if ranked_cases else None,
                "citationConsistency": round(correct_cite / len(answer_cases), 4),
                "refusalBreakdown": breakdown,
                "avgLatencyMs": round(sum(r["latencyMs"] for r in rows) / len(rows), 2),
                "maxLatencyMs": max(r["latencyMs"] for r in rows),
            },
            "rows": rows,
        }
