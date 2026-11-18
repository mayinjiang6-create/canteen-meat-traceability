#!/usr/bin/env python3
"""并发扣减验证（BR-02 的唯一硬证据）。

场景：采购批次余量 60.000 kg，四个请求**同时**各申请投料 20.000 kg。
预期：恰好 3 个成功、1 个返回 40901，余量归零且批次自动结案 CLOSED。

为什么必须单独验一次：
    "先查余量再比较" 这种预校验在并发下**必然失效**——四个请求可能同时读到 60，
    各自都以为够用。真正兜底的只有
        UPDATE purchase_batch SET available_qty = available_qty - ?
         WHERE id = ? AND available_qty >= ?
    的受影响行数。这个脚本就是把这句话跑成数据。

用法：
    python scripts/concurrency_check.py                          # 自己起实例（18098）
    python scripts/concurrency_check.py --base-url http://127.0.0.1:8080

退出码：0 通过 / 1 失败。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "backend" / "target" / "meat-trace-0.1.0.jar"
DEFAULT_PORT = 18098
THREADS = 4
QTY_EACH = "20.000"
TOTAL_STOCK = "60.000"

PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    (PASSED if ok else FAILED).append(name)
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
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
        except Exception as exc:
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


def run(client: Client) -> None:
    status, resp = client.call("POST", "/api/auth/login",
                               {"username": "admin", "password": "Admin@123"})
    client.token = (resp.get("data") or {}).get("token")
    if not client.token:
        check("admin 登录成功", False, f"code={resp.get('code')}")
        return
    check("admin 登录成功", True)

    _, resp = client.call("POST", "/api/suppliers", {
        "name": f"并发验证供应商-{int(time.time())}",
        "creditCode": "91360100MA35CONC01",
        "licenseNo": "JX2026CONCUR",
        "contactName": "并发",
        "contactPhone": "13800000000",
    })
    supplier_id = ((resp.get("data") or {}).get("id"))
    check("供应商创建成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")

    _, resp = client.call("POST", "/api/purchases", {
        "supplierId": supplier_id,
        "meatType": "冷鲜猪肉",
        "origin": "江西省南昌市",
        "quarantineNo": f"JX-JY-2026-CONCUR{int(time.time()) % 100000}",
        "quantity": TOTAL_STOCK,
        "unit": "kg",
        "unitPrice": "25.50",
        "arriveTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "coldChainTemp": "3.10",
    }, request_id=str(uuid.uuid4()))
    purchase = resp.get("data") or {}
    batch_no = purchase.get("batchNo")
    check("采购批次创建成功（库存 60.000）", resp.get("code") == 0, f"{batch_no}")

    _, resp = client.call("POST", "/api/acceptances", {
        "batchId": purchase.get("id"),
        "acceptTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "并发验证",
        "temperature": "3.10",
        "appearanceOk": True,
        "certComplete": True,
        "sampleRetained": True,
        "result": "PASS",
    })
    check("验收合格", ((resp.get("data") or {}).get("batchStatus")) == "PASSED",
          str((resp.get("data") or {}).get("batchStatus")))

    # ---------------- 并发段：所有线程在同一栅栏后同时发车 ----------------
    barrier = threading.Barrier(THREADS)
    results: list[tuple[int, dict]] = []
    lock = threading.Lock()

    def worker():
        body = {
            "meatType": "冷鲜猪肉",
            "workshop": "并发验证后厨",
            "operator": f"并发线程-{threading.get_ident() % 1000}",
            "outputQty": QTY_EACH,
            "sources": [{"purchaseBatchNo": batch_no, "usedQty": QTY_EACH}],
        }
        barrier.wait()
        status, resp = client.call("POST", "/api/process-batches", body,
                                   request_id=str(uuid.uuid4()), timeout=60)
        with lock:
            results.append((status, resp))

    print(f"\n  同时发车：{THREADS} 个请求 × {QTY_EACH} kg，库存只有 {TOTAL_STOCK} kg")
    threads = [threading.Thread(target=worker) for _ in range(THREADS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    codes = [r.get("code") for _, r in results]
    ok_count = codes.count(0)
    rejected = codes.count(40901)
    print(f"  返回码：{sorted(codes)}")

    check("恰好 3 个请求成功", ok_count == 3, f"成功 {ok_count} 个")
    check("恰好 1 个请求被 40901 拒绝", rejected == 1, f"余量不足 {rejected} 个")
    check("没有出现其他错误码",
          set(codes) <= {0, 40901}, f"异常返回码 {sorted(set(codes) - {0, 40901})}")
    check("失败请求提示带剩余量",
          any("余量不足" in (r.get("message") or "") for _, r in results if r.get("code") == 40901),
          next((r.get("message") for _, r in results if r.get("code") == 40901), ""))

    # ---------------- 事后核对 ----------------
    _, resp = client.call("GET", f"/api/purchases/{purchase.get('id')}")
    after = resp.get("data") or {}
    check("余量精确归零（未出现负数）", after.get("availableQty") == "0.000",
          str(after.get("availableQty")))
    check("余量扣完自动结案 CLOSED", after.get("status") == "CLOSED", str(after.get("status")))

    successful = [r.get("data") or {} for _, r in results if r.get("code") == 0]
    inputs = sorted(p.get("inputQty") for p in successful)
    check("3 个成功请求各记 20.000 投入", inputs == ["20.000"] * 3, str(inputs))
    check("3 个加工批次都生成了 PR 追溯码",
          all((p.get("processNo") or "").startswith("PR") for p in successful),
          str([p.get("processNo") for p in successful]))


def main() -> int:
    parser = argparse.ArgumentParser(description="并发扣减验证")
    parser.add_argument("--base-url", help="复用已在运行的实例")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
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
        log_path = ROOT / "backend" / "target" / "concurrency-check.log"
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
            print("应用启动超时，看看 backend/target/concurrency-check.log")
            return 1
        print("=" * 68)
        run(client)
    finally:
        if proc:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
        if log_handle:
            log_handle.close()

    print("\n" + "=" * 68)
    print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项，耗时 {time.time() - t0:.1f}s")
    if FAILED:
        print("失败项：")
        for name in FAILED:
            print("   -", name)
        return 1
    print("全部通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
