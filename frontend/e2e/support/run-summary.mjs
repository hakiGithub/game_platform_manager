// E2E 结果落盘：把 Playwright 的 JSON 报告压成 e2e/.last-run/summary.{json,md}
//
// 为什么读 JSON 报告而不是解析终端输出：终端输出只有计数，没有"哪条用例失败、
// 产物在哪个文件"。JSON reporter 是 Playwright 官方的结构化输出，每条用例的
// attachments 里就带着 error-context.md / 截图 / trace 的真实路径 ——
// 正好是票 03 要写进 summary 的东西，不用自己猜目录名。
//
// 落盘失败（磁盘满、只读、并发写）一律降级为 warning，绝不影响主流程退出码。
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const FRONTEND_DIR = resolve(E2E_DIR, "..");
export const LAST_RUN_DIR = join(E2E_DIR, ".last-run");
export const DEFAULT_JSON_REPORT = join(LAST_RUN_DIR, "playwright-report.json");

/** Playwright result.status → 本模块的四种归一化状态 */
function normalizeStatus(status) {
  switch (status) {
    case "passed":
      return "passed";
    case "failed":
    case "timedOut":
      return "failed";
    case "skipped":
      return "skipped";
    default:
      return "didNotRun";
  }
}

/** 把绝对路径转成相对仓库的写法，summary 里贴给人看更短 */
function rel(p) {
  if (!p) return null;
  try {
    return relative(FRONTEND_DIR, p).replaceAll("\\", "/");
  } catch {
    return p;
  }
}

function firstLine(text) {
  if (!text) return null;
  // Playwright 的 error.message 带 ANSI 颜色码，直接写进 summary 会变成乱码方块
  const clean = String(text).replaceAll(/\u001b\[[0-9;]*m/g, "");
  const line = clean
    .split("\n")
    .map((l) => l.trim())
    .find((l) => l.length > 0);
  return line ? line.slice(0, 300) : null;
}

/**
 * 递归展开 Playwright JSON 报告的 suite 树，产出扁平的用例记录。
 * spec 的 file 只在最外层出现，深层 suite 继承父级的 file。
 */
function walkSuites(suite, inheritedFile, acc) {
  const file = suite.file ?? inheritedFile ?? null;
  for (const spec of suite.specs ?? []) {
    const record = {
      file: rel(spec.file ?? file),
      title: spec.title,
      line: spec.line ?? null,
      // 用 null 起始：Playwright 的 tests 数组里没结果的 spec 才是 didNotRun，
      // 若这里先给 "didNotRun"，后面的 rank 比较会把所有 passed 都挡掉。
      status: null,
      skipReason: null,
      error: null,
      errorContext: null,
      screenshot: null,
      trace: null,
      durationMs: 0,
    };

    for (const test of spec.tests ?? []) {
      const results = test.results ?? [];
      const last = results.at(-1);
      if (!last) continue;

      const status = normalizeStatus(last.status);
      // 同一 spec 多条 test（多 project）时取最严重的一条
      const rank = { passed: 0, skipped: 1, didNotRun: 2, failed: 3 };
      if (record.status === null || rank[status] >= rank[record.status]) {
        record.status = status;
        record.durationMs += last.duration ?? 0;
        record.error = firstLine(last.error?.message);

        const attachments = last.attachments ?? [];
        const byName = (name) => {
          const hit = attachments.find((a) => a.name === name && a.path);
          return hit ? rel(hit.path) : null;
        };
        record.errorContext =
          byName("error-context") ??
          // Playwright 也会把 error-context 写成附件但名字随版本变，兜底再找一次
          (attachments.find((a) => a.path?.endsWith("error-context.md"))
            ? rel(attachments.find((a) => a.path.endsWith("error-context.md")).path)
            : null);
        record.screenshot = byName("screenshot");
        record.trace = byName("trace");
      }

      const annotations = [
        ...(spec.annotations ?? []),
        ...(test.annotations ?? []),
        ...(last.annotations ?? []),
      ];
      const skip = annotations.find((a) => a.type === "skip");
      if (skip) record.skipReason = skip.description || "SKIP";
    }

    record.status = record.status ?? "didNotRun";
    acc.push(record);
  }

  for (const child of suite.suites ?? []) walkSuites(child, file, acc);
}

/** 读一个 Playwright JSON 报告文件 → 扁平用例记录数组 */
export function readPlaywrightReport(jsonPath) {
  if (!existsSync(jsonPath)) return null;
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(jsonPath, "utf8"));
  } catch {
    return null;
  }
  const acc = [];
  for (const suite of parsed.suites ?? []) walkSuites(suite, null, acc);
  return acc;
}

function tallyOf(records) {
  const totals = { passed: 0, failed: 0, skipped: 0, didNotRun: 0 };
  for (const r of records) totals[r.status] += 1;
  return totals;
}

/**
 * @param {object} opts
 * @param {Array<{name?: string, jsonPath: string}>} opts.reports 一个或多个 JSON 报告；>1 视为整轮分组
 * @param {"managed"|"attach"|"docker"} opts.mode
 * @param {"local"|"wsl"} opts.executor
 * @param {number} opts.startedAt epoch ms
 * @param {number} [opts.finishedAt] epoch ms
 * @param {number} [opts.exitCode] Playwright 进程退出码（JSON 丢用例时用来兜底判 FAIL）
 * @param {string} [opts.runnerError] runner 自身失败（起栈没起来、Playwright 没产出 JSON）
 * @param {Array<{name: string, reason: string}>} [opts.skippedGroups] 整轮里因看门狗超时没跑的分组
 */
export function buildSummary({
  reports,
  mode,
  executor,
  startedAt,
  finishedAt = Date.now(),
  exitCode = 0,
  runnerError = null,
  skippedGroups = [],
}) {
  const groups = [];
  const all = [];

  for (const report of reports) {
    const records = readPlaywrightReport(report.jsonPath) ?? [];
    all.push(...records);
    if (reports.length > 1) {
      const totals = tallyOf(records);
      groups.push({
        name: report.name ?? report.jsonPath,
        verdict: totals.failed === 0 && totals.didNotRun === 0 ? "PASS" : "FAIL",
        totals,
      });
    }
  }

  // 整轮看门狗跳过的分组：显式记成 SKIP，不能因为"没有 JSON"就无声消失
  for (const group of skippedGroups) {
    groups.push({
      name: group.name,
      verdict: "SKIP",
      totals: { passed: 0, failed: 0, skipped: 0, didNotRun: 0 },
      reason: group.reason,
    });
  }

  const totals = tallyOf(all);
  const verdict =
    totals.failed === 0 && totals.didNotRun === 0 && exitCode === 0
      ? "PASS"
      : "FAIL";

  const summary = {
    verdict,
    startedAt: new Date(startedAt).toISOString(),
    finishedAt: new Date(finishedAt).toISOString(),
    durationMs: finishedAt - startedAt,
    mode,
    executor,
    exitCode,
    totals,
    runnerError,
    failures: all
      .filter((r) => r.status === "failed")
      .map((r) => ({
        file: r.file,
        title: r.title,
        error: r.error,
        errorContext: r.errorContext,
        screenshot: r.screenshot,
        trace: r.trace,
      })),
    skips: all
      .filter((r) => r.status === "skipped")
      .map((r) => ({
        file: r.file,
        title: r.title,
        reason: r.skipReason,
      })),
    didNotRun: all
      .filter((r) => r.status === "didNotRun")
      .map((r) => ({ file: r.file, title: r.title })),
    groups,
    report: "e2e/.report/index.html",
  };
  return summary;
}

export function renderMarkdown(summary) {
  const { totals } = summary;
  const total = totals.passed + totals.failed + totals.skipped + totals.didNotRun;
  const lines = [];

  lines.push(`# E2E 结果 — ${summary.verdict}`);
  lines.push("");
  lines.push(`- 判定：**${summary.verdict}**`);
  lines.push(
    `- 时间：${summary.startedAt} → ${summary.finishedAt}（${Math.round(summary.durationMs / 1000)}s）`,
  );
  lines.push(`- 模式：${summary.mode} / ${summary.executor}`);
  lines.push(
    `- 总数：${total} = 通过 ${totals.passed} + 失败 ${totals.failed} + 跳过 ${totals.skipped}` +
      (totals.didNotRun ? ` + 未执行 ${totals.didNotRun}` : ""),
  );
  if (summary.runnerError) {
    // 起栈失败 / Playwright 没产出报告时，总数会是 0 —— 必须说清"没跑起来"而不是"跑过了"
    lines.push(`- **未跑起来**：${summary.runnerError}`);
  }
  lines.push("");

  if (summary.groups.length > 0) {
    lines.push("## 分组");
    lines.push("");
    lines.push("| 分组 | 判定 | 通过 | 失败 | 跳过 | 未执行 |");
    lines.push("|------|------|------|------|------|--------|");
    for (const g of summary.groups) {
      if (g.verdict === "SKIP") {
        lines.push(
          `| ${g.name} | ${g.verdict} | - | - | - | - |`,
        );
        continue;
      }
      lines.push(
        `| ${g.name} | ${g.verdict} | ${g.totals.passed} | ${g.totals.failed} | ${g.totals.skipped} | ${g.totals.didNotRun} |`,
      );
    }
    lines.push("");
    const skippedByWatchdog = summary.groups.filter((g) => g.reason);
    if (skippedByWatchdog.length > 0) {
      for (const g of skippedByWatchdog) {
        lines.push(`- 分组「${g.name}」未执行：${g.reason}`);
      }
      lines.push("");
    }
  }

  lines.push("## 失败");
  lines.push("");
  if (summary.failures.length === 0) {
    lines.push("无。");
  } else {
    summary.failures.forEach((f, i) => {
      lines.push(`${i + 1}. **${f.title}** — \`${f.file}\``);
      if (f.error) lines.push(`   - 错误：${f.error}`);
      if (f.errorContext) lines.push(`   - 上下文：\`${f.errorContext}\``);
      if (f.screenshot) lines.push(`   - 截图：\`${f.screenshot}\``);
      if (f.trace) lines.push(`   - trace：\`${f.trace}\``);
    });
  }
  lines.push("");

  lines.push("## 显式 SKIP");
  lines.push("");
  if (summary.skips.length === 0) {
    lines.push("无。");
  } else {
    const byReason = new Map();
    for (const s of summary.skips) {
      const key = s.reason ?? "(未注明)";
      byReason.set(key, (byReason.get(key) ?? 0) + 1);
    }
    for (const [reason, count] of byReason) {
      lines.push(`- ${reason} — ${count} 条`);
    }
  }
  lines.push("");

  if (summary.didNotRun.length > 0) {
    lines.push("## 未执行");
    lines.push("");
    for (const d of summary.didNotRun) lines.push(`- ${d.title} — \`${d.file}\``);
    lines.push("");
  }

  lines.push(`HTML 报告：\`${summary.report}\`（\`npm run e2e:report\`）`);
  lines.push("");
  return lines.join("\n");
}

/**
 * 落盘 summary.json + summary.md。
 * 返回 { jsonPath, mdPath } | null（失败时 null，只打 warning）。
 */
export function writeSummary(summary) {
  try {
    mkdirSync(LAST_RUN_DIR, { recursive: true });
    const jsonPath = join(LAST_RUN_DIR, "summary.json");
    const mdPath = join(LAST_RUN_DIR, "summary.md");
    writeFileSync(jsonPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
    writeFileSync(mdPath, renderMarkdown(summary), "utf8");
    return { jsonPath, mdPath };
  } catch (error) {
    console.warn(`[e2e-summary] WARN 结果落盘失败（不影响退出码）: ${error.message}`);
    return null;
  }
}

/** 一步到位：构建 + 落盘 + 回显路径 */
export function finalizeSummary(opts) {
  const summary = buildSummary(opts);
  const written = writeSummary(summary);
  if (written) {
    console.log(`[e2e-summary] 判定 ${summary.verdict}；结果已写入 ${rel(written.mdPath)}`);
  }
  return summary;
}
