#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DBDataSync 操作手册同步校验。

用途：功能变更后，检查离线内置操作手册（public/manual/index.html）是否仍然
与实际界面/配置一致。被 CI 的 docs-sync 任务调用，也可在提交前本地执行：

    python tools/check_manual_sync.py

检查项：
  1. 系统配置页（system/list.html）的每个表单字段都出现在手册中；
  2. 侧边栏（sidebar.html）的每个菜单项都出现在手册中；
  3. 手册声明的功能基线与更新日期覆盖当前最新的 Wave 报告（报告目录存在时）；
  4. 手册自身基线、变更记录、fragment 结构与 HTML 标签平衡。

报告目录约定：优先 <repo>/reports，其次工作区同级 <parent>/reports。
CI 只 checkout 代码仓库、没有 reports 目录时，第 3 项的跨目录比对会降级为
「手册内部基线自洽」检查，其余检查照常执行。
"""

from __future__ import print_function

import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB_RES = ROOT / "dbdatasync-web" / "src" / "main" / "resources"
MANUAL = WEB_RES / "public" / "manual" / "index.html"
SYSTEM_PAGE = WEB_RES / "public" / "system" / "list.html"
SIDEBAR = WEB_RES / "public" / "sidebar.html"

VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
}


class TagBalanceChecker(HTMLParser):
    """检查常见标签是否正确闭合（忽略 void 元素与自闭合标签）。"""

    def __init__(self):
        HTMLParser.__init__(self)
        self.stack = []
        self.problems = []

    def handle_starttag(self, tag, attrs):
        if tag not in VOID_TAGS:
            self.stack.append((tag, self.getpos()[0]))

    def handle_startendtag(self, tag, attrs):
        pass

    def handle_endtag(self, tag):
        if tag in VOID_TAGS:
            return
        if not self.stack:
            self.problems.append("第 %d 行多余的 </%s>" % (self.getpos()[0], tag))
            return
        if self.stack[-1][0] == tag:
            self.stack.pop()
            return
        for idx in range(len(self.stack) - 1, -1, -1):
            if self.stack[idx][0] == tag:
                unclosed = self.stack[idx + 1:]
                self.problems.append(
                    "第 %d 行 </%s> 之前未闭合：%s"
                    % (self.getpos()[0], tag, ", ".join("<%s>(第%d行)" % (t, ln) for t, ln in unclosed))
                )
                del self.stack[idx:]
                return
        self.problems.append("第 %d 行 </%s> 没有对应的开始标签" % (self.getpos()[0], tag))


def read_text(path):
    return path.read_text(encoding="utf-8-sig")


def find_reports_dir():
    for candidate in (ROOT / "reports", ROOT.parent / "reports"):
        if candidate.is_dir():
            return candidate
    return None


def main():
    errors = []
    notes = []

    for required in (MANUAL, SYSTEM_PAGE, SIDEBAR):
        if not required.is_file():
            print("[FAIL] 缺少文件：%s" % required)
            return 1

    manual_bytes = MANUAL.read_bytes()
    if manual_bytes.startswith(b"\xef\xbb\xbf"):
        errors.append("操作手册带有 UTF-8 BOM，请改为无 BOM 编码")
    manual = read_text(MANUAL)

    # 1. 系统配置字段覆盖
    config_names = sorted(set(re.findall(r'name="([A-Za-z][A-Za-z0-9_]*)"', read_text(SYSTEM_PAGE))))
    missing_config = [name for name in config_names if name not in manual]
    if missing_config:
        errors.append("系统配置页有 %d 个字段未写入操作手册：%s" % (len(missing_config), ", ".join(missing_config)))
    else:
        print("[OK] 系统配置字段 %d/%d 已写入操作手册" % (len(config_names), len(config_names)))

    # 2. 侧边栏菜单覆盖
    sidebar = read_text(SIDEBAR)
    items = re.findall(r'<div class="sidebar-item[^"]*"[^>]*>(.*?)</div>', sidebar, re.S)
    menu_names = []
    for block in items:
        span = re.search(r"<span>(.*?)</span>", block, re.S)
        if span:
            name = re.sub(r"<[^>]+>", "", span.group(1)).strip()
            if name:
                menu_names.append(name)
    missing_menu = [name for name in menu_names if name not in manual]
    if missing_menu:
        errors.append("侧边栏有 %d 个菜单项未写入操作手册：%s" % (len(missing_menu), ", ".join(missing_menu)))
    else:
        print("[OK] 侧边栏菜单 %d/%d 已写入操作手册" % (len(menu_names), len(menu_names)))

    # 3. 功能基线与更新日期
    baseline_match = re.search(r"文档基线\s*<b>\s*Wave(\d+)", manual)
    if not baseline_match:
        errors.append("操作手册页头缺少「文档基线 WaveN」声明")
        baseline = None
    else:
        baseline = int(baseline_match.group(1))

    date_match = re.search(r"更新日期\s*<b>\s*(\d{4}-\d{2}-\d{2})", manual)
    if not date_match:
        errors.append("操作手册页头缺少「更新日期 YYYY-MM-DD」声明")
        manual_date = None
    else:
        manual_date = date_match.group(1)

    reports_dir = find_reports_dir()
    report_files = sorted(reports_dir.glob("OPTIMIZATION_REPORT_WAVE*.md")) if reports_dir else []
    report_waves = []
    report_dates = []
    for rf in report_files:
        m = re.search(r"WAVE(\d+)", rf.name)
        if m:
            report_waves.append(int(m.group(1)))
        head = "\n".join(read_text(rf).splitlines()[:20])
        for d in re.findall(r"日期[：:]\s*(\d{4}-\d{2}-\d{2})", head):
            report_dates.append(d)

    if report_waves:
        latest_wave = max(report_waves)
        latest_date = max(report_dates) if report_dates else None
        if baseline is None or baseline < latest_wave:
            errors.append("手册基线 Wave%s 落后于当前最新功能报告 Wave%d" % (baseline, latest_wave))
        elif baseline > latest_wave:
            notes.append("手册基线 Wave%d 高于报告目录中的 Wave%d（本地报告可能未同步）" % (baseline, latest_wave))
        else:
            print("[OK] 文档基线 Wave%d 与最新功能报告一致" % baseline)
        if latest_date:
            if manual_date is None or manual_date < latest_date:
                errors.append("手册更新日期 %s 早于最新报告日期 %s" % (manual_date, latest_date))
            else:
                print("[OK] 手册更新日期 %s 不早于最新报告日期 %s" % (manual_date, latest_date))
    else:
        notes.append("未找到 Wave 报告目录（CI 场景），跳过跨目录基线比对")
        if baseline is not None:
            recorded = [int(n) for n in re.findall(r"Wave(\d+)", manual)]
            if not recorded or max(recorded) < baseline:
                errors.append("手册变更记录中缺少 Wave%d 条目" % baseline)
            else:
                print("[OK] 手册内部基线与变更记录自洽（Wave%d）" % baseline)

    # 4. 结构与标签平衡
    if 'th:fragment="content"' not in manual:
        errors.append("操作手册缺少 th:fragment=\"content\"，将无法嵌入主布局")
    if "维护约定" not in manual or "变更记录" not in manual:
        errors.append("操作手册缺少第 18 章「变更记录与维护约定」")

    checker = TagBalanceChecker()
    checker.feed(manual)
    if checker.stack:
        errors.append("HTML 存在未闭合标签：%s" % ", ".join("<%s>(第%d行)" % (t, ln) for t, ln in checker.stack))
    if checker.problems:
        errors.extend(checker.problems[:5])
    if not checker.stack and not checker.problems:
        print("[OK] Thymeleaf fragment 结构与 HTML 标签平衡、UTF-8 无 BOM")

    for note in notes:
        print("[INFO] %s" % note)

    if errors:
        print("")
        for err in errors:
            print("[FAIL] %s" % err)
        print("")
        print("文档同步校验失败：请按 public/manual/index.html 第 18 章维护约定更新操作手册后重试。")
        return 1

    print("")
    print("文档同步校验通过：操作手册与当前功能一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
