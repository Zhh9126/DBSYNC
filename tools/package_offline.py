#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Wave29：把 Maven assembly 产出的标准 bin.zip 组装成「解压即用」的离线发行包。

离线包 = 标准包全部内容 + 内嵌 JRE 8 + 离线运行说明（标准包已自带） + 产物基线校验 + SHA256。
内嵌 JRE 随平台不同；默认使用本机 Windows 64 位 JRE 8，Linux 目标机请用 --no-jre
生成通用包，或把 Linux 版 JRE 8 通过 --jre 指进来。

用法：
    python tools/package_offline.py                       # 自动找最新 DBDataSync-*-bin.zip + 默认 JRE
    python tools/package_offline.py --no-jre             # 只做基线校验，产出跨平台通用包
    python tools/package_offline.py --jre /path/to/jre --platform linux-x64
    python tools/package_offline.py --zip /path/bin.zip --out /path/offline.zip
"""

from __future__ import print_function

import argparse
import hashlib
import io
import os
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANUAL_SRC = ROOT / "dbdatasync-web" / "src" / "main" / "resources" / "public" / "manual" / "index.html"
DEFAULT_JRE_CANDIDATES = [
    os.environ.get("EMBED_JRE", ""),
    str(ROOT.parent / "tools" / "jdk8" / "jdk8u412-b08" / "jre"),
    str(ROOT / "tools" / "jdk8" / "jre"),
]

def expected_baseline():
    """交付基线取源码手册页头，避免卡口停在硬编码 Wave 上（手册随功能实时同步）。"""
    m = re.search(r"文档基线\s*<b>\s*Wave(\d+)", MANUAL_SRC.read_text(encoding="utf-8-sig"))
    if not m:
        raise SystemExit("[FAIL] 源码手册缺少「文档基线 WaveN」声明，无法判定交付基线")
    return "Wave%d" % int(m.group(1))


# 最新功能代码是否真的进了 jar（Wave28 四库 Trigger CDC / Wave26 切流接线）
JAR_MARKERS = [
    ("DBDataSync-connector-mysql-", "MySQLTriggerCdcDialect", "MySQL Trigger CDC 方言"),
    ("DBDataSync-connector-postgresql-", "PostgreSQLTriggerCdcDialect", "PostgreSQL Trigger CDC 方言"),
    ("DBDataSync-connector-oracle-", "OracleTriggerCdcDialect", "Oracle Trigger CDC 方言"),
    ("DBDataSync-connector-sqlserver-", "SqlServerTriggerCdcDialect", "SQL Server Trigger CDC 方言"),
    ("DBDataSync-biz-", "DefaultCutoverExecutor", "切流真实动作执行器"),
]


def pick_base_zip(explicit):
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            raise SystemExit("[FAIL] 指定的标准包不存在：%s" % p)
        return p
    zips = sorted(
        ROOT.glob("DBDataSync-*-bin.zip"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not zips:
        raise SystemExit("[FAIL] 未找到 DBDataSync-*-bin.zip，请先执行 ./build.sh")
    return zips[0]


def pick_jre(explicit, no_jre):
    if no_jre:
        return None
    candidates = [explicit] if explicit else DEFAULT_JRE_CANDIDATES
    for c in candidates:
        if not c:
            continue
        p = Path(c)
        if p.is_dir() and (p / "lib" / "rt.jar").is_file():
            return p
    raise SystemExit(
        "[FAIL] 找不到可内嵌的 JRE 8（需含 lib/rt.jar）。用 --jre 指定，或 --no-jre 产出通用包。\n"
        "       已尝试：%s" % ", ".join(str(c) for c in candidates if c)
    )


def verify_base(zin, base_dir):
    names = zin.namelist()
    required = [
        "%s/conf/application.properties" % base_dir,
        "%s/bin/startup.bat" % base_dir,
        "%s/bin/startup.sh" % base_dir,
        "%s/README-OFFLINE.md" % base_dir,
    ]
    missing = [r for r in required if r not in names]
    if missing:
        raise SystemExit("[FAIL] 标准包缺少条目：%s" % ", ".join(missing))

    jars = [n for n in names if n.startswith("%s/lib/" % base_dir) and n.endswith(".jar")]
    if len(jars) < 100:
        raise SystemExit("[FAIL] lib 中 jar 数量异常（%d），疑似打包不完整" % len(jars))
    print("[OK] 依赖 jar %d 个，conf/bin/离线说明齐全" % len(jars))

    web_jars = [n for n in jars if "/DBDataSync-web-" in n]
    if not web_jars:
        raise SystemExit("[FAIL] 缺少 DBDataSync-web jar")
    with zipfile.ZipFile(io.BytesIO(zin.read(web_jars[0]))) as wj:
        wn = wj.namelist()
        manual_path = "public/manual/index.html"
        if manual_path not in wn:
            manual_path = next((n for n in wn if n.endswith("public/manual/index.html")), None)
        if not manual_path:
            raise SystemExit("[FAIL] web jar 中未找到内置操作手册")
        manual = wj.read(manual_path).decode("utf-8", "replace")
        baseline = expected_baseline()
        m = re.search(r"文档基线\s*<b>\s*Wave(\d+)", manual)
        jar_baseline = "Wave%d" % int(m.group(1)) if m else "unknown"
        if jar_baseline != baseline:
            raise SystemExit(
                "[FAIL] 发行 jar 内手册基线 %s 与源码 %s 不一致 —— 打到旧包了，请先重新构建"
                % (jar_baseline, baseline)
            )
        print("[OK] 内置操作手册基线 %s（源码与 jar 一致，来自 %s）" % (baseline, web_jars[0]))

        login = wj.read("public/login.html").decode("utf-8", "replace") if "public/login.html" in wn else ""
        if login and "login-shell" not in login:
            print("[WARN] login.html 中未见 login-shell 分屏结构，请人工确认前端资源")

    for prefix, marker, label in JAR_MARKERS:
        target = next((n for n in jars if prefix in n), None)
        if not target:
            raise SystemExit("[FAIL] 缺少 jar：%s" % prefix)
        with zipfile.ZipFile(io.BytesIO(zin.read(target))) as cj:
            if not any(marker in n for n in cj.namelist()):
                raise SystemExit("[FAIL] %s 未打进 %s（%s）" % (label, target, marker))
    print("[OK] Wave26/28 关键类已进入发行 jar（切流执行器 + 四库 Trigger CDC 方言）")
    return len(jars)


def build(base_zip, jre, out, no_jre):
    with zipfile.ZipFile(base_zip) as zin:
        names = zin.namelist()
        base_dir = names[0].rstrip("/").split("/")[0]
        print("[1/4] 校验标准包：%s" % base_zip.name)
        jar_count = verify_base(zin, base_dir)

        print("[2/4] 写出离线包条目 ...")
        parent = out.parent
        if not parent.is_dir():
            parent.mkdir(parents=True)
        if out.exists():
            out.unlink()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))

            jre_files = 0
            if jre:
                for f in sorted(jre.rglob("*")):
                    if not f.is_file():
                        continue
                    arc = "%s/jre8/%s" % (base_dir, f.relative_to(jre).as_posix())
                    zout.write(str(f), arc)
                    jre_files += 1
            print("      标准包条目 %d，内嵌 JRE 文件 %d" % (len(names), jre_files))

    print("[3/4] 复核离线包结构 ...")
    with zipfile.ZipFile(out) as z:
        final = z.namelist()
        for required in (
            "%s/bin/startup.bat" % base_dir,
            "%s/conf/application.properties" % base_dir,
            "%s/README-OFFLINE.md" % base_dir,
        ):
            if required not in final:
                raise SystemExit("[FAIL] 离线包缺少 %s" % required)
        if jre:
            runtime = "%s/jre8/bin/java.exe" % base_dir
            runtime_posix = "%s/jre8/bin/java" % base_dir
            if runtime not in final and runtime_posix not in final:
                raise SystemExit("[FAIL] 内嵌 JRE 缺少 bin/java(.exe)")
            if "%s/jre8/lib/rt.jar" % base_dir not in final:
                raise SystemExit("[FAIL] 内嵌 JRE 缺少 lib/rt.jar")
        if z.testzip() is not None:
            raise SystemExit("[FAIL] 离线包 CRC 校验失败")

    digest = hashlib.sha256()
    with out.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    sha = digest.hexdigest()
    out.with_name(out.name + ".sha256").write_text("%s  %s\n" % (sha, out.name), encoding="utf-8")

    size_mb = out.stat().st_size / 1024.0 / 1024.0
    print("[4/4] 完成：%s（%.1f MB，%d 条目，%d 个依赖 jar）" % (out.name, size_mb, len(final), jar_count))
    print("      SHA256: %s" % sha)
    if no_jre:
        print("      通用包：目标机需自带 JDK/JRE 8，或自行放入对应平台的 jre8/ 目录")
    else:
        print("      内嵌 JRE：%s" % jre)
    return 0


def main():
    ap = argparse.ArgumentParser(description="组装 DBDataSync 离线发行包")
    ap.add_argument("--zip", help="标准 DBDataSync-*-bin.zip 路径（默认取根目录最新）")
    ap.add_argument("--jre", help="要内嵌的 JRE 8 目录（需含 lib/rt.jar）")
    ap.add_argument("--no-jre", action="store_true", help="不内嵌 JRE，产出跨平台通用包")
    ap.add_argument("--platform", default="win64", help="平台标记，仅用于产物命名")
    ap.add_argument("--out", help="输出 zip 路径")
    args = ap.parse_args()

    base_zip = pick_base_zip(args.zip)
    jre = pick_jre(args.jre, args.no_jre)
    version = base_zip.name.replace("-bin.zip", "")
    if args.out:
        out = Path(args.out)
    else:
        suffix = "offline-%s" % args.platform
        if args.no_jre:
            suffix = "offline-portable"
        out = ROOT / ("%s-%s.zip" % (version, suffix))
    return build(base_zip, jre, out, args.no_jre)


if __name__ == "__main__":
    sys.exit(main())
