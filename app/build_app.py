#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把真实业务数据导出成快照并内嵌，生成单文件交互式软件 app.html。

    python build_app.py

产物 app.html 双击即可运行：完整业务系统（10 个模块）+ AI 智能体模块，
全部交互在浏览器本地完成，不需要 Java / MySQL / 区块链 / API Key。

数据来源：本机 MySQL meat_trace（真实运行数据快照），不含任何账号密码。
模板在 app.template.html，语料/数据更新后重跑本脚本即可。
"""
from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / "app.template.html"
OUT = ROOT / "app.html"


def export() -> dict:
    # 优先用已导出的快照，这样任何人重跑都不需要 MySQL / pymysql
    snap = ROOT / "data" / "snapshot.json"
    if snap.exists():
        return json.loads(snap.read_text(encoding="utf-8"))

    import pymysql
    conn = pymysql.connect(host="localhost", user="meat", password="Meat@2026",
                           database="meat_trace", charset="utf8mb4")
    cur = conn.cursor(pymysql.cursors.DictCursor)

    def q(sql):
        cur.execute(sql)
        out = []
        for row in cur.fetchall():
            d = {}
            for k, v in row.items():
                if isinstance(v, Decimal):
                    d[k] = str(v)
                elif isinstance(v, (datetime, date)):
                    d[k] = v.strftime("%Y-%m-%d %H:%M:%S")
                else:
                    d[k] = v
            out.append(d)
        return out

    data = {
        "suppliers": q("select id,supplier_code,name,license_no,contact_name,"
                       "contact_phone,address,status from supplier"),
        "purchases": q("select id,batch_no,supplier_id,meat_type,quarantine_no,"
                       "quantity,available_qty,unit,unit_price,arrive_time,"
                       "cold_chain_temp,status from purchase_batch"),
        "acceptances": q("select id,accept_no,batch_id,inspector,temperature,"
                         "appearance_ok,cert_complete,sample_retained,result,"
                         "reject_reason,accept_time from acceptance_record"),
        "processes": q("select id,process_no,meat_type,output_qty,available_qty,"
                       "unit,process_time,workshop,operator,retain_sample_no,"
                       "status from process_batch"),
        "sources": q("select id,process_batch_id,purchase_batch_id,used_qty "
                     "from source_relation"),
        "sales": q("select id,sale_no,process_batch_id,channel,quantity,"
                   "unit_price,sale_time,status from sale_record"),
        "coldChain": q("select id,log_no,batch_id,segment,temperature,threshold,"
                       "is_exceeded,recorded_at from cold_chain_log"),
        "abnormals": q("select id,abnormal_no,batch_id,process_batch_id,"
                       "abnormal_type,level,description,status,handler,conclusion "
                       "from abnormal_event"),
        "recalls": q("select id,recall_no,process_batch_id,reason,affected_sales,"
                     "recalled_qty,status,recall_time from recall_record"),
        "events": q("select event_id,batch_id,event_type,biz_time,data_digest "
                    "from event_snapshot"),
        "users": q("select username,display_name,role from sys_user"),
    }
    conn.close()
    return data


def main() -> int:
    data = export()
    docs = json.loads((ROOT.parent / "trusted-rag/data/knowledge_docs.json")
                      .read_text(encoding="utf-8"))
    payload = {
        "meta": {
            "generatedAt": datetime.now().isoformat(timespec="seconds"),
            "source": "meat_trace 真实运行数据快照（不含密码）",
            "note": "离线演示版：业务动作只改浏览器本地状态，不写库、不上链",
        },
        "data": data, "docs": docs,
    }
    js = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    html = TEMPLATE.read_text(encoding="utf-8")
    if "__APP_DATA__" not in html:
        raise SystemExit("模板里找不到 __APP_DATA__ 占位符")
    OUT.write_text(html.replace("__APP_DATA__", js), encoding="utf-8")
    print(f"已生成 {OUT}")
    print("  各表:", {k: len(v) for k, v in data.items()})
    print(f"  文件大小 {OUT.stat().st_size / 1024:.1f} KB（单文件，双击即可打开）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
