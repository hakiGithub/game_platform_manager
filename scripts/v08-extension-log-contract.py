#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V-08 机械核对脚本（design.md §10 V-08 / §14.6 规则 1-5；KPI-02、AC-03、AC-16 数据侧）。

判据（全部只读字段，脚本内不出现任何 message 匹配）：
  1. 取值域 = 扩展阶段内的行（不是 logs 全部行）：
       - stepId != null 的行必带 stage == "EXTENSION"
       - 所有 stage == "EXTENSION" 的行在 logs 序列里构成**连续块**（中间夹进一条异 stage
         的行 ⇒ 既存阶段的行被错归进扩展块，或扩展块被切开）
       - 块首行必为进入行形状：stepId == null ∧ stepEvent == null ∧ level == "INFO"
       - 两条 stepId == null 的条件行不得让脚本误报：BR-12 拦截行在取值域内（取 EXTENSION），
         目录不合法说明行在取值域外（归它实际发生的既有阶段，本期 "DEPLOY"）
  2. 统计对象 = stepId != null 的行；分母 = 非空 stepId 的去重计数
       （进入 / 交棒 / 停实例 / BR-12 / 目录不合法 / 收尾 / 阶段完成 七类阶段级行一律不进分母）
  3. 每个非空 stepId 恰有一个 START 行 + 恰有一个终态行（SUCCESS 或 FAILURE），
       且该终态行 elapsedMs != null  —— 三项齐备
  4. KPI-02 = 齐备步骤数 / 分母，目标 100%
  5. 规则 3 串行可见：步骤 N 的终态行之后才允许出现步骤 N+1 的 START 行
  6. 规则 4 退出码的**可判部分**：
       - SCRIPT 终态行 SUCCESS ⇒ exitCode == 0
       - SCRIPT 终态行 FAILURE 且 exitCode 非空 ⇒ exitCode != 0
       - PATCH 终态行、所有非终态行、所有阶段级行 ⇒ exitCode 恒为 null
     **不判的方向**（G2 附加裁定）：FAILURE ∧ exitCode == null 覆盖「超时」与「脚本未开始
     （下载失败 / 摘要不符 / 源缺失 / 非 http(s)）」两类，两者在字段面完全同形，而 AC-03 又禁读
     message ⇒ 该支按豁免清单回写，脚本只数出「有几行落在不可判支」并显式说明，不据此判 FAIL，
     也不得把它读成「已确认超时」。
  7. 规则 5 回滚记录位：某 PATCH 步骤终态为 FAILURE => 该 stepId 下、终态行之后、
       下一步骤 START 之前恰有一行 ROLLBACK
  8. 阶段级行取值域（§14.6 elapsedMs / exitCode 两行）：stepId == null 的行
       - exitCode 恒为 null
       - elapsedMs 非空仅当 stepEvent ∈ {"SUCCESS", "FAILURE"}（收尾两支与阶段完成行）

字段面不可判、因此**不判**的边角（写在这里，不沉默）：
  - 七类阶段级行（进入 / 停实例前置 / 停实例完成 / 交棒 / BR-12 拦截 / 恢复路径 / 致命终止）
    里，除 level 与 stepEvent 之外没有区分位；本脚本只判上面第 1 条给的块首形状，
    不判它们各自的条数与先后。
  - 「目录不合法说明行被实现前移进 EXTENSION 块内」与一条 BR-12 拦截行同形
    （stepId == null ∧ stepEvent == null ∧ stage == "EXTENSION"），字段面区分不了 ——
    钉的是「行归它实际发生的阶段」这条判据，不是字面量（§14.6 v0.3.5 拆分裁定）。
  - 超时 vs 脚本未开始：见第 6 条。

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
    """判据 1：取值域 + 扩展块的边界形状（G2 M-5 补齐的「有牙」部分）。"""
    for row in rows:
        stage = row.get("stage")
        step_id = row.get("stepId")
        if step_id is not None and stage != EXTENSION:
            report.error("stepId=%s 的行 stage=%s，必带 EXTENSION" % (step_id, stage))

    positions = [i for i, r in enumerate(rows) if r.get("stage") == EXTENSION]
    if not positions:
        return
    if positions != list(range(positions[0], positions[-1] + 1)):
        gaps = [i for i in range(positions[0], positions[-1] + 1) if rows[i].get("stage") != EXTENSION]
        report.error("EXTENSION 行不构成连续块：第 %s 行夹在非扩展行之间（既存阶段被卷进扩展块，"
                     "或扩展块被切开）" % ",".join(str(i) for i in gaps[:8]))
    head = rows[positions[0]]
    if head.get("stepId") is not None or head.get("stepEvent") is not None or head.get("level") != "INFO":
        report.error("EXTENSION 块首行形状不合法（应=进入行 stepId/stepEvent 皆空 + level INFO）："
                     "stepId=%s stepEvent=%s level=%s"
                     % (head.get("stepId"), head.get("stepEvent"), head.get("level")))


def check_stage_level_domains(rows, report):
    """判据 8：阶段级行（stepId == null）的 exitCode / elapsedMs 取值域。"""
    for row in rows:
        if row.get("stepId") is not None:
            continue
        if row.get("exitCode") is not None:
            report.error("阶段级行带 exitCode=%s（§14.6：exitCode 仅 SCRIPT 终态行非空）"
                         % row.get("exitCode"))
        if row.get("elapsedMs") is not None and row.get("stepEvent") not in TERMINAL_EVENTS:
            report.error("阶段级行 stepEvent=%s 却带 elapsedMs=%s"
                         "（§14.6：仅 SUCCESS/FAILURE 与阶段完成行非空）"
                         % (row.get("stepEvent"), row.get("elapsedMs")))


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
    """判据 6：退出码承载位的**可判部分**（不可判支显式说明，不静默降级）。"""
    exempt = []
    for step_id, rows in sorted(grouped.items()):
        terminals = [r for r in rows if r.get("stepEvent") in TERMINAL_EVENTS]
        if not terminals:
            continue
        terminal = terminals[0]
        step_type = terminal.get("stepType")
        exit_code = terminal.get("exitCode")
        if step_type == "PATCH":
            if exit_code is not None:
                report.error("%s 是 PATCH 步骤却带 exitCode=%s（恒应为 null）" % (step_id, exit_code))
        elif step_type == "SCRIPT":
            event = terminal.get("stepEvent")
            if event == "SUCCESS" and exit_code != 0:
                report.error("%s 判 SUCCESS 却 exitCode=%s（规则 4：成功 ⇒ 恰为 0）" % (step_id, exit_code))
            if event == "FAILURE" and exit_code is not None and exit_code == 0:
                report.error("%s 判 FAILURE 却 exitCode==0（规则 4：失败 ⇒ 不得为 0）" % step_id)
            if event == "FAILURE" and exit_code is None:
                exempt.append(step_id)
        else:
            report.warn("%s 的终态行 stepType=%s 不在 {PATCH, SCRIPT} 内，退出码判据对它不成立"
                        % (step_id, step_type))
        for row in rows:
            if row.get("stepEvent") not in TERMINAL_EVENTS and row.get("exitCode") is not None:
                report.error("%s 的非终态行带 exitCode（承载位错位）" % step_id)
    if exempt:
        # 附加裁定（G2 M-6 → M-5 第 4 条）：这一支**不是判据**，只是把不可判的范围摊开写清楚
        report.warn("规则 4 的豁免支：%s 判 FAILURE 且 exitCode == null —— 字段面覆盖「超时」与"
                    "「脚本未开始（下载失败 / 摘要不符 / 源缺失 / 非 http(s)）」两类，二者不可区分"
                    "（AC-03 禁读 message）。本脚本不据此判 FAIL，也不得把它读成「已确认超时」。"
                    % ",".join(exempt))


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
    check_stage_level_domains(rows, report)
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
    print("V-08 判定：PASS —— 覆盖面按本文件头的判据清单，不超出：")
    print("  判到：取值域 + 扩展块连续性与块首形状 / 三项齐备与 KPI-02 / 串行可见 / "
          "退出码可判支（SUCCESS⇒0、FAILURE 非空⇒非 0、PATCH 与非终态行与阶段级行恒 null）/ "
          "阶段级行 elapsedMs 域 / 回滚记录位的位置与数量")
    print("  没判（字段面不可判，不做静默降级）：FAILURE ∧ exitCode==null 的「超时 / 脚本未开始」两类、"
          "七类同形阶段级行各自的条数与先后、「目录不合法说明行」被前移进 EXTENSION 块的情形")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
