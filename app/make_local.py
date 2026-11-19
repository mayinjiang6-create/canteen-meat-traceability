#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「本机已接入版」app.local.html：双击即可直接用 LLM，无需手动填 Key。

    DEEPSEEK_API_KEY=xxx python make_local.py

安全边界：
  - 产物 app.local.html 含密钥，已在 .gitignore 中，**绝不提交到公开仓库**
  - 公开版 app.html 不含任何密钥
  - 本脚本自身不含密钥，只从环境变量读取
"""
from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "app.html"
OUT = ROOT / "app.local.html"

key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
if not key:
    raise SystemExit("请先设置环境变量 DEEPSEEK_API_KEY，例如：\n"
                     "  DEEPSEEK_API_KEY=sk-xxx python make_local.py")
if not SRC.exists():
    raise SystemExit("找不到 app.html，请先运行 python build_app.py")

cfg = {"base": "https://api.deepseek.com", "key": key,
       "model": "deepseek-chat", "enabled": True}
inject = """<script>
/* 本机已接入版：密钥只在这台机器的这个本地文件里，未提交到公开仓库。
   若已在「LLM 接入设置」里自行配置过，则不覆盖。 */
(function(){try{
  if(window.APP_CORE && !window.APP_CORE.getLLMConfig()){
    window.APP_CORE.setLLMConfig(%s);
  }
}catch(e){}})();
</script>
</body>""" % json.dumps(cfg, ensure_ascii=False)

html = SRC.read_text(encoding="utf-8")
OUT.write_text(html.replace("</body>", inject, 1), encoding="utf-8")
print(f"已生成 {OUT}  ({OUT.stat().st_size / 1024:.1f} KB)")
print("  双击这个文件即可直接使用 LLM 增强模式")
print("  注意：此文件含密钥，已加入 .gitignore，不要提交或随意外发")
