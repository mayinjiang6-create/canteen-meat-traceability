#!/usr/bin/env python3
"""在 Ubuntu 虚拟机上执行命令（paramiko 密码登录）。

用法：
    set VM_PASS=123456
    python scripts/vm_exec.py "hostname; whoami"

环境变量：
    VM_HOST  默认 192.168.239.130
    VM_USER  默认 zym
    VM_PASS  必填（不写入仓库）
"""
import os
import sys

import paramiko

HOST = os.environ.get("VM_HOST", "192.168.239.130")
USER = os.environ.get("VM_USER", "zym")
PASS = os.environ.get("VM_PASS", "")


def main() -> int:
    if not PASS:
        print("VM_PASS 未设置", file=sys.stderr)
        return 2
    cmd = sys.argv[1] if len(sys.argv) > 1 else "hostname; whoami; uptime"

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(HOST, port=22, username=USER, password=PASS,
                       timeout=15, auth_timeout=15, banner_timeout=15,
                       look_for_keys=False, allow_agent=False)
    except Exception as exc:  # noqa: BLE001
        print(f"CONNECT_FAIL: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    try:
        _stdin, stdout, stderr = client.exec_command(cmd, timeout=120)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        code = stdout.channel.recv_exit_status()
        if out:
            print(out.rstrip())
        if err:
            print("--- stderr ---", file=sys.stderr)
            print(err.rstrip(), file=sys.stderr)
        return code
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
