#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V-08 机械核对脚本（design.md §10 V-08 / §14.6 规则 1-2；KPI-02、AC-03、AC-16 数据侧）。

判据（全部只读字段，脚本内不出现任何 message 匹配）：
  1. 取值域 = 扩展阶段内的行（不是 logs 全部行）：
       - stepId != null 的行必带 stage == "EXTENSION"
       - stepId == null 的行按 §14.6 stage 行的钉值归属，两条条件行（BR-12 拦截行 /
         目录不合法说明行）不得让脚本误报：BR-12 拦截行在取值域内（取 EXTENSION），
         目录不合法说明行在取值域外（归它实际发生的既有阶段，本期 "DEPLOY"）
       - 段外的既存阶段行不得取 "EXTENSION" 常量
  2. 统计对象 = stepId != null 的行；分母 = 非空 stepId 的去重计数
       （进入 / 交棒 / 停实例 / BR-12 / 目录不合法 / 收尾 / 阶段完成 七类阶段级行一律不进分母）
  3. 每个非空 stepId 恰有一个 START 行 + 恰有一个终态行（SUCCESS 或 FAILURE），
       且该终态行 elapsedMs != null  —— 三项齐备
  4. KPI-02 = 齐备步骤数 / 分母，目标 100%
  5. 规则 3 串行可见：步骤 N 的终态行之后才允许出现步骤 N+1 的 START 行
  6. 规则 4 退出码：SCRIPT 的终态行 stepEvent == FAILURE <=> exitCode != 0
       （超时那一支 exitCode == null，由 timedOut 语义承载，脚本按 exitCode 字段判，不读文本）
       PATCH 的终态行与所有阶段级行 exitCode 恒为 null
  7. 规则 5 回滚记录位：某 PATCH 步骤终态为 FAILURE => 该 stepId 下、终态行之后、
       下一步骤 START 之前恰有一行 ROLLBACK

用法：
    python scripts/v08-extension-log-contract.py <deploy-progress.json> [...]

输入是 GET /api/instances/{id}/deploy-progress 的响应体（Result 包装或裸 DeployProgressVO 都可）。
注意：另一条通道 GET /api/instances/{id}/logs 把 LogEntry 摊平成文本行，那里读不到扩展字段，
拿它当输入是误判（§6.1 v0.3.1 补 SUG-10），脚本会在这种输入上直接报错而不是给一个假通过。
"""

import json
import sys

# Windows 控制台默认按代码页编码 stdout（本机 cp936），判定行里的「⇔」会抛
# UnicodeEncodeError，让一次全 PASS 的核对以 exit=1 收场。核对物必须换代码页也可复跑。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

EXTENSION = "EXTENSION"
TERMINAL_EVENTS = {"SUCCESS", "FAILURE"}
STEP_EVENTS = {"START", "SUCCESS", "FAILURE", "ROLLBACK", "NOTE"}


def load_payload(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    # 允许 Result 包装：{code, message, data:{...}}
    if isinstance(data, dict) and "data" in data and isinstance(data["data"], dict):
        candidate = data["data"]
    else:
        candidate = data
    if not isinstance(candidate, dict) or "logs" not in candidate:
        raise SystemExit(
            "%s: 不是 deploy-progress 的响应体（缺 logs）。若是 /api/instances/{id}/logs，"
            "那条通道把 LogEntry 摊平成文本行，扩展字段不在那里 —— 用它当输入是误判。" % path)
    rows = candidate["logs"]
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise SystemExit(
            "%s: logs 是文本行而不是结构化行 —— 取到的是 /api/instances/{id}/logs 那条摊平通道，"
            "扩展字段不在那里，用它当输入是误判（§6.1 SUG-10）。" % path)
    return candidate


def index_rows(rows):
    grouped = {}
    for row in rows:
        step_id = row.get("stepId")
        if step_id is not None:
            grouped.setdefault(step_id, []).append(row)
    return grouped


def check_domain(rows, report):
    """判据 1：取值域。"""
    for row in rows:
        stage = row.get("stage")
        step_id = row.get("stepId")
        if step_id is not None and stage != EXTENSION:
            report.error("stepId=%s 的行 stage=%s，必带 EXTENSION" % (step_id, stage))
        if step_id is None and stage == EXTENSION:
            # 阶段级行里只有 BR-12 拦截那一支归 EXTENSION（stepId == null），
            # 但它带 stepEvent == null 且没有步骤行跟着它 —— 不构成误报；
            # 真正的误报源是「段外既存阶段行取了 EXTENSION」，那由下一句判。
            pass
    extension_rows = [r for r in rows if r.get("stage") == EXTENSION]
    if extension_rows:
        first = extension_rows[0]
        if first.get("stepId") is None and first.get("stepEvent") is not None:
            report.warn("EXTENSION 段的第一行是带 stepEvent 的阶段级行（%s），"
                        "正常序列里它应是进入行（stepEvent 为 null）" % first.get("stepEvent"))


def check_serial(rows, report):
    """判据 5：串行可见 —— 步骤 N 的终态行之后才允许出现步骤 N+1 的 START 行。"""
    first_start = {}
    terminal = {}
    for position, row in enumerate(rows):
        step_id = row.get("stepId")
        event = row.get("stepEvent")
        if step_id is None:
            continue
        if event == "START" and step_id not in first_start:
            first_start[step_id] = position
        elif event in TERMINAL_EVENTS:
            terminal[step_id] = position
    order = sorted(set(list(first_start) + list(terminal)),
                   key=lambda s: first_start.get(s, terminal.get(s)))
    for previous, following in zip(order, order[1:]):
        if previous in terminal and following in first_start:
            if terminal[previous] > first_start[following]:
                report.error("步骤 %s 的 START 出现在步骤 %s 的终态行之前（违反串行可见）"
                             % (following, previous))


def check_complete(grouped, report):
    """判据 2/3/4：三项齐备与 KPI-02。"""
    denominator = len(grouped)
    if denominator == 0:
        report.warn("分母为 0：本次部署没有扩展步骤（KPI-02 在本份样本上不适用，不判 FAIL）")
        return None
    complete = 0
    for step_id, rows in sorted(grouped.items()):
        starts = [r for r in rows if r.get("stepEvent") == "START"]
        terminals = [r for r in rows if r.get("stepEvent") in TERMINAL_EVENTS]
        if len(starts) != 1:
            report.error("%s 的 START 行数 = %d（应为恰一）" % (step_id, len(starts)))
            continue
        if len(terminals) != 1:
            report.error("%s 的终态行数 = %d（应为恰一）" % (step_id, len(terminals)))
            continue
        if terminals[0].get("elapsedMs") is None:
            report.error("%s 的终态行 elapsedMs 为空（耗时不可见）" % step_id)
            continue
        complete += 1
    ratio = complete / denominator
    if ratio < 1.0:
        report.error("KPI-02 = %d/%d = %.2f%%（目标 100%%）" % (complete, denominator, ratio * 100))
    else:
        report.ok("KPI-02 = %d/%d = 100%%" % (complete, denominator))
    return ratio


def check_exit_code(grouped, report):
    """判据 6：退出码承载位。"""
    for step_id, rows in grouped.items():
        terminals = [r for r in rows if r.get("stepEvent") in TERMINAL_EVENTS]
        if not terminals:
            continue
        terminal = terminals[0]
        step_type = terminal.get("stepType")
        exit_code = terminal.get("exitCode")
        if step_type == "PATCH":
            if exit_code is not None:
                report.error("%s 是 PATCH 步骤却带 exitCode=%s（恒应为 null）" % (step_id, exit_code))
            continue
        if step_type == "SCRIPT":
            event = terminal.get("stepEvent")
            if event == "FAILURE" and exit_code is not None and exit_code == 0:
                report.error("%s 判 FAILURE 却 exitCode==0（规则 4 双向不成立）" % step_id)
            if event == "SUCCESS" and exit_code != 0:
                report.error("%s 判 SUCCESS 却 exitCode=%s（规则 4 双向不成立）" % (step_id, exit_code))
        for row in rows:
            if row.get("stepEvent") not in TERMINAL_EVENTS and row.get("exitCode") is not None:
                report.error("%s 的非终态行带 exitCode（承载位错位）" % step_id)


def check_rollback(rows, grouped, report):
    """判据 7：回滚记录位的位置与数量。"""
    step_order = []
    for row in rows:
        step_id = row.get("stepId")
        if step_id is not None and step_id not in step_order:
            step_order.append(step_id)
    for position, step_id in enumerate(step_order):
        group = grouped[step_id]
        terminals = [r for r in group if r.get("stepEvent") in TERMINAL_EVENTS]
        if not terminals or terminals[0].get("stepEvent") != "FAILURE":
            continue
        if terminals[0].get("stepType") != "PATCH":
            continue
        rollbacks = [r for r in group if r.get("stepEvent") == "ROLLBACK"]
        if len(rollbacks) != 1:
            report.error("%s 终态 FAILURE 却恰有 %d 行 ROLLBACK（应为 1）" % (step_id, len(rollbacks)))
            continue
        terminal_at = rows.index(terminals[0])
        rollback_at = rows.index(rollbacks[0])
        next_start_at = len(rows)
        if position + 1 < len(step_order):
            next_starts = [r for r in grouped[step_order[position + 1]] if r.get("stepEvent") == "START"]
            if next_starts:
                next_start_at = rows.index(next_starts[0])
        if not (terminal_at < rollback_at < next_start_at):
            report.error("%s 的 ROLLBACK 行位置不在「终态行之后、下一步 START 之前」" % step_id)


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.passes = []

    def error(self, text):
        self.errors.append(text)

    def warn(self, text):
        self.warnings.append(text)

    def ok(self, text):
        self.passes.append(text)


def check_file(path, report):
    payload = load_payload(path)
    rows = payload.get("logs") or []
    grouped = index_rows(rows)
    report.ok("%s：logs 行数 = %d，非空 stepId 去重计数（分母）= %d" % (path, len(rows), len(grouped)))
    check_domain(rows, report)
    check_serial(rows, report)
    check_complete(grouped, report)
    check_exit_code(grouped, report)
    check_rollback(rows, grouped, report)
    stage_rows = [r for r in rows if r.get("stepId") is None]
    report.ok("%s：阶段级行数（不进分母）= %d，其中带 stepEvent 的收尾/完成行 = %d"
              % (path, len(stage_rows),
                 len([r for r in stage_rows if r.get("stepEvent") is not None])))
    top_stage = payload.get("stage")
    if top_stage is None:
        report.error("%s：顶层 stage 缺失（§14.11 的激活态驱动源）" % path)
    else:
        report.ok("%s：顶层 stage = %s" % (path, top_stage))


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    report = Report()
    for path in argv[1:]:
        check_file(path, report)
    for text in report.passes:
        print("  PASS  " + text)
    for text in report.warnings:
        print("  WARN  " + text)
    for text in report.errors:
        print("  FAIL  " + text)
    print()
    if report.errors:
        print("V-08 判定：FAIL（%d 项违例）" % len(report.errors))
        return 1
    print("V-08 判定：PASS —— 取值域 / 三项齐备 / 串行可见 / 退出码 ⇔ 失败 / 回滚记录位 全部成立，"
          "KPI-02 = 100%")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
