#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 E 验收测试执行器：逐条执行 TECH-07 未回填的用例 + 20 次性能采样。

与 smoke_test.py 的分工：
  - smoke_test.py 是「一键回归」，判断系统整体是否还活着；
  - 本脚本是「按用例取证」，每个用例留下请求/响应原文、链上证据或页面截图，
    输出结构化结果供 TECH-07 / TECH-08 回填，**不编造任何数字**。

四个场景依次在同一台机器上复用 8080 端口（前端 Vite 反代固定指向 8080）：
  S1 正常配置        → TC-01~09 / 26~29 / 31~33 / 28 + 性能采样
  S2 关闭存证工作器  → TC-34（UNCONFIRMED）
  S3 链写入与读取都指向死端口 → TC-30 / TC-35（UNREACHABLE）
  S4 恢复正常        → 验证 UNREACHABLE 任务能被自动重试（不卡死队列）

用法（需要 pymysql，本机装在隔离 venv 里）：
    <python> scripts/acceptance_test.py
    <python> scripts/acceptance_test.py --skip-screenshots   # 不启动浏览器，只跑接口
环境变量：DB_USER / DB_PASSWORD（默认 meat / Meat@2026，与 application-dev.yml 一致）
退出码：0 无失败 / 1 存在失败用例。
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timedelta
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

from smoke_test import Client, JAR, wait_ready, wait_confirmed  # noqa: E402
import chain_tools as ct  # noqa: E402

try:
    import pymysql
except ImportError:  # 允许只跑接口部分，但涉及故障注入的用例会被标记阻塞
    pymysql = None

DB_CONF = dict(
    host=os.environ.get("DB_HOST", "localhost"),
    user=os.environ.get("DB_USER", "meat"),
    password=os.environ.get("DB_PASSWORD", "Meat@2026"),
    database="meat_trace",
    charset="utf8mb4",
    autocommit=True,
)

EVIDENCE_DIR = ROOT / "screenshots" / "04-接口与日志"
SHOT_PUBLIC = ROOT / "screenshots" / "02-公开溯源页"
SHOT_ADMIN = ROOT / "screenshots" / "01-后台管理"
REPORT_PATH = EVIDENCE_DIR / "acceptance-report.json"
RUN_ID = datetime.now().strftime("%Y%m%d-%H%M%S")
BASE = "http://127.0.0.1:8080"
FRONTEND = "http://127.0.0.1:5173"
DEAD_CHAIN = "http://127.0.0.1:1"

RESULTS: list[dict] = []
EVIDENCE: dict[str, list[str]] = {}
PERF: dict = {}
OBSERVATIONS: list[str] = []
SKIP_SHOTS = False


# --------------------------------------------------------------------------- 基础设施
def db_select(sql: str, params=None):
    if pymysql is None:
        raise RuntimeError("缺少 pymysql，无法执行数据库故障注入")
    conn = pymysql.connect(**DB_CONF)
    try:
        with conn.cursor() as cur:
            cur.execute(sql, params or ())
            return cur.fetchall()
    finally:
        conn.close()


def db_exec(sql: str, params=None) -> int:
    if pymysql is None:
        raise RuntimeError("缺少 pymysql，无法执行数据库故障注入")
    conn = pymysql.connect(**DB_CONF)
    try:
        with conn.cursor() as cur:
            return cur.execute(sql, params or ())
    finally:
        conn.close()


def log(msg: str) -> None:
    print(msg, flush=True)


def record(tc: str, name: str, ok: bool | None, detail: str = "",
           req=None, resp=None, evidence_file: str | None = None) -> bool:
    """ok=None 表示阻塞（未执行），True/False 为通过/不通过。"""
    state = "阻塞" if ok is None else ("通过" if ok else "不通过")
    RESULTS.append({"tc": tc, "name": name, "result": state, "detail": detail,
                    "at": datetime.now().isoformat(timespec="seconds")})
    icon = {"通过": "[PASS]", "不通过": "[FAIL]", "阻塞": "[BLOCK]"}[state]
    log(f"  {icon} {tc} {name}  {detail}")
    if req is not None or resp is not None:
        EVIDENCE.setdefault(tc, []).append(
            f"时间: {datetime.now().isoformat(timespec='seconds')}\n"
            f"请求: {json.dumps(req, ensure_ascii=False, default=str)}\n"
            f"响应: {json.dumps(resp, ensure_ascii=False, default=str)[:4000]}\n")
    return ok is True


def flush_evidence() -> None:
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    for tc, blocks in EVIDENCE.items():
        (EVIDENCE_DIR / f"{tc}.txt").write_text(
            "\n" + ("-" * 70 + "\n").join(blocks), encoding="utf-8")
    REPORT_PATH.write_text(json.dumps(
        {"runId": RUN_ID, "results": RESULTS, "performance": PERF,
         "observations": OBSERVATIONS,
         "summary": {s: sum(1 for r in RESULTS if r["result"] == s)
                     for s in ("通过", "不通过", "阻塞")}},
        ensure_ascii=False, indent=2), encoding="utf-8")


def start_instance(extra: list[str], label: str):
    log_path = ROOT / "backend" / "target" / f"acceptance-{label.split()[0]}.log"
    handle = open(log_path, "w", encoding="utf-8")
    cmd = ["java", "-jar", str(JAR), "--spring.profiles.active=dev",
           "--server.port=8080"] + extra
    log(f"\n=== {label} ===")
    log("  启动: " + " ".join(cmd[3:]))
    proc = subprocess.Popen(cmd, cwd=str(ROOT / "backend"),
                            stdout=handle, stderr=subprocess.STDOUT)
    client = Client(BASE)
    if not wait_ready(client, timeout=90):
        proc.terminate()
        handle.close()
        raise RuntimeError(f"{label} 启动超时，看 {log_path}")
    # 等 worker / 拦截器完全就绪
    time.sleep(1.5)
    return proc, handle, client


def stop_instance(proc, handle) -> None:
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
    handle.close()
    time.sleep(1.0)


def ensure_frontend():
    """启动 Vite dev server（已跑则复用），失败时返回 False，截图部分自动跳过。"""
    def alive() -> bool:
        try:
            return urllib.request.urlopen(FRONTEND + "/", timeout=2).status == 200
        except Exception:
            return False

    if alive():
        log("前端 dev server 已在跑，复用 5173")
        return None, True
    log_handle = open(ROOT / "backend" / "target" / "acceptance-vite.log",
                      "w", encoding="utf-8")
    proc = subprocess.Popen(
        ["npm.cmd", "run", "dev", "--", "--host", "127.0.0.1", "--port", "5173"],
        cwd=str(ROOT / "frontend"), stdout=log_handle, stderr=subprocess.STDOUT)
    for _ in range(40):
        if alive():
            return (proc, log_handle), True
        time.sleep(0.5)
    log("  !! 前端 dev server 未就绪，跳过页面截图（接口测试照常）")
    return (proc, log_handle), False


def capture(path: str, out: Path, wait_ms: int = 3500) -> bool:
    if SKIP_SHOTS:
        return False
    out.parent.mkdir(parents=True, exist_ok=True)
    script = ROOT / "frontend" / "scripts" / "capture-evidence.mjs"
    cmd = ["node", str(script), "--path", path, "--out", str(out),
           "--base", FRONTEND, "--api", BASE, "--wait", str(wait_ms)]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90,
                       encoding="utf-8", errors="replace")
    ok = r.returncode == 0 and out.exists()
    log(f"  [截图] {'OK' if ok else '失败'} {out.name}  {(r.stdout or r.stderr).strip()[:200]}")
    return ok


# --------------------------------------------------------------------------- 链交互
def chain_total_events() -> int:
    abi_str, addr = ct._abi_and_addr()
    code, enc = ct._post_raw("/trans/encodeFunction", {
        "groupId": ct.GROUP_ID, "contractAbi": json.loads(abi_str),
        "funcName": "totalEvents", "funcParam": [],
    })
    enc = enc.strip().strip('"')
    resp = ct._rpc("eth_call", [{"to": addr, "data": "0x" + enc}, "latest"])
    return int(resp["result"], 16)


def chain_query(event_id: str) -> dict:
    abi_str, addr = ct._abi_and_addr()
    code, enc = ct._post_raw("/trans/encodeFunction", {
        "groupId": ct.GROUP_ID, "contractAbi": json.loads(abi_str),
        "funcName": "query", "funcParam": [event_id],
    })
    enc = enc.strip().strip('"')
    resp = ct._rpc("eth_call", [{"to": addr, "data": "0x" + enc}, "latest"])
    return ct.decode_query_result(resp["result"])


def chain_append(event_id: str, batch_id: str, event_type: str,
                 biz_time: int, digest: str) -> tuple[int, dict]:
    abi_str, addr = ct._abi_and_addr()
    resp = ct._post("/trans/handleWithSign", {
        "groupId": ct.GROUP_ID, "contractName": ct.CONTRACT,
        "contractPath": "", "contractAddress": addr,
        "contractAbi": json.loads(abi_str), "funcName": "append",
        "funcParam": [event_id, batch_id, event_type, str(biz_time), digest],
        "signUserId": ct.SIGN_USER,
    })
    out = (resp.get("output") or "") if isinstance(resp, dict) else ""
    hexout = out[2:] if out.startswith("0x") else out
    code = int(hexout[-2:], 16) if len(hexout) >= 2 else -1
    return code, resp


# --------------------------------------------------------------------------- 业务辅助
def new_supplier(client: Client, name: str) -> dict:
    _, resp = client.call("POST", "/api/suppliers", {
        "name": name, "creditCode": "91360100MA" + uuid.uuid4().hex[:8].upper(),
        "licenseNo": "JX" + uuid.uuid4().hex[:10].upper(),
        "contactName": "验收测试", "contactPhone": "13800000000",
        "address": "江西省南昌市（测试数据）",
    })
    if resp.get("code") != 0:
        log(f"  !! 供应商创建失败 code={resp.get('code')} msg={resp.get('message')}")
    return resp.get("data") or {}


def new_purchase(client: Client, supplier_id, qty="20.000"):
    body = {
        "supplierId": supplier_id, "meatType": "冷鲜猪肉",
        "origin": "江西省南昌市", "slaughterNo": "SL-TEST-" + uuid.uuid4().hex[:6],
        "quarantineNo": "JX-JY-2026-" + uuid.uuid4().hex[:8].upper(),
        "quantity": qty, "unit": "kg", "unitPrice": "25.50",
        "arriveTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "coldChainTemp": "3.20",
    }
    status, resp = client.call("POST", "/api/purchases", body,
                               request_id=str(uuid.uuid4()))
    return status, resp, body


def accept(client: Client, batch_id, result="PASS", reason=None):
    body = {
        "batchId": batch_id,
        "acceptTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "验收测试员", "temperature": "3.20",
        "appearanceOk": result == "PASS", "certComplete": result == "PASS",
        "sampleRetained": True, "result": result, "rejectReason": reason,
    }
    return client.call("POST", "/api/acceptances", body)


def public_trace(batch_no: str) -> dict:
    anon = Client(BASE)
    _, resp = anon.call("GET", f"/api/public/trace/{batch_no}")
    return resp.get("data") or {}


def public_verify(batch_no: str) -> dict:
    anon = Client(BASE)
    _, resp = anon.call("GET", f"/api/public/trace/{batch_no}/verify")
    return resp.get("data") or {}


def task_detail(client: Client, event_id: str) -> dict:
    # 存证任务详情是 ADMIN 接口，必须带 token，不能用匿名 Client
    _, resp = client.call("GET", f"/api/evidence/tasks/{event_id}")
    return resp.get("data") or {}


def wait_queue_drained(timeout: int = 180) -> tuple[bool, dict]:
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        rows = db_select("SELECT status, COUNT(*) FROM evidence_task GROUP BY status")
        last = {r[0]: int(r[1]) for r in rows}
        pending = sum(v for k, v in last.items()
                      if k in ("PENDING", "SUBMITTED", "UNREACHABLE"))
        if pending == 0:
            return True, last
        time.sleep(2)
    return False, last


# --------------------------------------------------------------------------- S1 正常场景
def run_normal(client: Client) -> None:
    log("\n--- TC-01 / TC-02 供应商 ---")
    _, resp = client.call("POST", "/api/auth/login",
                          {"username": "admin", "password": "Admin@123"})
    client.token = (resp.get("data") or {}).get("token")

    sup = new_supplier(client, "验收测试-正常供应商-" + RUN_ID)
    record("TC-01", "供应商备案成功", bool(sup.get("supplierCode")),
           f"supplierCode={sup.get('supplierCode')} eventId={str(sup.get('eventId'))[:16]}…",
           {"name": "验收测试-正常供应商"}, sup)
    ev_sup = sup.get("eventId")
    if ev_sup:
        state = wait_confirmed(client, ev_sup, timeout=40)
        record("TC-01", "SUPPLIER_REG 事件已上链", state == "CONFIRMED", f"状态={state}")

    _, resp = client.call("POST", "/api/suppliers", {
        "name": "验收测试-黑名单供应商-" + RUN_ID,
        "creditCode": "91360100MA" + uuid.uuid4().hex[:8].upper(),
        "licenseNo": "JX" + uuid.uuid4().hex[:10].upper(),
    })
    black = resp.get("data") or {}
    _, resp_put = client.call("PUT", f"/api/suppliers/{black.get('id')}",
                              {"status": "BLACKLISTED"})
    before = db_select("SELECT COUNT(*) FROM purchase_batch")[0][0]
    _, resp2, _ = new_purchase(client, black.get("id"))
    after = db_select("SELECT COUNT(*) FROM purchase_batch")[0][0]
    record("TC-02", "黑名单供应商禁止建采购单",
           resp2.get("code") == 40902 and before == after,
           f"code={resp2.get('code')} msg={resp2.get('message')} "
           f"采购批次总数 {before}→{after}",
           {"supplierId": black.get("id")}, resp2)

    log("\n--- TC-03 ~ TC-05 采购批次 ---")
    status, resp, body = new_purchase(client, sup.get("id"), "120.000")
    pb = resp.get("data") or {}
    pb_no, pb_id = pb.get("batchNo"), pb.get("id")
    _, detail = client.call("GET", f"/api/purchases/{pb_id}")
    pv = detail.get("data") or {}
    record("TC-03", "创建采购批次成功",
           resp.get("code") == 0 and (pb_no or "").startswith("PB")
           and (pv.get("status") == "PENDING_ACCEPT")
           and str(pv.get("availableQty")) == "120.000"
           and (pb.get("evidence") or {}).get("status") == "PENDING",
           f"batchNo={pb_no} status={pv.get('status')} qty={pv.get('availableQty')} "
           f"evidence={(pb.get('evidence') or {}).get('status')}", body, resp)
    pb_event = (pb.get("evidence") or {}).get("eventId")

    bad_body = dict(body)
    bad_body.pop("quarantineNo")
    _, resp = client.call("POST", "/api/purchases", bad_body,
                          request_id=str(uuid.uuid4()))
    record("TC-04", "检疫证号缺失", resp.get("code") == 40001,
           f"code={resp.get('code')} msg={resp.get('message')}", bad_body, resp)

    rid = str(uuid.uuid4())
    body2 = dict(body)
    body2["quarantineNo"] = "JX-JY-2026-" + uuid.uuid4().hex[:8].upper()
    client.call("POST", "/api/purchases", body2, request_id=rid)
    _, resp = client.call("POST", "/api/purchases", body2, request_id=rid)
    # 检疫证号每次随机生成，按它计数就能证明「库里只有 1 条」而不依赖批次号
    rows = db_select("SELECT COUNT(*) FROM purchase_batch WHERE quarantine_no=%s",
                     (body2["quarantineNo"],))
    record("TC-05", "重复提交幂等",
           resp.get("code") == 0 and (resp.get("data") or {}).get("replayed") is True
           and rows[0][0] == 1,
           f"replayed={(resp.get('data') or {}).get('replayed')} "
           f"batchNo={(resp.get('data') or {}).get('batchNo')} "
           f"同检疫证号的采购批次={rows[0][0]} 条",
           {"X-Request-Id": rid}, resp)

    log("\n--- TC-06 ~ TC-09 入库验收 ---")
    _, resp = client.call("POST", "/api/acceptances", {
        "batchId": pb_id, "acceptTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "验收测试员", "temperature": "3.50", "appearanceOk": True,
        "certComplete": True, "sampleRetained": True, "result": "PASS",
    })
    acc = resp.get("data") or {}
    record("TC-06", "验收合格",
           resp.get("code") == 0 and acc.get("batchStatus") == "PASSED",
           f"batchStatus={acc.get('batchStatus')} eventId={str((acc.get('evidence') or {}).get('eventId'))[:16]}…",
           {"batchId": pb_id, "result": "PASS"}, resp)

    _, resp_fail_batch, _ = new_purchase(client, sup.get("id"), "30.000")
    fb = resp_fail_batch.get("data") or {}
    _, resp = accept(client, fb.get("id"), "FAIL", "到货温度 9.80℃ 且色泽异常（测试数据）")
    rejected = resp.get("data") or {}
    _, resp_pr = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉", "outputQty": "1.000",
        "sources": [{"purchaseBatchNo": fb.get("batchNo"), "usedQty": "1.000"}],
    }, request_id=str(uuid.uuid4()))
    record("TC-07", "验收不合格并禁止后续加工",
           rejected.get("batchStatus") == "REJECTED" and resp_pr.get("code") == 40902,
           f"batchStatus={rejected.get('batchStatus')} 加工返回={resp_pr.get('code')}",
           {"batchId": fb.get("id"), "result": "FAIL"}, resp)

    _, resp_no_reason, _ = new_purchase(client, sup.get("id"), "10.000")
    nr = resp_no_reason.get("data") or {}
    _, resp = accept(client, nr.get("id"), "FAIL", None)
    record("TC-08", "不合格未填原因", resp.get("code") == 40001,
           f"code={resp.get('code')} msg={resp.get('message')}",
           {"batchId": nr.get("id"), "result": "FAIL"}, resp)

    _, resp = accept(client, pb_id, "PASS", None)
    record("TC-09", "重复验收", resp.get("code") == 40902,
           f"code={resp.get('code')} msg={resp.get('message')}",
           {"batchId": pb_id}, resp)

    log("\n--- TC-26 / TC-32 存证确认与公开溯源 ---")
    state = wait_confirmed(client, pb_event, timeout=60)
    t = task_detail(client, pb_event)
    rec = db_select("SELECT tx_status, tx_hash, block_number FROM tx_receipt WHERE event_id=%s",
                    (pb_event,))
    record("TC-26", "任务从待提交到已确认",
           state == "CONFIRMED" and bool(rec) and rec[0][1] and rec[0][0] == "0x1",
           f"任务={state} txHash={str(rec[0][1])[:20] if rec else None}… "
           f"block={rec[0][2] if rec else None} tx_status={rec[0][0] if rec else None}"
           f"（节点回执口径 0x1）",
           {"eventId": pb_event}, t)

    accept_event = (acc.get("evidence") or {}).get("eventId")
    wait_confirmed(client, accept_event, timeout=60)
    trace = public_trace(pb_no)
    timeline = trace.get("timeline") or []
    summary = trace.get("verifySummary") or {}
    record("TC-32", "记录一致 MATCHED",
           bool(timeline) and all(x.get("verifyStatus") == "MATCHED" for x in timeline)
           and summary.get("overall") == "PASS",
           f"事件 {len(timeline)} 条 / matched={summary.get('matched')} / overall={summary.get('overall')}"
           f" / 检验结论与核验状态分栏={'conclusion' in trace and 'verifySummary' in trace}",
           {"GET": f"/api/public/trace/{pb_no}"},
           {"verifySummary": summary, "conclusion": trace.get("conclusion")})
    capture(f"/trace/{pb_no}", SHOT_PUBLIC / "TC-32.png", 4000)

    log("\n--- TC-27 / TC-29 / TC-31 链上幂等、冲突、查链自愈 ---")
    if pymysql is None:
        for tc in ("TC-27", "TC-29", "TC-31"):
            record(tc, "需数据库与链交互", None, "缺少 pymysql，未执行")
    else:
        # totalEvents 是全局计数器：测量前必须让存证队列排空，否则 worker
        # 并发写入别的事件会让计数自然 +1，把「幂等」误判成失败。
        drained_chain, chain_stat = wait_queue_drained(120)
        OBSERVATIONS.append(
            f"TC-27 测量前存证队列：{chain_stat}，drained={drained_chain}"
            "（排除并发上链对全局 totalEvents 的干扰）")
        onchain = chain_query(pb_event)
        n0 = chain_total_events()
        code, raw = chain_append(pb_event, onchain["batchId"], onchain["eventType"],
                                 onchain["bizTime"], onchain["dataDigest"])
        n1 = chain_total_events()
        after = chain_query(pb_event)
        receipts = db_select("SELECT COUNT(*) FROM tx_receipt WHERE event_id=%s", (pb_event,))[0][0]
        record("TC-27", "同内容重复上链幂等",
               code == 1 and n0 == n1 and after["dataDigest"] == onchain["dataDigest"]
               and receipts == 1,
               f"返回码={code}（1=幂等） totalEvents {n0}→{n1} "
               f"链上摘要保持={after['dataDigest'][:16]}… 回执数={receipts}",
               {"eventId": pb_event, "digest": onchain["dataDigest"]}, raw)

        # TC-29：用独立测试批次，避免污染正式演示记录
        _, resp_c, _ = new_purchase(client, sup.get("id"), "12.000")
        cb = resp_c.get("data") or {}
        conflict_event = (cb.get("evidence") or {}).get("eventId")
        wait_confirmed(client, conflict_event, timeout=60)
        oc = chain_query(conflict_event)
        d1 = oc["dataDigest"]
        d2 = "f" * 64
        code2, raw2 = chain_append(conflict_event, oc["batchId"], oc["eventType"],
                                   oc["bizTime"], d2)
        still = chain_query(conflict_event)
        # 模拟「本地摘要被改成 D2 后点重试」：retry 先查链，发现摘要不同 → CONFLICT
        db_exec("UPDATE event_snapshot SET data_digest=%s WHERE event_id=%s",
                (d2, conflict_event))
        db_exec("UPDATE evidence_task SET status='UNREACHABLE', retry_count=1, "
                "next_retry_at=%s WHERE event_id=%s",
                ((datetime.now() + timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S"),
                 conflict_event))
        _, retry_resp = client.call("POST", f"/api/evidence/tasks/{conflict_event}/retry")
        task_state = (retry_resp.get("data") or {}).get("status")
        record("TC-29", "摘要冲突且链上保留原记录",
               code2 == 2 and still["dataDigest"] == d1 and task_state == "CONFLICT",
               f"返回码={code2}（2=冲突） 链上摘要仍为 {still['dataDigest'][:16]}… "
               f"任务状态={task_state}",
               {"eventId": conflict_event, "tamperedDigest": d2}, raw2)
        # 复原测试数据
        db_exec("UPDATE event_snapshot SET data_digest=%s WHERE event_id=%s",
                (d1, conflict_event))
        db_exec("UPDATE evidence_task SET status='CONFIRMED', retry_count=0, "
                "next_retry_at=NULL, last_error=NULL WHERE event_id=%s", (conflict_event,))

        n_before = chain_total_events()
        db_exec("UPDATE evidence_task SET status='SUBMITTED', retry_count=1, "
                "next_retry_at=%s WHERE event_id=%s",
                ((datetime.now() + timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S"),
                 accept_event))
        _, retry_resp = client.call("POST", f"/api/evidence/tasks/{accept_event}/retry")
        n_after = chain_total_events()
        record("TC-31", "超时后重试先查链",
               (retry_resp.get("data") or {}).get("status") == "CONFIRMED"
               and n_before == n_after,
               f"模拟状态=SUBMITTED → 重试后={(retry_resp.get('data') or {}).get('status')} "
               f"totalEvents {n_before}→{n_after}（链上未新增）",
               {"eventId": accept_event, "模拟": "提交成功但状态未回写"}, retry_resp)

    log("\n--- TC-33 记录不一致 MISMATCHED（独立测试数据，测完复原）---")
    _, resp_m, _ = new_purchase(client, sup.get("id"), "15.000")
    mb = resp_m.get("data") or {}
    mb_no, mb_id = mb.get("batchNo"), mb.get("id")
    mb_event = (mb.get("evidence") or {}).get("eventId")
    wait_confirmed(client, mb_event, timeout=60)
    accept(client, mb_id, "PASS", None)
    row = db_select("SELECT canonical_text, payload_json FROM event_snapshot "
                    "WHERE event_id=%s", (mb_event,))
    orig_text = row[0][0] if row else None
    orig_payload = row[0][1] if row else None
    # MySQL JSON 列经 pymysql 可能返回字符串或已反序列化的对象，统一成合法 JSON 文本
    if isinstance(orig_payload, (dict, list)):
        orig_payload = json.dumps(orig_payload, ensure_ascii=False)
    if orig_text:
        db_exec("UPDATE event_snapshot SET canonical_text=%s, "
                "payload_json=JSON_SET(payload_json, '$.tampered', true) "
                "WHERE event_id=%s", (orig_text + "\ntampered", mb_event))
        bad = public_verify(mb_no)
        bad_trace = public_trace(mb_no)
        record("TC-33", "记录不一致 MISMATCHED",
               (bad.get("counts") or {}).get("mismatched", 0) >= 1
               and bad.get("overall") == "FAIL",
               f"counts={bad.get('counts')} overall={bad.get('overall')} "
               f"页面顶部警示条={'verifySummary' in bad_trace}",
               {"篡改": "独立测试批次的 canonical_text / payload_json"}, bad)
        capture(f"/trace/{mb_no}", SHOT_PUBLIC / "TC-33.png", 4000)
        db_exec("UPDATE event_snapshot SET canonical_text=%s, payload_json=%s "
                "WHERE event_id=%s", (orig_text, orig_payload, mb_event))
        healed = public_verify(mb_no)
        OBSERVATIONS.append(
            f"TC-33 测试数据已复原，复原后 overall={healed.get('overall')}，"
            "正式演示记录未被修改。")

    log("\n--- TC-28 常规操作稳定性（20 次新建 + 查详情）---")
    ok28, samples28 = 0, []
    for i in range(20):
        t0 = time.perf_counter()
        st, rp, _ = new_purchase(client, sup.get("id"), "1.000")
        p = rp.get("data") or {}
        _, dt = client.call("GET", f"/api/purchases/{p.get('id')}")
        samples28.append(round((time.perf_counter() - t0) * 1000, 1))
        if st == 200 and rp.get("code") == 0 and dt.get("code") == 0:
            ok28 += 1
    record("TC-28", "连续 20 次新建 + 查询稳定",
           ok28 == 20 and max(samples28) < 10000,
           f"成功 {ok28}/20，最慢 {max(samples28)} ms，无 5xx、无超时",
           {"循环": 20}, {"samples_ms": samples28[:5] + ["…"] + samples28[-3:]})

    log("\n--- 性能采样：公开溯源 / 批次详情 各 20 次 ---")
    warm = 3
    trace_url = "/api/public/trace/PR20261118001"
    detail_url = f"/api/purchases/{pb_id}"
    for _ in range(warm):
        client.call("GET", trace_url)
        client.call("GET", detail_url)

    def sample(url: str, n: int = 20):
        vals = []
        for _ in range(n):
            t0 = time.perf_counter()
            st, rp = client.call("GET", url)
            vals.append(round((time.perf_counter() - t0) * 1000, 1))
            if st != 200 or rp.get("code") != 0:
                OBSERVATIONS.append(f"性能采样 {url} 出现 HTTP {st} / code={rp.get('code')}")
        ordered = sorted(vals)
        return {"samples": vals,
                "mean": round(statistics.mean(vals), 1),
                "max": max(vals), "min": min(vals),
                "p95": ordered[18],
                "median": round(statistics.median(vals), 1)}

    anon = Client(BASE)
    perf_trace = []
    for _ in range(warm):
        anon.call("GET", trace_url)
    for _ in range(20):
        t0 = time.perf_counter()
        st, rp = anon.call("GET", trace_url)
        perf_trace.append(round((time.perf_counter() - t0) * 1000, 1))
    ordered = sorted(perf_trace)
    PERF["public_trace"] = {
        "url": trace_url, "samples": perf_trace,
        "mean": round(statistics.mean(perf_trace), 1), "max": max(perf_trace),
        "min": min(perf_trace), "p95": ordered[18],
        "median": round(statistics.median(perf_trace), 1),
        "chainQueries": len((rp.get("data") or {}).get("timeline") or []),
        "note": "含链上 eth_call 核验，事件数见 chainQueries",
    }
    PERF["purchase_detail"] = sample(detail_url)
    PERF["purchase_detail"]["note"] = "纯数据库查询，不含链上交互"
    log(f"  公开溯源: 均值 {PERF['public_trace']['mean']} ms / "
        f"P95 {PERF['public_trace']['p95']} ms / 最大 {PERF['public_trace']['max']} ms")
    log(f"  批次详情: 均值 {PERF['purchase_detail']['mean']} ms / "
        f"P95 {PERF['purchase_detail']['p95']} ms / 最大 {PERF['purchase_detail']['max']} ms")

    log("\n--- 等存证队列排空，避免污染后续故障场景 ---")
    drained, stat = wait_queue_drained(180)
    OBSERVATIONS.append(f"S1 结束时存证队列状态：{stat}；drained={drained}")
    log(f"  drained={drained} {stat}")


# --------------------------------------------------------------------------- S2 / S3 / S4
def run_unconfirmed(client: Client) -> None:
    _, resp = client.call("POST", "/api/auth/login",
                          {"username": "admin", "password": "Admin@123"})
    client.token = (resp.get("data") or {}).get("token")
    sup = new_supplier(client, "验收测试-UNCONFIRMED-" + RUN_ID)
    _, resp, _ = new_purchase(client, sup.get("id"), "5.000")
    pb_no = (resp.get("data") or {}).get("batchNo")
    v = public_verify(pb_no)
    t = public_trace(pb_no)
    record("TC-34", "尚未确认 UNCONFIRMED",
           (v.get("counts") or {}).get("unconfirmed", 0) >= 1
           and v.get("overall") == "PARTIAL" and t.get("conclusion") is not None,
           f"counts={v.get('counts')} overall={v.get('overall')} "
           f"业务数据照常可读={bool(t.get('timeline'))}",
           {"batchNo": pb_no, "前置": "chain.evidence.worker-enabled=false"}, v)
    capture(f"/trace/{pb_no}", SHOT_PUBLIC / "TC-34.png", 4000)
    OBSERVATIONS.append(f"TC-34 测试批次 {pb_no} 的任务停留在 PENDING（链上无记录），"
                        "S4 恢复实例会自动补上链。")


def run_unreachable(client: Client) -> None:
    _, resp = client.call("POST", "/api/auth/login",
                          {"username": "admin", "password": "Admin@123"})
    client.token = (resp.get("data") or {}).get("token")
    sup = new_supplier(client, "验收测试-UNREACHABLE-" + RUN_ID)
    _, resp, _ = new_purchase(client, sup.get("id"), "6.000")
    pb_no = (resp.get("data") or {}).get("batchNo")
    ev = ((resp.get("data") or {}).get("evidence") or {}).get("eventId")

    state, last_error = None, None
    deadline = time.time() + 45
    while time.time() < deadline:
        rows = db_select("SELECT status, last_error FROM evidence_task WHERE event_id=%s", (ev,))
        if rows:
            state, last_error = rows[0][0], rows[0][1]
            if state == "UNREACHABLE":
                break
        time.sleep(1.5)
    record("TC-30", "链不可达时业务落库、任务置 UNREACHABLE",
           resp.get("code") == 0 and state == "UNREACHABLE" and bool(last_error),
           f"业务 code={resp.get('code')} batchNo={pb_no} 任务={state} "
           f"last_error={str(last_error)[:80]}",
           {"前置": "webase/rpc 指向 127.0.0.1:1", "batchNo": pb_no},
           {"status": state, "lastError": last_error})

    t0 = time.perf_counter()
    demo = public_verify("PB20261118001")
    cost = round((time.perf_counter() - t0) * 1000, 1)
    trace = public_trace("PB20261118001")
    statuses = [x.get("verifyStatus") for x in (trace.get("timeline") or [])]
    record("TC-35", "链上不可达 UNREACHABLE（不得显示为通过）",
           (demo.get("counts") or {}).get("unreachable", 0) >= 1
           and demo.get("overall") != "PASS"
           and "MATCHED" not in statuses and bool(trace.get("timeline")),
           f"counts={demo.get('counts')} overall={demo.get('overall')} "
           f"时间线状态={statuses} 业务数据可读={bool(trace.get('timeline'))} "
           f"熔断后耗时={cost} ms",
           {"GET": "/api/public/trace/PB20261118001/verify"}, demo)
    capture("/trace/PB20261118001", SHOT_PUBLIC / "TC-35.png", 4500)
    PERF["unreachable_verify_ms"] = cost


def run_recovery(client: Client) -> None:
    before = db_select("SELECT status, COUNT(*) FROM evidence_task "
                       "WHERE status IN ('PENDING','SUBMITTED','UNREACHABLE') GROUP BY status")
    drained, stat = wait_queue_drained(180)
    OBSERVATIONS.append(
        f"S4 恢复正常配置后自动重试：重试前={dict((k, int(v)) for k, v in before)}，"
        f"drained={drained}，最终={stat}。这条验证 UNREACHABLE 状态改动后任务不会被卡死。")
    record("TC-30", "故障恢复后任务自动重试成功", drained,
           f"重试前 {dict((k, int(v)) for k, v in before)} → 最终 {stat}",
           {"场景": "恢复正常链配置"}, {"drained": drained})
    log(f"  自动重试 drained={drained} {stat}")


# --------------------------------------------------------------------------- main
def main() -> int:
    global SKIP_SHOTS
    parser = argparse.ArgumentParser(description="阶段 E 验收测试执行器")
    parser.add_argument("--skip-screenshots", action="store_true")
    args = parser.parse_args()
    SKIP_SHOTS = args.skip_screenshots

    if not JAR.exists():
        log(f"找不到 {JAR}，先执行 mvn package -DskipTests")
        return 1
    for d in (EVIDENCE_DIR, SHOT_PUBLIC, SHOT_ADMIN):
        d.mkdir(parents=True, exist_ok=True)

    vite = None
    vite_ok = False
    if not SKIP_SHOTS:
        try:
            vite, vite_ok = ensure_frontend()
        except Exception as exc:
            OBSERVATIONS.append(f"前端 dev server 启动异常，跳过页面截图：{exc}")
            log(f"  !! 前端启动异常：{exc}")
        if not vite_ok:
            SKIP_SHOTS = True

    t0 = time.time()
    try:
        scenarios = [
            ("S1 正常配置", [], run_normal),
            ("S2 关闭存证工作器", ["--chain.evidence.worker-enabled=false"], run_unconfirmed),
            ("S3 链不可达", [
                "--chain.webase.base-url=" + DEAD_CHAIN,
                "--chain.rpc.url=" + DEAD_CHAIN,
                "--chain.evidence.poll-interval-ms=1500",
            ], run_unreachable),
            ("S4 恢复正常（验证自动重试）", [], run_recovery),
        ]
        for label, extra, fn in scenarios:
            proc, handle, client = start_instance(extra, label)
            try:
                fn(client)
            except Exception as exc:  # 单个场景出错不阻断其余场景，但如实记录
                OBSERVATIONS.append(f"{label} 场景异常：{type(exc).__name__}: {exc}")
                log(f"  !! {label} 场景异常：{exc}")
                RESULTS.append({"tc": "SCENARIO", "name": label, "result": "不通过",
                                "detail": f"{type(exc).__name__}: {exc}"})
            finally:
                stop_instance(proc, handle)
    finally:
        if vite:
            p, h = vite
            p.terminate()
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
            h.close()
        flush_evidence()

    cost = time.time() - t0
    s = {k: sum(1 for r in RESULTS if r["result"] == k)
         for k in ("通过", "不通过", "阻塞")}
    log("\n" + "=" * 70)
    log(f"验收测试完成：通过 {s['通过']} / 不通过 {s['不通过']} / 阻塞 {s['阻塞']}，"
        f"耗时 {cost:.1f}s")
    log(f"证据目录：{EVIDENCE_DIR}")
    log(f"结构化报告：{REPORT_PATH}")
    return 1 if s["不通过"] else 0


if __name__ == "__main__":
    sys.exit(main())
