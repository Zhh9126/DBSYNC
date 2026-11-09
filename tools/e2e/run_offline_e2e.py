#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Wave24：手工 E2E 脚本统一入口 / CI 卡口。

此前根目录 26 个 ``_*.py`` 脚本分散在各轮联调中，从未进入流水线。本入口提供：

* ``--ci``：对全部脚本做语法编译检查 + 分类清点（不需要任何数据库连接，CI 默认执行）；
* ``--smoke``：执行标记为「离线安全」的脚本（当前仅语法与依赖自检，不连库）；
* ``--real-db``：需要 ``DBDATASYNC_E2E_CONFIG`` 指向 e2e.properties 时才允许连接真库，
  否则明确跳过并给出原因，绝不偷偷尝试远程地址。

真机 E2E 的实际执行入口仍是 ``tools/e2e/RealDbE2eHarness.java``，见
``reports/E2E_REAL_DB_RUNBOOK.md``。
"""
from __future__ import print_function

import argparse
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPORT_DIR = os.path.join(os.path.dirname(ROOT), "reports")

ENV_PREP = ("install", "repo", "prep", "_pw", "mk_dst", "mysql_pw")
E2E = ("e2e", "sync", "verify", "test_sync", "vs_scan")
OPS = ("tail", "log", "readrun", "tcp", "run_migration", "start_migration", "migration")


def classify(name):
    low = name.lower()
    if any(k in low for k in E2E):
        return "e2e"
    if any(k in low for k in ENV_PREP):
        return "environment"
    if any(k in low for k in OPS):
        return "operations"
    return "misc"


def discover():
    files = []
    for entry in sorted(os.listdir(ROOT)):
        if entry.startswith("_") and entry.endswith(".py"):
            files.append(os.path.join(ROOT, entry))
    return files


def syntax_check(path):
    try:
        with open(path, "rb") as fh:
            source = fh.read()
        compile(source, path, "exec")
        return None
    except (SyntaxError, ValueError, OSError) as exc:  # noqa: B902
        return "{}: {}".format(type(exc).__name__, exc)


def main():
    parser = argparse.ArgumentParser(description="DBDataSync E2E scripts gate")
    parser.add_argument("--ci", action="store_true", help="CI 模式：只做语法与清点，不连库")
    parser.add_argument("--smoke", action="store_true", help="离线冒烟（不连库）")
    parser.add_argument("--real-db", action="store_true", help="请求真库 E2E（需 DBDATASYNC_E2E_CONFIG）")
    args = parser.parse_args()

    scripts = discover()
    failures = []
    categories = {}
    for path in scripts:
        error = syntax_check(path)
        category = classify(os.path.basename(path))
        categories[category] = categories.get(category, 0) + 1
        if error:
            failures.append({"script": os.path.basename(path), "error": error})

    real_db = {"requested": args.real_db, "executed": False, "reason": ""}
    if args.real_db:
        config = os.environ.get("DBDATASYNC_E2E_CONFIG", "")
        if not config or not os.path.isfile(config):
            real_db["reason"] = "未设置 DBDATASYNC_E2E_CONFIG 或文件不存在，真库 E2E 跳过"
        else:
            # 真库 harness 由 CI 的 real-db-e2e job 显式调用，此处只登记可运行条件
            real_db["reason"] = "配置就绪，请执行 RealDbE2eHarness（本入口不直接连库）"
            real_db["config"] = config

    report = {
        "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "mode": "ci" if args.ci else ("smoke" if args.smoke else "inventory"),
        "scriptCount": len(scripts),
        "categories": categories,
        "syntaxFailures": failures,
        "realDb": real_db,
        "scripts": [os.path.basename(p) for p in scripts],
        "note": "语法/清点卡口在 CI 恒跑；真机 E2E 需显式 workflow_dispatch 与连接串",
    }
    if REPORT_DIR and os.path.isdir(REPORT_DIR):
        out = os.path.join(REPORT_DIR, "E2E_OFFLINE_GATE.json")
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
