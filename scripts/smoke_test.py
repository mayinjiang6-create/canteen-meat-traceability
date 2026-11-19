#!/usr/bin/env python3
"""全链路回归：登录 → 建供应商 → 建采购批次 → 自动上链 → 验收 → 自动上链 → 公开溯源核验
→ 加工投料（单来源 / 多来源 / 四类拒绝）→ 上架 → PR 追溯码回溯上游。

用法：
    python scripts/smoke_test.py                 # 自己起一个实例（18099 端口），跑完自动关
    python scripts/smoke_test.py --base-url http://127.0.0.1:8080   # 复用已在跑的实例
    python scripts/smoke_test.py --skip-chain    # 链没开时跳过需要上链的断言

退出码：0 全部通过 / 1 有失败。

这是上线前的"一键自检"：它跑通，说明业务链路、摘要规范、上链、幂等、
多来源投料的数量约束、四类核验状态这几件事同时都是好的。任何一处坏了都会红。
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


def create_purchase(client: Client, supplier_id, quantity: str, tag: str) -> dict:
    """建一个采购批次（状态 PENDING_ACCEPT），返回 data。"""
    _, resp = client.call("POST", "/api/purchases", {
        "supplierId": supplier_id,
        "meatType": "冷鲜猪肉",
        "origin": "江西省南昌市",
        "slaughterNo": f"SL-{tag}",
        "quarantineNo": f"JX-JY-2026-{tag}-{int(time.time()) % 100000}",
        "quantity": quantity,
        "unit": "kg",
        "unitPrice": "25.50",
        "arriveTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "coldChainTemp": "3.10",
    }, request_id=str(uuid.uuid4()))
    return resp.get("data") or {}


def accept_pass(client: Client, batch_id) -> dict:
    """验收合格，返回 data。"""
    _, resp = client.call("POST", "/api/acceptances", {
        "batchId": batch_id,
        "acceptTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "inspector": "自检员",
        "temperature": "3.10",
        "appearanceOk": True,
        "certComplete": True,
        "sampleRetained": True,
        "result": "PASS",
    })
    return resp.get("data") or {}


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

    # ============================================================== 阶段 B：加工投料
    print("\n[14] 加工投料：单来源（TC-12）")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "processTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "workshop": "自检后厨",
        "operator": "自检员",
        "retainSampleNo": "LY-SMOKE-001",
        "outputQty": "28.000",
        "sources": [{"purchaseBatchNo": batch_no, "usedQty": "30.000"}],
    }, request_id=str(uuid.uuid4()))
    single = resp.get("data") or {}
    check("单来源投料成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("生成 PR 追溯码", (single.get("processNo") or "").startswith("PR"),
          single.get("processNo", ""))
    check("加工批次初始状态 CREATED", single.get("status") == "CREATED", single.get("status", ""))
    check("投入量由来源汇总得出", single.get("inputQty") == "30.000", single.get("inputQty", ""))
    single_sources = single.get("sources") or [{}]
    check("来源扣减后余量 58.000", single_sources[0].get("remainQty") == "58.000",
          str(single.get("sources")))
    single_id = single.get("id")

    print("\n[15] 上架（幂等）")
    status, resp = client.call("POST", f"/api/process-batches/{single_id}/onsale")
    check("上架后状态 ON_SALE", (resp.get("data") or {}).get("status") == "ON_SALE",
          str((resp.get("data") or {}).get("status")))
    status, resp = client.call("POST", f"/api/process-batches/{single_id}/onsale")
    check("重复上架按幂等处理",
          resp.get("code") == 0 and (resp.get("data") or {}).get("status") == "ON_SALE",
          f"code={resp.get('code')}")

    print("\n[16] 多来源投料（TC-13，关键用例）")
    second = create_purchase(client, supplier_id, "50.000", "SMOKE2")
    check("第二批次创建成功", bool(second.get("batchNo")), str(second.get("batchNo")))
    accept_pass(client, second.get("id"))
    second_no = second.get("batchNo")

    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "processTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "workshop": "自检后厨",
        "operator": "自检员",
        "retainSampleNo": "LY-SMOKE-002",
        "outputQty": "40.000",
        "sources": [
            {"purchaseBatchNo": batch_no, "usedQty": "20.000"},
            {"purchaseBatchNo": second_no, "usedQty": "20.000"},
        ],
    }, request_id=str(uuid.uuid4()))
    multi = resp.get("data") or {}
    check("多来源投料成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("来源关系落 2 条", len(multi.get("sources") or []) == 2, str(len(multi.get("sources") or [])))
    check("投入合计 40.000", multi.get("inputQty") == "40.000", multi.get("inputQty", ""))
    remain = {s["purchaseBatchNo"]: s["remainQty"] for s in (multi.get("sources") or [])}
    check("两个批次余量分别扣到 38.000 / 30.000",
          remain.get(batch_no) == "38.000" and remain.get(second_no) == "30.000", str(remain))
    multi_no = multi.get("processNo")
    multi_event = (multi.get("evidence") or {}).get("eventId")

    status, resp = client.call("GET", "/api/process-batches?page=1&size=100")
    total_before = (resp.get("data") or {}).get("total")

    print("\n[17] 来源批次未验收（TC-14）")
    pending = create_purchase(client, supplier_id, "40.000", "SMOKE3")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "outputQty": "10.000",
        "sources": [{"purchaseBatchNo": pending.get("batchNo"), "usedQty": "10.000"}],
    })
    check("未验收来源返回 40902", resp.get("code") == 40902,
          f"code={resp.get('code')} {resp.get('message', '')}")

    print("\n[18] 投入超可用量（TC-15）")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "outputQty": "100.000",
        "sources": [{"purchaseBatchNo": batch_no, "usedQty": "999.000"}],
    })
    check("超量投入返回 40901", resp.get("code") == 40901,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("提示带当前剩余量", "38.000" in (resp.get("message") or ""), resp.get("message", ""))

    print("\n[19] 重复引用同一来源（TC-16）")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "outputQty": "10.000",
        "sources": [
            {"purchaseBatchNo": batch_no, "usedQty": "5.000"},
            {"purchaseBatchNo": batch_no, "usedQty": "5.000"},
        ],
    })
    check("重复引用返回 40904", resp.get("code") == 40904,
          f"code={resp.get('code')} {resp.get('message', '')}")

    print("\n[20] 产出大于投入（TC-17）")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "outputQty": "999.000",
        "sources": [{"purchaseBatchNo": second_no, "usedQty": "10.000"}],
    })
    check("产出超投入返回 40001", resp.get("code") == 40001,
          f"code={resp.get('code')} {resp.get('message', '')}")

    status, resp = client.call("GET", "/api/process-batches?page=1&size=100")
    total_after = (resp.get("data") or {}).get("total")
    check("四次失败请求全部回滚，无脏数据", total_before == total_after,
          f"{total_before} → {total_after}")

    print("\n[21] 等加工事件上链 + PR 公开溯源回溯")
    state = wait_confirmed(client, multi_event)
    check("加工事件已确认（链上真实交易）", state == "CONFIRMED", f"最终状态={state}")
    status, resp = anon.call("GET", f"/api/public/trace/{multi_no}")
    pr = resp.get("data") or {}
    check("追溯码免登录可查", resp.get("code") == 0, f"code={resp.get('code')}")
    check("识别为加工批次", pr.get("batchType") == "PROCESS", str(pr.get("batchType")))
    pr_summary = pr.get("summary") or {}
    check("公开页来源数 2、投入 40.000",
          pr_summary.get("sourceCount") == 2 and pr_summary.get("totalInputQty") == "40.000",
          str(pr_summary))
    timeline = pr.get("timeline") or []
    types = [t.get("eventType") for t in timeline]
    check("时间线沿来源回溯到上游采购批次",
          types.count("PURCHASE_CREATE") >= 2 and types.count("ACCEPT_RESULT") >= 2, str(types))
    check("时间线含加工投料事件", "PROCESS_CREATE" in types, str(types))
    check("上游批次号出现在时间线里",
          {batch_no, second_no}.issubset({t.get("batchNo") for t in timeline}),
          str(sorted({t.get("batchNo") for t in timeline})))
    check("PR 溯源全部 MATCHED",
          bool(timeline) and all(t.get("verifyStatus") == "MATCHED" for t in timeline),
          str([t.get("verifyStatus") for t in timeline]))
    check("PR 核验 overall=PASS",
          (pr.get("verifySummary") or {}).get("overall") == "PASS",
          str(pr.get("verifySummary")))
    # ============================================================== 阶段 B 第 2 周：销售 / 召回 / 冷链 / 异常
    print("\n[22] 销售登记（TC-18）")
    single_no = single.get("processNo")
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": single_no,
        "channel": "一食堂 3 号窗口",
        "quantity": "10.000",
        "unitPrice": "38.00",
        "saleTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "operator": "自检员",
    }, request_id=str(uuid.uuid4()))
    sale1 = resp.get("data") or {}
    check("销售登记成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("销售单号 SL+日期", (sale1.get("saleNo") or "").startswith("SL"), sale1.get("saleNo", ""))
    check("金额由服务端算好 380.00", sale1.get("amount") == "380.00", str(sale1.get("amount")))
    check("销售后余量 18.000", sale1.get("availableQty") == "18.000", str(sale1.get("availableQty")))
    check("批次仍在售 ON_SALE", sale1.get("processStatus") == "ON_SALE",
          str(sale1.get("processStatus")))
    sale_event = (sale1.get("evidence") or {}).get("eventId")

    print("\n[23] 同一 X-Request-Id 重放销售（BR-10）")
    replay_rid = str(uuid.uuid4())
    replay_body = {
        "processBatchNo": single_no,
        "channel": "一食堂 3 号窗口",
        "quantity": "1.000",
        "unitPrice": "38.00",
    }
    status, resp = client.call("POST", "/api/sales", replay_body, request_id=replay_rid)
    first_no = (resp.get("data") or {}).get("saleNo")
    status, resp = client.call("POST", "/api/sales", replay_body, request_id=replay_rid)
    replay = resp.get("data") or {}
    check("重放返回 replayed=true", replay.get("replayed") is True, str(replay.get("replayed")))
    check("重放返回同一销售单号", replay.get("saleNo") == first_no,
          f"{replay.get('saleNo')} vs {first_no}")
    status, resp = client.call("GET", f"/api/sales?processBatchNo={single_no}&page=1&size=100")
    sale_total = (resp.get("data") or {}).get("total")
    check("重放没有多出一条销售记录", sale_total == 2, f"实际 {sale_total} 条")

    print("\n[24] 未上架批次销售（TC-19）")
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": multi_no, "quantity": "1.000", "channel": "一食堂 1 号窗口",
    })
    check("未上架返回 40902", resp.get("code") == 40902,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("提示「尚未上架」", "尚未上架" in (resp.get("message") or ""), resp.get("message", ""))

    print("\n[25] 超量销售（TC-20）")
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": single_no, "quantity": "999.000", "channel": "一食堂 3 号窗口",
    })
    check("超量返回 40901", resp.get("code") == 40901,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("提示带当前余量 17.000", "17.000" in (resp.get("message") or ""),
          resp.get("message", ""))

    print("\n[26] 售罄状态流转（TC-21）")
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": single_no, "quantity": "17.000", "channel": "一食堂 3 号窗口",
        "unitPrice": "38.00",
    })
    sold = resp.get("data") or {}
    check("恰好卖完余量成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("余量归零 0.000", sold.get("availableQty") == "0.000", str(sold.get("availableQty")))
    check("批次状态 ON_SALE → SOLD_OUT", sold.get("processStatus") == "SOLD_OUT",
          str(sold.get("processStatus")))
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": single_no, "quantity": "1.000",
    })
    check("售罄后再销售被拒 40902", resp.get("code") == 40902,
          f"code={resp.get('code')} {resp.get('message', '')}")

    print("\n[27] 批次召回（TC-24 / TC-25）")
    status, resp = client.call("POST", "/api/recalls", {
        "processBatchNo": single_no,
        "reason": "同批次抽检发现兽药残留超标（自检模拟）",
        "scope": "自检当日全部销售记录",
        "operator": "自检员",
        "recallTime": time.strftime("%Y-%m-%d %H:%M:%S"),
    }, request_id=str(uuid.uuid4()))
    recall = resp.get("data") or {}
    check("召回成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("召回单号 RC+日期", (recall.get("recallNo") or "").startswith("RC"),
          recall.get("recallNo", ""))
    check("波及销售 3 笔", recall.get("affectedSales") == 3, str(recall.get("affectedSales")))
    check("需追回 28.000 kg", recall.get("recalledQty") == "28.000", str(recall.get("recalledQty")))
    check("批次状态 → RECALLED", recall.get("processStatus") == "RECALLED",
          str(recall.get("processStatus")))
    recall_event = (recall.get("evidence") or {}).get("eventId")

    status, resp = client.call("GET", f"/api/sales?processBatchNo={single_no}&page=1&size=100")
    sale_status = [(r.get("status"), r.get("saleNo")) for r in ((resp.get("data") or {}).get("records") or [])]
    check("全部销售记录置 RECALLED",
          bool(sale_status) and all(s == "RECALLED" for s, _ in sale_status), str(sale_status))
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": single_no, "quantity": "1.000",
    })
    check("TC-25 召回后销售被拒 40902", resp.get("code") == 40902,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("提示「已召回」", "已召回" in (resp.get("message") or ""), resp.get("message", ""))

    print("\n[28] 冷链登记与阈值判定（TC-10 / TC-11）")
    status, resp = client.call("POST", "/api/cold-chain-logs", {
        "batchNo": batch_no,
        "segment": "WAREHOUSE_TO_CANTEEN",
        "transportNo": "TR-SMOKE-001",
        "temperature": "3.00",
        "recordedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "recorder": "自检司机",
    }, request_id=str(uuid.uuid4()))
    cold_ok = resp.get("data") or {}
    check("冷链登记成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("阈值按品类取 4.00℃", cold_ok.get("threshold") == "4.00", str(cold_ok.get("threshold")))
    check("3.00℃ 未超限", cold_ok.get("exceeded") is False, str(cold_ok.get("exceeded")))
    check("未超限不生成异常工单", cold_ok.get("abnormalNo") is None, str(cold_ok.get("abnormalNo")))

    status, resp = client.call("POST", "/api/cold-chain-logs", {
        "batchNo": batch_no,
        "segment": "ORIGIN_TO_WAREHOUSE",
        "transportNo": "TR-SMOKE-002",
        "temperature": "6.50",
        "recordedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "recorder": "自检司机",
    }, request_id=str(uuid.uuid4()))
    cold_bad = resp.get("data") or {}
    check("超限登记成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("6.50℃ 判定超限", cold_bad.get("exceeded") is True, str(cold_bad.get("exceeded")))
    check("超限自动开异常工单（AB...）",
          (cold_bad.get("abnormalNo") or "").startswith("AB"), str(cold_bad.get("abnormalNo")))
    cold_event = (cold_bad.get("evidence") or {}).get("eventId")

    status, resp = client.call("GET", f"/api/abnormal-events?batchNo={batch_no}&status=OPEN")
    pending_abnormal = (resp.get("data") or {}).get("total")
    check("异常列表能看到冷链自动工单", (pending_abnormal or 0) >= 1, str(pending_abnormal))

    print("\n[29] 异常登记 → 冻结 → 处置恢复（TC-22 / TC-23）")
    status, resp = client.call("POST", "/api/process-batches", {
        "meatType": "冷鲜猪肉",
        "processTime": time.strftime("%Y-%m-%d %H:%M:%S"),
        "workshop": "自检后厨",
        "operator": "自检员",
        "outputQty": "4.000",
        "sources": [{"purchaseBatchNo": second_no, "usedQty": "5.000"}],
    }, request_id=str(uuid.uuid4()))
    ab_process = resp.get("data") or {}
    check("为异常用例新建加工批次", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    ab_process_id = ab_process.get("id")
    ab_process_no = ab_process.get("processNo")
    client.call("POST", f"/api/process-batches/{ab_process_id}/onsale")

    status, resp = client.call("POST", "/api/abnormal-events", {
        "processBatchNo": ab_process_no,
        "abnormalType": "SENSORY_ABNORMAL",
        "level": "HIGH",
        "description": "抽检发现色泽异常，暂停销售待复检",
        "handler": "自检员",
        "occurredAt": time.strftime("%Y-%m-%d %H:%M:%S"),
    }, request_id=str(uuid.uuid4()))
    abnormal = resp.get("data") or {}
    check("异常登记成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("异常编号 AB+日期", (abnormal.get("abnormalNo") or "").startswith("AB"),
          abnormal.get("abnormalNo", ""))
    check("异常状态 OPEN", abnormal.get("status") == "OPEN", str(abnormal.get("status")))
    abnormal_id = abnormal.get("id")

    status, resp = client.call("GET", f"/api/process-batches/{ab_process_id}")
    check("TC-22 批次被冻结为 ABNORMAL",
          (resp.get("data") or {}).get("status") == "ABNORMAL",
          str((resp.get("data") or {}).get("status")))
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": ab_process_no, "quantity": "1.000",
    })
    check("冻结期间销售被拒 40902", resp.get("code") == 40902,
          f"code={resp.get('code')} {resp.get('message', '')}")

    status, resp = client.call("POST", f"/api/abnormal-events/{abnormal_id}/dispose", {
        "action": "暂停销售、封存留样并送第三方复检",
        "handler": "自检员",
        "conclusion": "复检合格，恢复销售",
        "resumeSale": True,
        "disposeTime": time.strftime("%Y-%m-%d %H:%M:%S"),
    })
    disposed = resp.get("data") or {}
    check("TC-23 处置成功", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    check("异常状态 CLOSED", disposed.get("status") == "CLOSED", str(disposed.get("status")))
    check("结案时间已写入", bool(disposed.get("closedAt")), str(disposed.get("closedAt")))
    dispose_event = (disposed.get("evidence") or {}).get("eventId")

    status, resp = client.call("GET", f"/api/process-batches/{ab_process_id}")
    check("恢复销售后状态 ON_SALE",
          (resp.get("data") or {}).get("status") == "ON_SALE",
          str((resp.get("data") or {}).get("status")))
    status, resp = client.call("POST", "/api/sales", {
        "processBatchNo": ab_process_no, "quantity": "1.000", "channel": "二食堂 1 号窗口",
    })
    check("恢复后可继续销售", resp.get("code") == 0,
          f"code={resp.get('code')} {resp.get('message', '')}")
    sale3_event = ((resp.get("data") or {}).get("evidence") or {}).get("eventId")

    print("\n[30] 等新事件上链 + 公开页时间线核对")
    for label, eid in (("销售", sale_event), ("召回", recall_event),
                       ("冷链", cold_event), ("处置", dispose_event), ("恢复后销售", sale3_event)):
        state = wait_confirmed(client, eid)
        check(f"{label}事件已确认", state == "CONFIRMED", f"最终状态={state}")

    status, resp = anon.call("GET", f"/api/public/trace/{single_no}")
    pr = resp.get("data") or {}
    timeline = pr.get("timeline") or []
    types = [t.get("eventType") for t in timeline]
    check("公开页出现销售登记事件", "SALE_CREATE" in types, str(types))
    check("公开页出现召回事件", "RECALL" in types, str(types))
    check("销售+召回事件全部 MATCHED",
          all(t.get("verifyStatus") == "MATCHED" for t in timeline if t.get("eventType") in
              ("SALE_CREATE", "RECALL")),
          str([(t.get("eventType"), t.get("verifyStatus")) for t in timeline]))
    check("召回后批次状态在公开页可读",
          (pr.get("summary") or {}).get("soldQty") == "28.000",
          str(pr.get("summary")))

    status, resp = anon.call("GET", f"/api/public/trace/{batch_no}")
    cold_types = [t.get("eventType") for t in ((resp.get("data") or {}).get("timeline") or [])]
    check("采购批次时间线含冷链登记", cold_types.count("COLD_CHAIN") == 2, str(cold_types))


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
