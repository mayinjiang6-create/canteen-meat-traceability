#!/usr/bin/env python3
"""WeBASE-Front (v3.x) 链交互小工具：编译 / 部署 / 写入 / 查询。

用法示例：
    set WEBASE_URL=http://192.168.239.130:5002/WeBASE-Front
    python scripts/chain_tools.py info
    python scripts/chain_tools.py compile
    python scripts/chain_tools.py deploy
    python scripts/chain_tools.py append <eventId> <batchId> <eventType> <bizTime> <digest>
    python scripts/chain_tools.py query <eventId>
    python scripts/chain_tools.py receipt <txHash>

环境变量：
    WEBASE_URL     默认 http://192.168.239.130:5002/WeBASE-Front
    RPC_URL        默认 http://192.168.239.130:8545（节点标准 JSON-RPC，用于只读查询）
    GROUP_ID       默认 group0（FISCO BCOS 3.x 用字符串组名）
    SIGN_USER_ID   默认 meattraceadmin
"""
from __future__ import annotations

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

WEBASE_URL = os.environ.get("WEBASE_URL", "http://192.168.239.130:5002/WeBASE-Front").rstrip("/")
GROUP_ID = os.environ.get("GROUP_ID", "group0")
SIGN_USER = os.environ.get("SIGN_USER_ID", "meattraceadmin")
USER_ADDR = os.environ.get("USER_ADDR", "0x0bd53242ca7ee7fb0ce57a3cb296b6d4b237dbe8")
RPC_URL = os.environ.get("RPC_URL", "http://192.168.239.130:8545")
CONTRACT = "TraceRegistry"

ROOT = Path(__file__).resolve().parent.parent
SOL = ROOT / "contract" / "TraceRegistry.sol"
BUILD = ROOT / "contract" / "build"


def _post(path: str, payload: dict, timeout: int = 120) -> dict:
    req = urllib.request.Request(
        WEBASE_URL + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        raw = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        print(f"[HTTP {exc.code}] {path}: {raw[:500]}", file=sys.stderr)
        return json.loads(raw) if raw.startswith("{") else {"error": raw}
    return json.loads(raw)


def _post_raw(path: str, payload: dict, timeout: int = 180) -> tuple[int, str]:
    """返回 (HTTP 状态码, 原始响应文本)。部分接口成功时只返回纯文本。"""
    req = urllib.request.Request(
        WEBASE_URL + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def _get(path: str, timeout: int = 30):
    raw = urllib.request.urlopen(WEBASE_URL + path, timeout=timeout).read().decode("utf-8")
    return json.loads(raw)


def cmd_info() -> int:
    print("webase:", WEBASE_URL)
    print("group :", GROUP_ID)
    print("groups:", _get("/group0/web3/groupList") if GROUP_ID == "group0" else "?")
    print("block :", _get(f"/{GROUP_ID}/web3/blockNumber"))
    return 0


def cmd_compile() -> int:
    source = SOL.read_text(encoding="utf-8")
    resp = _post(
        "/contract/contractCompile",
        {
            "contractName": CONTRACT,
            "groupId": GROUP_ID,
            "solidityBase64": base64.b64encode(source.encode("utf-8")).decode(),
        },
    )
    abi = resp.get("contractAbi")
    bin_ = resp.get("contractBin") or resp.get("bytecodeBin")
    if not abi or not bin_:
        print("编译失败:", json.dumps(resp, ensure_ascii=False)[:600], file=sys.stderr)
        return 1
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD / f"{CONTRACT}.abi.json").write_text(abi, encoding="utf-8")
    (BUILD / f"{CONTRACT}.bin").write_text(bin_, encoding="utf-8")
    print(f"编译成功 abi={len(abi)} 字节 bin={len(bin_)} 字符")
    return 0


def cmd_deploy() -> int:
    abi_path = BUILD / f"{CONTRACT}.abi.json"
    bin_path = BUILD / f"{CONTRACT}.bin"
    if not abi_path.exists():
        cmd_compile()
    abi_str = abi_path.read_text(encoding="utf-8")
    bin_ = bin_path.read_text(encoding="utf-8").strip()
    # deployWithSign 成功时直接返回纯文本合约地址（不是 JSON）
    code, body = _post_raw(
        "/contract/deployWithSign",
        {
            "groupId": GROUP_ID,
            "contractName": CONTRACT,
            "contractPath": "",
            "bytecodeBin": bin_,
            "abiInfo": json.loads(abi_str),
            "funcParam": [],
            "signUserId": SIGN_USER,
        },
    )
    text = body.strip().strip('"')
    if code == 200 and text.startswith("0x") and len(text) == 42:
        dep = BUILD / "deployment.json"
        dep.write_text(
            json.dumps({"contractName": CONTRACT, "contractAddress": text,
                        "groupId": GROUP_ID, "deployer": SIGN_USER},
                       ensure_ascii=False, indent=2),
            encoding="utf-8")
        print("部署成功:", text)
        return 0
    print(f"部署失败 HTTP {code}: {body[:500]}", file=sys.stderr)
    return 1


def _abi_and_addr():
    abi_str = (BUILD / f"{CONTRACT}.abi.json").read_text(encoding="utf-8")
    addr = json.loads((BUILD / "deployment.json").read_text(encoding="utf-8"))["contractAddress"]
    return abi_str, addr


def cmd_append(args: list[str]) -> int:
    if len(args) != 5:
        print("用法: append <eventId(64hex)> <batchId> <eventType> <bizTime(秒)> <digest(64hex)>", file=sys.stderr)
        return 2
    event_id, batch_id, event_type, biz_time, digest = args
    abi_str, addr = _abi_and_addr()
    resp = _post(
        "/trans/handleWithSign",
        {
            "groupId": GROUP_ID,
            "contractName": CONTRACT,
            "contractPath": "",
            "contractAddress": addr,
            "contractAbi": json.loads(abi_str),
            "funcName": "append",
            "funcParam": [event_id, batch_id, event_type, str(biz_time), digest],
            "signUserId": SIGN_USER,
        },
    )
    print(json.dumps(resp, ensure_ascii=False))
    return 0


def _rpc(method: str, params: list, timeout: int = 20) -> dict:
    """调用节点标准 JSON-RPC（web3_rpc，默认 8545）。"""
    payload = {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}
    req = urllib.request.Request(
        RPC_URL, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    raw = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8")
    return json.loads(raw)


def _word(data: bytes, index: int) -> bytes:
    return data[index * 32:(index + 1) * 32]


def _decode_dyn_string(data: bytes, offset_word: bytes) -> str:
    off = int.from_bytes(offset_word, "big")
    length = int.from_bytes(data[off:off + 32], "big")
    return data[off + 32:off + 32 + length].decode("utf-8", "replace")


def decode_query_result(hexstr: str) -> dict:
    """解出 query() 的 7 个返回值。"""
    raw = hexstr[2:] if hexstr.startswith("0x") else hexstr
    data = bytes.fromhex(raw)
    return {
        "batchId": _decode_dyn_string(data, _word(data, 0)),
        "eventType": _decode_dyn_string(data, _word(data, 1)),
        "bizTime": int.from_bytes(_word(data, 2), "big"),
        "dataDigest": _word(data, 3).hex(),
        "submitter": "0x" + _word(data, 4)[12:].hex(),
        "createdAt": int.from_bytes(_word(data, 5), "big"),
        "exists": bool(int.from_bytes(_word(data, 6), "big")),
    }


def cmd_query(args: list[str]) -> int:
    if len(args) != 1:
        print("用法: query <eventId(64hex)>", file=sys.stderr)
        return 2
    abi_str, addr = _abi_and_addr()
    code, encode_str = _post_raw(
        "/trans/encodeFunction",
        {"groupId": GROUP_ID, "contractAbi": json.loads(abi_str),
         "funcName": "query", "funcParam": [args[0]]},
    )
    encode_str = encode_str.strip().strip('"')
    if code != 200 or not encode_str:
        print(f"编码失败 HTTP {code}: {encode_str[:300]}", file=sys.stderr)
        return 1
    # 读走标准 JSON-RPC，绕开 WeBASE-Front 3.1.1 的 query-transaction 缺陷
    resp = _rpc("eth_call", [{"to": addr, "data": "0x" + encode_str}, "latest"])
    if "error" in resp:
        print(json.dumps(resp, ensure_ascii=False), file=sys.stderr)
        return 1
    decoded = decode_query_result(resp["result"])
    print(json.dumps(decoded, ensure_ascii=False, indent=2))
    return 0


def cmd_receipt(args: list[str]) -> int:
    if len(args) != 1:
        print("用法: receipt <txHash(0x...)>", file=sys.stderr)
        return 2
    resp = _rpc("eth_getTransactionReceipt", [args[0]])
    result = resp.get("result") or {}
    brief = {
        "transactionHash": result.get("transactionHash"),
        "blockNumber": int(result["blockNumber"], 16) if result.get("blockNumber") else None,
        "status": result.get("status"),
        "gasUsed": int(result["gasUsed"], 16) if result.get("gasUsed") else None,
        "from": result.get("from"),
        "to": result.get("to"),
    }
    print(json.dumps(brief, ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd, rest = sys.argv[1], sys.argv[2:]
    table = {"info": lambda: cmd_info(), "compile": lambda: cmd_compile(),
             "deploy": lambda: cmd_deploy(), "append": lambda: cmd_append(rest),
             "query": lambda: cmd_query(rest), "receipt": lambda: cmd_receipt(rest)}
    if cmd not in table:
        print(f"未知命令: {cmd}", file=sys.stderr)
        return 2
    return table[cmd]()


if __name__ == "__main__":
    sys.exit(main())
