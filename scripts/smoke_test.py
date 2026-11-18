#!/usr/bin/env python3
"""全链路回归：登录 → 建供应商 → 建采购批次 → 自动上链 → 验收 → 自动上链 → 公开溯源核验。

用法：
    python scripts/smoke_test.py                 # 自己起一个实例（18099 端口），跑完自动关
    python scripts/smoke_test.py --base-url http://127.0.0.1:8080   # 复用已在跑的实例
    python scripts/smoke_test.py --skip-chain    # 链没开时跳过需要上链的断言

退出码：0 全部通过 / 1 有失败。

这是答辩前的"一键自检"：它跑通，说明业务链路、摘要规范、上链、幂等、
四类核验状态这几件事同时都是好的。任何一处坏了都会红。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "backend" / "target" / "meat-trace-0.1.0.jar"
DEFAULT_PORT = 18099

PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    if ok:
        PASSED.append(name)
        print(f"  [PASS] {name}" + (f"  {detail}" if detail else ""))
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}  {detail}")
    return ok


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def call(self, method: str, path: str, body=None, request_id=None, timeout=30):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if request_id:
            headers["X-Request-Id"] = request_id
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            resp = urllib.request.urlopen(req, timeout=timeout)
            return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            try:
                return exc.code, json.loads(raw)
            except json.JSONDecodeError:
                return exc.code, {"raw": raw[:300]}
        except Exception as exc:  # 连接不上
            return 0, {"error": str(exc)}


def wait_ready(client: Client, timeout: int = 90) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, _ = client.call("POST", "/api/auth/login",
                                {"username": "__probe__", "password": "__probe__"}, timeout=3)
        if status in (200, 401):
            return True
        time.sleep(1)
    return False


def wait_confirmed(client: Client, event_id: str, timeout: int = 40) -> str | None:
    """轮询存证任务直到不再 PENDING/SUBMITTED。返回最终状态。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        _, resp = client.call("GET", f"/api/evidence/tasks/{event_id}")
        status = ((resp.get("data") or {}).get("status"))
        if status:
            last = status
            if status not in ("PENDING", "SUBMITTED"):
                return status
        time.sleep(2)
    return last


def run(client: Client, skip_chain: bool) -> None:
    print("\n[1] 登录")
    status, resp = client.call("POST", "/api/auth/login",
                               {"username": "admin", "password": "Admin@123"})
    check("admin 登录成功", resp.get("code") == 0, f"code={resp.get('code')}")
    client.token = (resp.get("data") or {}).get("token")
    if not client.token:
        print("  !! 拿不到 token，后续全部跳过")
        return
    check("返回体带 traceId", bool(resp.get("traceId")), resp.get("traceId", ""))
    check("返回体带 timestamp", bool(resp.get("timestamp")), resp.get("timestamp", ""))

    print("\n[2] 错误口令应被拒")
    status, resp = client.call("POST", "/api/auth/login",
                               {"username": "admin", "password": "wrong"})
    check("错误口令返回 401/40101", status == 401 and resp.get("code") == 40101,
          f"HTTP {status} code={resp.get('code')}")

    print("\n[3] 新建供应商")
    status, resp = client.call("POST", "/api/suppliers", {
        "name": f"自检供应商-{int(time.time())}",
        "creditCode": "91360100MA35SMOKE1",
        "licenseNo": "JX2026SMOKE",
        "contactName": "自检",
        "contactPhone": "13800000000",
        "address": "自检地址",
    })
    supplier = resp.get("data") or {}
    check("供应创建成功", resp.get("code") == 0, f"code={resp.get('code')}")
    check("自动生成供应商编码", bool(supplier.get("supplierCode")), supplier.get("supplierCode", ""))
    check("供应商事件 eventId 为 64 位",
          len(supplier.get("eventId") or "") == 64, f"长度={len(supplier.get('eventId') or '')}")
    supplier_id = supplier.get("id")

    if skip_chain:
        print("\n[4] --skip-chain：跳过上链相关断言")
        return

    print("\n[4] 新建采购批次（带 X-Request-Id）")
    request_id = str(uuid.uuid4())
    body = {
        "supplierId": supplier_id,
        "meatType": "冷鲜猪肉",
        "origin": "江西省南昌市",
        "slaughterNo": "SL20261118SMOKE",
        "quarantineNo": f"JX-JY-2026-SMOKE{int(time.time()) % 10000}",
        "quantity": "88.000",
        "unit": "kg",
        "unitPrice": "25.50",
        "arriveTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "coldChainTemp": "3.10",
    }
    status, resp = client.call("POST", "/api/purchases", body, request_id=request_id)
    purchase = resp.get("data") or {}
    check("采购批次创建成功", resp.get("code") == 0, f"code={resp.get('code')}")
    check("批次号格式 PB+日期+序号",
          (purchase.get("batchNo") or "").startswith("PB"), purchase.get("batchNo", ""))
    check("返回存证状态 PENDING",
          (purchase.get("evidence") or {}).get("status") == "PENDING")
    batch_no = purchase.get("batchNo")
    purchase_event = (purchase.get("evidence") or {}).get("eventId")

    print("\n[5] 同一 X-Request-Id 重放（BR-10）")
    status, resp = client.call("POST", "/api/purchases", body, request_id=request_id)
    replay = resp.get("data") or {}
    check("重放返回 replayed=true", replay.get("replayed") is True,
          f"replayed={replay.get('replayed')}")
    check("重放返回同一批次号", replay.get("batchNo") == batch_no,
          f"{replay.get('batchNo')} vs {batch_no}")

    print("\n[6] 等采购事件上链")
    state = wait_confirmed(client, purchase_event)
    check("采购事件已确认", state == "CONFIRMED", f"最终状态={state}")

    print("\n[7] 验收登记")
    status, resp = client.call("POST", "/api/acceptances", {
        "batchId": purchase.get("id"),
        "acceptTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "自检员",
        "temperature": "3.10",
        "appearanceOk": True,
        "certComplete": True,
        "sampleRetained": True,
        "result": "PASS",
        "rejectReason": None,
    })
    acceptance = resp.get("data") or {}
    check("验收登记成功", resp.get("code") == 0, f"code={resp.get('code')}")
    check("验收后批次状态 PASSED", acceptance.get("batchStatus") == "PASSED",
          acceptance.get("batchStatus", ""))
    accept_event = (acceptance.get("evidence") or {}).get("eventId")

    print("\n[8] 等验收事件上链")
    state = wait_confirmed(client, accept_event)
    check("验收事件已确认", state == "CONFIRMED", f"最终状态={state}")

    print("\n[9] 重复验收应被拒（40902）")
    status, resp = client.call("POST", "/api/acceptances", {
        "batchId": purchase.get("id"),
        "acceptTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "自检员",
        "result": "PASS",
    })
    check("重复验收返回 40902", resp.get("code") == 40902, f"code={resp.get('code')}")

    print("\n[10] 公开溯源（免登录）")
    anon = Client(client.base)
    status, resp = anon.call("GET", f"/api/public/trace/{batch_no}")
    trace = resp.get("data") or {}
    check("公开接口免登录可访问", resp.get("code") == 0, f"code={resp.get('code')}")
    timeline = trace.get("timeline") or []
    check("时间线含 2 条事件", len(timeline) == 2, f"实际 {len(timeline)} 条")
    check("全部 MATCHED",
          all(t.get("verifyStatus") == "MATCHED" for t in timeline),
          str([t.get("verifyStatus") for t in timeline]))
    summary = trace.get("verifySummary") or {}
    check("overall=PASS", summary.get("overall") == "PASS", str(summary))
    check("检验结论与核验状态是两个独立字段",
          "conclusion" in trace and "verifySummary" in trace)
    check("检验结论为 PASS",
          (trace.get("conclusion") or {}).get("inspectionResult") == "PASS")
    check("时间线带真实 txHash",
          all(t.get("txHash") for t in timeline),
          str([t.get("txHash") for t in timeline]))

    print("\n[11] 仅核验接口")
    status, resp = anon.call("GET", f"/api/public/trace/{batch_no}/verify")
    verify = resp.get("data") or {}
    check("verify 返回 overall=PASS", verify.get("overall") == "PASS", str(verify.get("overall")))

    print("\n[12] 演示批次的链上真实记录")
    status, resp = anon.call("GET", "/api/public/trace/PB20261118001/verify")
    demo = resp.get("data") or {}
    check("PB20261118001 核验为 PASS/MATCHED",
          demo.get("overall") == "PASS"
          and ((demo.get("counts") or {}).get("matched") or 0) >= 1,
          str(demo.get("counts")))

    print("\n[13] 不存在的批次返回 40401")
    status, resp = anon.call("GET", "/api/public/trace/PB00000000000")
    check("不存在批次返回 40401", resp.get("code") == 40401, f"code={resp.get('code')}")


def main() -> int:
    parser = argparse.ArgumentParser(description="全链路回归自检")
    parser.add_argument("--base-url", help="复用已在运行的实例，例如 http://127.0.0.1:8080")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--skip-chain", action="store_true", help="链未就绪时跳过上链断言")
    args = parser.parse_args()

    proc = None
    log_handle = None
    if args.base_url:
        base = args.base_url
        print(f"复用实例：{base}")
    else:
        if not JAR.exists():
            print(f"找不到 {JAR}，请先 mvn package -DskipTests")
            return 1
        base = f"http://127.0.0.1:{args.port}"
        log_path = ROOT / "backend" / "target" / "smoke-test.log"
        log_handle = open(log_path, "w", encoding="utf-8")
        print(f"启动实例：{base}（日志 {log_path}）")
        proc = subprocess.Popen(
            ["java", "-jar", str(JAR), "--spring.profiles.active=dev",
             f"--server.port={args.port}"],
            cwd=str(ROOT / "backend"), stdout=log_handle, stderr=subprocess.STDOUT)

    client = Client(base)
    t0 = time.time()
    try:
        if proc and not wait_ready(client):
            print("应用启动超时，看看 backend/target/smoke-test.log")
            return 1
        print("=" * 68)
        run(client, args.skip_chain)
    finally:
        if proc:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
        if log_handle:
            log_handle.close()

    cost = time.time() - t0
    print("\n" + "=" * 68)
    print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项，耗时 {cost:.1f}s")
    if FAILED:
        print("失败项：")
        for name in FAILED:
            print("   -", name)
        return 1
    print("全部通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
