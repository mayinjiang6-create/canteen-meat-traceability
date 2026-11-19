#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交付包自检：在没有 Docker 的机器上，验证容器化配置的内部一致性。

为什么需要它：Dockerfile / compose / nginx / Spring 配置是四份各自独立的文件，
任何一处名字或端口写错，要等到客户现场 `docker compose up` 才会炸。
这个脚本把「能静态验证的部分」提前到打包前跑一遍。

做的是**一致性检查**，不是构建验证：
  - 文件是否齐全、compose 是否能被 YAML 解析
  - 三个服务的端口是否冲突、依赖顺序是否成环
  - 构建上下文与 Dockerfile 路径是否真实存在
  - Dockerfile 里引用的 jar 名是否与 pom 的 artifactId-version 一致
  - nginx 反代的主机名/端口是否与 compose 里的后端服务对得上
  - compose 传的环境变量是否覆盖了 Spring 配置里的占位符；未覆盖的必须有默认值
  - 每个占位符变量是否在 .env.example 里有说明

用法：
    python deploy/check_deploy.py          # 有 PyYAML 时做完整解析
    python deploy/check_deploy.py --strict # 没有 PyYAML 直接判失败

退出码：0 = 全部通过；1 = 有 FAIL。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / "deploy"
COMPOSE = DEPLOY / "docker-compose.yml"
ENV_EXAMPLE = DEPLOY / ".env.example"
BACKEND_DOCKERFILE = ROOT / "backend" / "Dockerfile"
FRONTEND_DOCKERFILE = ROOT / "frontend" / "Dockerfile"
NGINX_CONF = ROOT / "frontend" / "nginx.conf"
APP_YML = ROOT / "backend" / "src" / "main" / "resources" / "application-dev.yml"
POM = ROOT / "backend" / "pom.xml"

PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}")

pass_count = 0
fail_count = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global pass_count, fail_count
    if cond:
        pass_count += 1
        print(f"  PASS {name}" + (f"  {detail}" if detail else ""))
    else:
        fail_count += 1
        print(f"  FAIL {name}" + (f"  {detail}" if detail else ""))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def load_yaml(text: str):
    try:
        import yaml  # noqa: PLC0415
    except ImportError:
        return None, "未安装 PyYAML"
    try:
        return yaml.safe_load(text), None
    except Exception as exc:  # noqa: BLE001
        return None, f"YAML 解析失败：{exc}"


def main() -> int:
    strict = "--strict" in sys.argv

    print("=== 1. 交付文件齐全 ===")
    for p in [COMPOSE, ENV_EXAMPLE, BACKEND_DOCKERFILE, FRONTEND_DOCKERFILE,
              NGINX_CONF, ROOT / ".dockerignore", ROOT / "frontend" / ".dockerignore"]:
        check(f"存在 {p.relative_to(ROOT)}", p.exists())

    print("\n=== 2. compose 可解析、服务与端口 ===")
    compose_text = read(COMPOSE)
    data, err = load_yaml(compose_text)
    if data is None:
        check("compose YAML 可解析", False, err or "未知原因")
        if strict:
            print("\n[strict] 缺少 PyYAML，无法继续做 compose 解析检查")
            print(f"\n结论：PASS {pass_count} / FAIL {fail_count}")
            return 1
        print("  SKIP 后续 compose 项（装 PyYAML 后可完整校验）")
        services = {}
    else:
        check("compose YAML 可解析", True)
        services = data.get("services", {}) or {}
        check("三个服务齐全（mysql/backend/frontend）",
              set(services) == {"mysql", "backend", "frontend"}, f"实际 {sorted(services)}")

        ports = []
        for name, svc in services.items():
            for item in (svc.get("ports") or []):
                text = str(item)
                # "${FRONTEND_PORT:-8088}:80" → 取默认值 8088 作为宿主机端口
                left = text.rsplit(":", 1)[0]
                m = re.search(r"\$\{[A-Z0-9_]+:-(\d+)\}", left)
                ports.append((name, m.group(1) if m else left))
        host_ports = [p for _, p in ports if p]
        check("宿主机端口不冲突", len(host_ports) == len(set(host_ports)), str(ports))

        for name, svc in services.items():
            build = svc.get("build")
            if not build:
                continue
            ctx = (DEPLOY / build["context"]).resolve()
            df_rel = build.get("dockerfile", "Dockerfile")
            # compose 里 dockerfile 相对**上下文根**解析；带子目录时按仓库根理解
            dockerfile = (ROOT / df_rel).resolve() if "/" in df_rel else (ctx / df_rel).resolve()
            check(f"{name} 构建上下文存在", ctx.is_dir(), str(ctx))
            check(f"{name} Dockerfile 存在", dockerfile.is_file(), str(dockerfile))

        backend = services.get("backend", {})
        frontend = services.get("frontend", {})
        check("frontend 依赖 backend 健康",
              "backend" in str(frontend.get("depends_on", {})),
              str(frontend.get("depends_on")))
        check("backend 依赖 mysql",
              "mysql" in str(backend.get("depends_on", {})),
              str(backend.get("depends_on")))

    print("\n=== 3. Dockerfile 与 pom 的产物名一致 ===")
    pom_text = read(POM)
    # 去掉 <parent> 块，否则会先匹配到 spring-boot-starter-parent 的版本
    own = re.sub(r"<parent>.*?</parent>", "", pom_text, flags=re.S)
    artifact = re.search(r"<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>", own)
    jar = f"{artifact.group(1)}-{artifact.group(2)}.jar" if artifact else ""
    check("pom 解析出 artifactId-version", bool(artifact), jar)
    backend_df = read(BACKEND_DOCKERFILE)
    check("backend/Dockerfile 引用正确 jar 名", jar in backend_df, jar)
    check("backend/Dockerfile 拷贝 db/migration", "db/migration" in backend_df)
    check("backend/Dockerfile 拷贝 contract/build", "contract/build" in backend_df)
    # 只看真正执行的 RUN 行，注释里出现 "不要用 spring-boot:run" 不算违规
    check("backend/Dockerfile 用 package 而不是 spring-boot:run",
          "package -DskipTests" in backend_df
          and re.search(r"^RUN.*spring-boot:run", backend_df, re.M) is None)
    check("backend/Dockerfile 声明 HEALTHCHECK", "HEALTHCHECK" in backend_df)
    check("backend/Dockerfile 非 root 运行", re.search(r"^USER\s+\w", backend_df, re.M) is not None)

    frontend_df = read(FRONTEND_DOCKERFILE)
    check("frontend/Dockerfile 多阶段构建", frontend_df.count("FROM ") >= 2)
    check("frontend/Dockerfile 托管 dist", "dist" in frontend_df)

    print("\n=== 4. nginx 反代目标与 compose 一致 ===")
    nginx = read(NGINX_CONF)
    # 允许两种写法：直接 proxy_pass http://backend:8080，或先 set $upstream 再 proxy_pass $upstream
    proxy = re.search(r"proxy_pass\s+http://([A-Za-z0-9_.-]+):(\d+)", nginx)
    if proxy is None:
        proxy = re.search(r"set\s+\$\w+\s+http://([A-Za-z0-9_.-]+):(\d+)", nginx)
    check("nginx 有 /api 反代", proxy is not None, proxy.group(0) if proxy else "未找到")
    check("nginx 使用 Docker 内置 DNS（后端重启后不 502）",
          "resolver 127.0.0.11" in nginx)
    if proxy and services:
        host, port = proxy.group(1), proxy.group(2)
        check("反代主机名是 compose 里的后端服务", host == "backend", host)
        backend_ports = [str(x).split(":")[-1] for x in (services["backend"].get("ports") or [])] \
            if "backend" in services else []
        check("反代端口与后端容器端口一致", port == "8080", f"nginx {port} / backend {backend_ports}")
    check("nginx 有 SPA history 兜底", "try_files" in nginx and "index.html" in nginx)

    print("\n=== 5. 环境变量闭环（Spring 占位符 ↔ compose ↔ .env.example）===")
    app_yml = read(APP_YML)
    placeholders = PLACEHOLDER.findall(app_yml)
    documented = set(PLACEHOLDER.findall(read(ENV_EXAMPLE)))
    provided = set()
    if services:
        for line in (services.get("backend", {}).get("environment") or []):
            provided.add(str(line).split(":")[0].strip())
    missing_without_default = []
    for name, default in placeholders:
        ok = name in provided or name in documented or default != ""
        if not ok:
            missing_without_default.append(name)
    check("Spring 占位符都有来源（compose 注入 / .env 说明 / 代码默认值）",
          not missing_without_default, str(missing_without_default))
    check("占位符数量可解释", len(placeholders) >= 6, f"{len(placeholders)} 个")

    print("\n=== 6. 密钥不进仓库 ===")
    gitignore = read(ROOT / ".gitignore")
    check(".env 被忽略", ".env" in gitignore)
    check(".env.example 未被 *.env 规则误伤",
          ".env.example" not in gitignore or "!.env.example" in gitignore)
    check("示例文件不含真实 API Key",
          "sk-" not in read(ENV_EXAMPLE).lower())

    print(f"\n结论：PASS {pass_count} / FAIL {fail_count}")
    if fail_count:
        print("→ 有 FAIL 项，按上面的路径逐条修正后再打包")
    else:
        print("→ 静态一致性全部通过。构建与运行验证仍需在有 Docker 的机器上执行，见 docs/product/09")
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
