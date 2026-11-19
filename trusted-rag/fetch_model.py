#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载本地中文 embedding 模型（BGE-small-zh-v1.5），只走标准库。

为什么不用 huggingface_hub：国内直连 huggingface.co 常常超时，ModelScope 可达。
本脚本按文件列表从 ModelScope 的 resolve 地址拉取，支持断点续传（已存在则跳过）。

    python fetch_model.py                 # 默认下到 .models/bge-small-zh-v1.5
    python fetch_model.py --dir D:/models/bge-small-zh-v1.5

模型：BAAI/bge-small-zh-v1.5（约 95 MB，MIT 许可，以模型卡为准）
用途：仅在 `embed_eval.py` 里做「语义检索 vs 词面检索」的对照，**不是运行 demo.py 的必需依赖**。
"""
from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

BASE = "https://www.modelscope.cn/models/BAAI/bge-small-zh-v1.5/resolve/master/"
FILES = ["config.json", "vocab.txt", "tokenizer_config.json", "tokenizer.json",
         "special_tokens_map.json", "model.safetensors", "pytorch_model.bin"]
REQUIRED = ["config.json", "vocab.txt", "model.safetensors"]


def human(n: int) -> str:
    return f"{n / 1024 / 1024:.1f} MB"


def fetch(url: str, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 1024:
        print(f"  跳过（已存在）{dest.name} {human(dest.stat().st_size)}")
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "meat-trace/1.0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        dest.write_bytes(data)
        print(f"  下载完成 {dest.name} {human(len(data))}")
        return True
    except Exception as exc:  # noqa: BLE001
        print(f"  失败 {dest.name}: {type(exc).__name__} {exc}")
        return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=str(Path(__file__).resolve().parent / ".models" / "bge-small-zh-v1.5"))
    args = ap.parse_args()
    out = Path(args.dir)
    out.mkdir(parents=True, exist_ok=True)
    print(f"目标目录：{out}")

    for name in FILES:
        fetch(BASE + name, out / name)

    missing = [n for n in REQUIRED if not (out / n).exists()]
    if missing:
        print(f"\n缺少必需文件：{missing}")
        print("→ 换网络重试，或手动从 ModelScope 页面下载后放到该目录")
        return 1
    print("\n模型就绪。下一步：python embed_eval.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
