#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语义检索对照：本地 BGE embedding 替换词面检索，**其余约束全部不变**。

对照口径（这是重点）：
  - 同一份公开集 60 条 + held-out 20 条
  - 同一套意图路由 / 六类拦截 / 字段覆盖校验 / 批次过滤 / grounded 校验
  - **只换检索器**：TF-IDF+BM25+RRF → BGE 余弦
这样出来的差异才能归因到"检索器"，而不是"顺手改了别的地方"。

依赖是可选的：torch + transformers（仅本脚本需要）。
    python fetch_model.py     # 下载模型到 .models/
    python embed_eval.py      # 产 embedding_eval.json

阈值怎么定：在**公开集**上网格搜一遍（0.45~0.70），挑答案类与拒答类均值最高的；
held-out 只用最终阈值跑一次，不参与选择——否则 held-out 就失效了。
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np

from trusted_rag import BATCH_RE, TrustedRAG, rewrite

ROOT = Path(__file__).resolve().parent
MODEL_DIR = Path(os.environ.get("BGE_MODEL_DIR", ROOT / ".models" / "bge-small-zh-v1.5"))
OUT = ROOT / "embedding_eval.json"

# BGE 官方建议：短查询检索长段落时给 query 加指令前缀，passage 不加
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


class Encoder:
    def __init__(self, model_dir: Path, max_length: int = 256):
        import torch
        from transformers import AutoModel, AutoTokenizer

        self.torch = torch
        self.tok = AutoTokenizer.from_pretrained(str(model_dir))
        self.model = AutoModel.from_pretrained(str(model_dir))
        self.model.eval()
        self.max_length = max_length

    def encode(self, texts: list[str], is_query: bool = False, batch: int = 16) -> np.ndarray:
        torch = self.torch
        if is_query:
            texts = [QUERY_INSTRUCTION + t for t in texts]
        out = []
        for i in range(0, len(texts), batch):
            chunk = texts[i:i + batch]
            inputs = self.tok(chunk, padding=True, truncation=True,
                              max_length=self.max_length, return_tensors="pt")
            with torch.no_grad():
                hidden = self.model(**inputs).last_hidden_state
            mask = inputs["attention_mask"].unsqueeze(-1).float()
            # mean pooling（BGE 的推荐做法），再 L2 归一化
            emb = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
            emb = torch.nn.functional.normalize(emb, p=2, dim=1)
            out.append(emb.cpu().numpy())
        return np.vstack(out)


class EmbeddingRAG(TrustedRAG):
    """只把检索通道换成语义向量，拦截与校验逻辑完全继承。"""

    def __init__(self, model_dir: Path, threshold: float = 0.60, top_k: int = 5):
        self.model_dir = model_dir
        super().__init__(threshold=threshold, top_k=top_k)

    def _build_index(self) -> None:
        self.encoder = Encoder(self.model_dir)
        self.chunk_vecs = self.encoder.encode([c["content"] for c in self.chunks])

    def retrieve(self, question: str):
        from trusted_rag import Hit

        q_text = rewrite(question)
        qv = self.encoder.encode([q_text], is_query=True)[0]
        sims = self.chunk_vecs @ qv
        batch_ids = {m.upper() for m in BATCH_RE.findall(question)}

        scored = [(i, float(sims[i])) for i in range(len(self.chunks))]
        # 批次号精确命中给一点加权：这是业务约束，不属于"检索器能力"，两版都保留
        if batch_ids:
            scored = [(i, s + (0.05 if (self.chunks[i]["batchId"] or "").upper() in batch_ids else 0.0))
                      for i, s in scored]
        scored.sort(key=lambda x: (-x[1], x[0]))
        hits = []
        for rank, (i, score) in enumerate(scored[: self.top_k]):
            hits.append(Hit(self.chunks[i], round(score, 4), rank + 1))
        return hits


def metrics_of(rag: TrustedRAG, path: Path) -> dict:
    rep = rag.evaluate(path)
    m = rep["metrics"]
    return {"cases": m["cases"], "answerAccuracy": m["answerAccuracy"],
            "refusalAccuracy": m["refusalAccuracy"], "recallAt5": m["recallAt5"],
            "top1Accuracy": m["top1Accuracy"], "mrr": m["mrr"],
            "failed": [r["id"] for r in rep["rows"] if not r["pass"]],
            "refusalBreakdown": m["refusalBreakdown"]}


def probe(rag: TrustedRAG, questions: list[str]) -> dict:
    out = {}
    for q in questions:
        r = rag.answer(q)
        out[q] = {"grounded": r["grounded"], "mode": r["mode"],
                  "answer": r["answer"][:90],
                  "cited": [c["batchId"] for c in r["citations"]]}
    return out


def main() -> int:
    if not MODEL_DIR.exists():
        print(f"模型目录不存在：{MODEL_DIR}\n先跑 python fetch_model.py")
        return 2
    try:
        import torch  # noqa: F401
        import transformers  # noqa: F401
    except ImportError:
        print("缺少 torch / transformers。本脚本是可选对照，不影响 demo.py 运行。")
        print("  pip install torch transformers")
        return 2

    eval_set = ROOT / "eval_set.jsonl"
    holdout = ROOT / "eval_holdout.jsonl"
    probes = [
        "猪肉合格不？",                     # 已知未解决：泛问语义判断
        "供应商鑫旺的证号",                  # 已知未解决：同名供应商实体消歧
        "PB20261118001 的检验结论是什么？",   # 对照：应正常作答
        "这批肉吃了安全吗？",                # 对照：应拒答
    ]

    print("加载模型…", end=" ", flush=True)
    grid = []
    for th in [0.45, 0.50, 0.55, 0.60, 0.65, 0.70]:
        rag = EmbeddingRAG(MODEL_DIR, threshold=th)
        m = metrics_of(rag, eval_set)
        mean = (m["answerAccuracy"] + m["refusalAccuracy"]) / 2
        grid.append({"threshold": th, "answerAccuracy": m["answerAccuracy"],
                     "refusalAccuracy": m["refusalAccuracy"],
                     "mean": round(mean, 4), "failed": m["failed"]})
        print(f"\n  θ={th:.2f} 答案={m['answerAccuracy']} 拒答={m['refusalAccuracy']} 失败={m['failed']}")
    best = max(grid, key=lambda x: (x["mean"], x["refusalAccuracy"]))
    print(f"\n公开集选定阈值 θ={best['threshold']}（只在公开集上选，held-out 不参与）")

    rag = EmbeddingRAG(MODEL_DIR, threshold=best["threshold"])
    pub = metrics_of(rag, eval_set)
    hold = metrics_of(rag, holdout)
    probes_out = probe(rag, probes)
    baseline = TrustedRAG()
    probes_base = probe(baseline, probes)

    report = {
        "meta": {
            "model": str(MODEL_DIR),
            "modelName": "BAAI/bge-small-zh-v1.5",
            "note": "只替换检索器（词面 → BGE 余弦），意图路由/六类拦截/字段校验/批次过滤/grounded 全部不变",
            "threshold": best["threshold"],
            "thresholdSelection": "在公开集 60 条上网格搜索，held-out 不参与",
        },
        "thresholdGrid": grid,
        "publicSet": pub, "holdoutSet": hold,
        "baseline": {"publicSet": metrics_of(baseline, eval_set),
                     "holdoutSet": metrics_of(baseline, holdout)},
        "probes": {"embedding": probes_out, "baseline": probes_base},
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== 语义检索 vs 词面检索（同一份题、同一套约束）===")
    rows = [("公开集 答案正确率", pub["answerAccuracy"], report["baseline"]["publicSet"]["answerAccuracy"]),
            ("公开集 拒答正确率", pub["refusalAccuracy"], report["baseline"]["publicSet"]["refusalAccuracy"]),
            ("held-out 答案正确率", hold["answerAccuracy"], report["baseline"]["holdoutSet"]["answerAccuracy"]),
            ("held-out 拒答正确率", hold["refusalAccuracy"], report["baseline"]["holdoutSet"]["refusalAccuracy"])]
    print(f"  {'指标':<22}{'语义':>8}{'词面':>8}")
    for name, a, b in rows:
        print(f"  {name:<22}{str(a):>8}{str(b):>8}")
    print(f"\n  语义版失败用例：公开 {pub['failed'] or '无'} / held-out {hold['failed'] or '无'}")
    print("\n  两个已知难题：")
    for q in probes[:2]:
        e, b = probes_out[q], probes_base[q]
        print(f"    {q}\n      语义: grounded={e['grounded']} mode={e['mode']} 引用={e['cited']}")
        print(f"      词面: grounded={b['grounded']} mode={b['mode']} 引用={b['cited']}")
    print(f"\n报告：{OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
