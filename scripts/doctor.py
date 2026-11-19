#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给技术小白的环境体检：一条命令告诉你哪些组件就绪、现在能跑哪条路线。

零第三方依赖（Python 标准库即可），只读不改动任何文件。

    python scripts/doctor.py

设计参考 GitHub 上常见的 doctor.sh / setup-check 思路：
先逐项检查工具与端口，再给出「成功长什么样」和下一步该敲什么命令。
"""
from __future__ import annotations

import shutil
import socket
import subprocess
import sys
from datetime import datetime
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
RESULTS: list[tuple[str, bool, str]] = []


def ok(name: str, detail: str = "") -> None:
    RESULTS.append((name, True, detail))
    print(f"  [OK]   {name}" + (f"  {detail}" if detail else ""))


def bad(name: str, detail: str) -> None:
    RESULTS.append((name, False, detail))
    print(f"  [MISS] {name}  {detail}")


def check_tool(name: str, candidates: list[str], version_args: list[str],
               why: str) -> None:
    exe = next((x for x in candidates if shutil.which(x)), None)
    if not exe:
        bad(name, why)
        return
    try:
        out = subprocess.run([exe] + version_args, capture_output=True,
                             text=True, timeout=10, encoding="utf-8",
                             errors="replace")
        first = (out.stdout or out.stderr).strip().splitlines()
        detail = first[0][:70] if first else "已安装"
        ok(name, detail)
    except Exception as exc:
        ok(name, f"已安装（版本读取失败：{type(exc).__name__}）")


def check_port(name: str, host: str, port: int, why: str) -> None:
    s = socket.socket()
    s.settimeout(1.5)
    try:
        s.connect((host, port))
        ok(name, f"{host}:{port}")
    except Exception:
        bad(name, why)
    finally:
        s.close()


def check_file(name: str, path: Path, why: str) -> None:
    if path.exists():
        ok(name, str(path.relative_to(ROOT)))
    else:
        bad(name, why)


def main() -> int:
    print("=" * 68)
    print(f"环境体检  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 68)

    print("\n【1】基础工具")
    check_tool("Python", ["python", "python3", "py"], ["-V"],
               "没装 Python → 装 Python 3.11+，安装时勾选 Add to PATH")
    check_tool("Java", ["java"], ["-version"],
               "没装 JDK 21 → 装 JDK 21 并配 JAVA_HOME")
    check_tool("Maven", ["mvn.cmd", "mvn"], ["-v"],
               "没装 Maven → 本仓库已带 D:/Maven，或自己装后加进 PATH")
    check_tool("Node", ["node"], ["-v"],
               "没装 Node → 装 Node 20+（前端要用到）")
    check_tool("npm", ["npm.cmd", "npm"], ["-v"],
               "没装 npm → 一般随 Node 一起装好")

    print("\n【2】本机服务（端口能不能连上）")
    check_port("MySQL :3306", "127.0.0.1", 3306,
               "MySQL 没启动 → 启动 MySQL 服务（Windows 服务里找 MySQL）")
    check_port("区块链 WeBASE :5002", "192.168.239.130", 5002,
               "虚拟机里的链/WeBASE 没开（不跑完整演示可以不管）")
    check_port("节点 RPC :8545", "192.168.239.130", 8545,
               "虚拟机 node0 的 web3 RPC 没开（不跑完整演示可以不管）")

    print("\n【3】构建产物")
    check_file("后端 jar", ROOT / "backend/target/meat-trace-0.1.0.jar",
               "没打包 → cd backend && mvn.cmd package -DskipTests")
    check_file("前端依赖 node_modules", ROOT / "frontend/node_modules",
               "没装依赖 → cd frontend && npm install")
    check_file("AI 子项目语料", ROOT / "trusted-rag/data/events.json",
               "语料缺失 → 从 GitHub 重新 clone 仓库")

    ok_keys = {name for name, good, _ in RESULTS if good}
    miss_keys = {name for name, good, _ in RESULTS if not good}

    print("\n" + "=" * 68)
    print("你现在能跑什么")
    print("=" * 68)

    # 路线 A：只要 Python + 语料
    route_a = "Python" in ok_keys and "AI 子项目语料" in ok_keys
    print(f"\n路线 A · AI 子项目（零依赖，5 分钟，推荐先跑）  "
          f"{'可跑' if route_a else '缺东西'}")
    if route_a:
        print("   cd trusted-rag")
        print("   python demo.py        # 看到「40 条全部通过」就成功")
        print("   python compare.py     # 看到 v1/v2 对比表就成功")
    else:
        print("   先补齐上面带 [MISS] 的基础工具 / 语料")

    # 路线 B：后端 + 前端，链可不开
    route_b = all(k in ok_keys for k in
                  ("Python", "Java", "Node", "npm", "MySQL :3306",
                   "后端 jar", "前端依赖 node_modules"))
    chain_ready = ("区块链 WeBASE :5002" in ok_keys
                   and "节点 RPC :8545" in ok_keys)
    print(f"\n路线 B · 后端 + 前端（链可不开，业务功能够用）  "
          f"{'可跑' if route_b else '缺东西'}")
    if route_b:
        if not chain_ready:
            print("   注意：虚拟机没开，存证会进入 UNREACHABLE，公开页显示黄色提示——")
            print("         这是设计好的降级行为，不是出错，业务数据照常可用。")
            print("         公开溯源页可能转圈几秒（等链查询超时），不是卡死。")
        print("   终端 1: cd backend && java -jar target/meat-trace-0.1.0.jar "
              "--spring.profiles.active=dev --server.port=8080")
        print("   终端 2: cd frontend && npm run dev")
        print("   浏览器: http://localhost:5173  账号 admin / Admin@123")
    else:
        print("   先补齐上面带 [MISS] 的工具 / MySQL / 构建产物")

    # 路线 C：完整
    route_c = route_b and chain_ready
    print(f"\n路线 C · 完整含链（真实上链与核验）  "
          f"{'可跑' if route_c else '需要先开虚拟机'}")
    if route_c:
        print("   python scripts/smoke_test.py   # 107 项断言全过即完整链路可用")
    else:
        print("   1) 用 VMware 打开 UbuntuServer 虚拟机（挂起过就用 gui 模式恢复）")
        print("   2) 等 1 分钟后重跑 python scripts/doctor.py")
        print("   3) 两个链端口都 [OK] 后，再跑 python scripts/smoke_test.py")

    print("\n" + "=" * 68)
    print(f"小结：{len(ok_keys)} 项就绪，{len(miss_keys)} 项未就绪。")
    if miss_keys:
        print("未就绪：" + "、".join(sorted(miss_keys)))
    print("详细步骤见 docs/product/08-小白运行指南.md")
    print("=" * 68)
    return 0


if __name__ == "__main__":
    sys.exit(main())
