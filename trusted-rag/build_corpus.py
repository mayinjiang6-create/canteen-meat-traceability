#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建可信语料：逐条查链核验，只导出摘要一致（MATCHED）的事件。

这是「可信 RAG」的数据门槛：知识库不收录被篡改（MISMATCHED）或核不了
（UNREACHABLE）的记录。导出的 data/events.json 自带核验状态，
之后离线运行问答与评测**不需要 MySQL、不需要链、不需要 API Key**。

用法（重建语料时才需要，且需要 pymysql）：
    python build_corpus.py

链不可达时会降级为「只依赖 CONFIRMED 任务状态」，并在 meta 里如实标注，
不会假装数据已核验。
"""
from __future__ import annotations

import json
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "events.json"
RPC_URL = "http://192.168.239.130:8545"
CONTRACT = "0xd24180cc0fef2f3e545de4f9aafc09345cd08903"
QUERY_SELECTOR = "a3b61a9c"  # query(bytes32)

TYPE_CN = {
    "PURCHASE_CREATE": "采购批次建单",
    "ACCEPT_RESULT": "入库验收",
    "PROCESS_CREATE": "加工投料",
    "SALE_CREATE": "销售登记",
    "COLD_CHAIN": "冷链温控登记",
    "DISPOSAL": "异常处置",
    "RECALL": "批次召回",
    "SUPPLIER_REG": "供应商备案",
}


def chain_digest(event_id: str, timeout: float = 4.0) -> str | None:
    """按 eventId 读链上摘要；返回 None 表示链不可达或链上无记录。"""
    body = {
        "jsonrpc": "2.0", "method": "eth_call",
        "params": [{"to": CONTRACT, "data": "0x" + QUERY_SELECTOR + event_id}, "latest"],
        "id": 1,
    }
    req = urllib.request.Request(
        RPC_URL, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    try:
        raw = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8")
        result = json.loads(raw).get("result") or ""
        data = bytes.fromhex(result[2:] if result.startswith("0x") else result)
        if len(data) < 7 * 32:
            return None
        exists = int.from_bytes(data[6 * 32:7 * 32], "big") != 0
        if not exists:
            return None
        return data[3 * 32:4 * 32].hex()
    except Exception:
        return None


def fmt_qty(v) -> str:
    if v is None:
        return ""
    try:
        return f"{float(v):.3f}"
    except (TypeError, ValueError):
        return str(v)


def render(event_type: str, batch_id: str, biz_time, quantity, unit, p: dict) -> str:
    """把结构化事件渲染成一段自然语言（TECH-09 §4.1：不要直接把 JSON 丢进检索器）。"""
    t = biz_time.strftime("%Y-%m-%d %H:%M") if hasattr(biz_time, "strftime") else str(biz_time)
    head = f"{t}，{TYPE_CN.get(event_type, event_type)}：{batch_id}。"
    q = fmt_qty(quantity)
    qtail = f"（{q} {unit or 'kg'}）" if q else ""

    if event_type == "PURCHASE_CREATE":
        return (f"{t}，采购批次 {batch_id}（{p.get('meatType', '肉类')}，{q} {unit or 'kg'}）建单。"
                f"供应商编码 {p.get('supplierCode', '未记录')}，"
                f"检疫合格证号 {p.get('quarantineNo', '未记录')}。")
    if event_type == "ACCEPT_RESULT":
        result = "合格（PASS）" if p.get("result") == "PASS" else "不合格（FAIL）"
        cert = "齐全" if p.get("certComplete") else "不齐全"
        return (f"{t}，采购批次 {batch_id}{qtail}完成入库验收。验收人 {p.get('inspector', '未记录')}，"
                f"到货中心温度 {fmt_qty(p.get('temperature'))} ℃，索证索票{cert}，"
                f"检验结论：{result}。验收单号 {p.get('acceptNo', '未记录')}。")
    if event_type == "PROCESS_CREATE":
        sources = p.get("sources") or []
        desc = "；".join(
            f"{s.get('purchaseBatchNo', '?')} 投入 {fmt_qty(s.get('usedQty'))} kg" for s in sources)
        return (f"{t}，加工批次 {batch_id} 完成投料，共 {len(sources)} 个来源：{desc or '未记录'}。"
                f"投入合计 {q} {unit or 'kg'}，产出 {fmt_qty(p.get('outputQty'))} kg，"
                f"留样号 {p.get('retainSampleNo', '未记录')}，"
                f"车间 {p.get('workshop', '未记录')}，操作人 {p.get('operator', '未记录')}。")
    if event_type == "SALE_CREATE":
        return (f"{t}，加工批次 {batch_id} 销售 {fmt_qty(p.get('quantity'))} kg 至"
                f"「{p.get('channel', '未记录')}」，单价 {fmt_qty(p.get('unitPrice'))} 元/kg，"
                f"销售单号 {p.get('saleNo', '未记录')}，操作人 {p.get('operator', '未记录')}。")
    if event_type == "COLD_CHAIN":
        exceeded = p.get("exceeded")
        verdict = "判定超限" if exceeded else "未超限"
        return (f"{t}，批次 {batch_id} 冷链温控登记：实测 {fmt_qty(p.get('temperature'))} ℃，"
                f"阈值 {fmt_qty(p.get('threshold'))} ℃，{verdict}。"
                f"运输段 {p.get('segment', '未记录')}，运输单 {p.get('transportNo', '未记录')}。")
    if event_type == "DISPOSAL":
        return (f"{t}，批次 {batch_id} 异常处置（阶段 {p.get('stage', '未记录')}）："
                f"{p.get('description', '未记录')}。类型 {p.get('abnormalType', '未记录')}，"
                f"等级 {p.get('level', '未记录')}，处理人 {p.get('handler', '未记录')}，"
                f"异常单号 {p.get('abnormalNo', '未记录')}。")
    if event_type == "RECALL":
        return (f"{t}，加工批次 {batch_id} 发起召回，原因：{p.get('reason', '未记录')}。"
                f"波及 {fmt_qty(p.get('affectedSales'))} 笔销售，需追回 "
                f"{fmt_qty(p.get('recalledQty'))} kg，召回单号 {p.get('recallNo', '未记录')}，"
                f"操作人 {p.get('operator', '未记录')}。")
    if event_type == "SUPPLIER_REG":
        return (f"{t}，供应商 {p.get('supplierCode', batch_id)}「{p.get('name', '未记录')}」完成备案。"
                f"许可证号 {p.get('licenseNo', '未记录')}，状态 {p.get('status', '未记录')}。")
    extra = "，".join(f"{k}={v}" for k, v in list(p.items())[:6])
    return head + (f" {extra}。" if extra else "")


def main() -> int:
    try:
        import pymysql
    except ImportError:
        print("重建语料需要 pymysql；直接使用已导出的 data/events.json 不需要它。")
        return 1

    conn = pymysql.connect(host="localhost", user="meat", password="Meat@2026",
                           database="meat_trace", charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT s.event_id, s.batch_id, s.event_type, s.biz_time, s.quantity, "
                "s.unit, s.payload_json, s.data_digest, t.status "
                "FROM event_snapshot s LEFT JOIN evidence_task t ON t.event_id = s.event_id "
                "ORDER BY s.id")
            rows = cur.fetchall()
    finally:
        conn.close()

    # 先探一次链是否可达，避免链挂了还逐条等超时（186 × 4s 会卡十几分钟）
    gold = "6f1c2b9a4e7d5083c1a2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718"
    chain_ok = chain_digest(gold) is not None
    print(f"链探测：{'可达，逐条核验摘要' if chain_ok else '不可达，降级为 CONFIRMED 任务状态'}")
    events, matched, skipped = [], 0, 0
    for eid, batch_id, etype, biz_time, qty, unit, payload, digest, task_status in rows:
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                payload = {}
        payload = payload or {}
        on_chain = chain_digest(eid) if chain_ok else None
        if chain_ok:
            if on_chain != str(digest).lower():
                skipped += 1
                continue
            verify = "MATCHED"
            matched += 1
        else:
            if task_status != "CONFIRMED":
                skipped += 1
                continue
            verify = "CONFIRMED_UNVERIFIED"
        events.append({
            "eventId": eid, "batchId": batch_id, "eventType": etype,
            "bizTime": biz_time.strftime("%Y-%m-%d %H:%M:%S"),
            "verifyStatus": verify, "dataDigest": digest,
            "text": render(etype, batch_id, biz_time, qty, unit, payload),
            "payload": payload,
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    meta = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "source": "meat_trace.event_snapshot（真实运行数据）",
        "chainVerified": chain_ok,
        "rule": "链上摘要 == 本地 data_digest 才收录；链不可达时降级为 CONFIRMED 任务并标注",
        "totalSnapshots": len(rows), "included": len(events),
        "excluded": skipped, "matched": matched,
    }
    OUT.write_text(json.dumps({"meta": meta, "events": events},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"语料导出：{OUT}")
    print(f"  快照 {len(rows)} 条 → 收录 {len(events)} 条 / 排除 {skipped} 条")
    print(f"  链核验可用={chain_ok}，MATCHED={matched}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
