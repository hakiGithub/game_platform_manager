// 票 03 的单元测试：用一份手写 Playwright JSON 报告验证 summary 归一化逻辑。
// 跑法：node --test e2e/support/run-summary.test.mjs
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { buildSummary, renderMarkdown } from "./run-summary.mjs";

/** 造一份最小可用的 Playwright JSON 报告 */
function writeReport(specs) {
  const dir = mkdtempSync(join(tmpdir(), "e2e-summary-"));
  const jsonPath = join(dir, "report.json");
  writeFileSync(
    jsonPath,
    JSON.stringify({
      suites: [
        {
          title: "suite",
          file: "plugins/demo/demo.spec.js",
          specs: specs.map((spec, i) => ({
            title: spec.title,
            ok: spec.status === "passed",
            line: 10 + i,
            file: "plugins/demo/demo.spec.js",
            tests: [
              {
                status:
                  spec.status === "passed"
                    ? "expected"
                    : spec.status === "skipped"
                      ? "skipped"
                      : "unexpected",
                annotations: spec.skipReason
                  ? [{ type: "skip", description: spec.skipReason }]
                  : [],
                results: spec.status === "didNotRun"
                  ? []
                  : [
                      {
                        status: spec.status,
                        duration: 1234,
                        error: spec.error ? { message: spec.error } : undefined,
                        attachments: spec.attachments ?? [],
                        annotations: [],
                      },
                    ],
              },
            ],
          })),
        },
      ],
    }),
    "utf8",
  );
  return jsonPath;
}

describe("buildSummary", () => {
  it("把 passed / failed / skipped / didNotRun 分别归位", () => {
    const jsonPath = writeReport([
      { title: "ok", status: "passed" },
      { title: "bad", status: "failed", error: "expect(received).toBeVisible()" },
      { title: "skip", status: "skipped", skipReason: "SKIP (no test host)" },
      { title: "never", status: "didNotRun" },
    ]);

    const summary = buildSummary({
      reports: [{ jsonPath }],
      mode: "docker",
      executor: "wsl",
      startedAt: 1000,
      finishedAt: 5000,
      exitCode: 1,
    });

    assert.deepEqual(summary.totals, {
      passed: 1,
      failed: 1,
      skipped: 1,
      didNotRun: 1,
    });
    assert.equal(summary.verdict, "FAIL");
    assert.equal(summary.durationMs, 4000);
    assert.equal(summary.failures[0].title, "bad");
    assert.match(summary.failures[0].error, /toBeVisible/);
    assert.equal(summary.skips[0].reason, "SKIP (no test host)");
    assert.equal(summary.didNotRun[0].title, "never");
    assert.deepEqual(summary.groups, [], "单报告不应产生分组");
  });

  it("全部通过且退出码 0 时判定 PASS", () => {
    const jsonPath = writeReport([{ title: "ok", status: "passed" }]);
    const summary = buildSummary({
      reports: [{ jsonPath }],
      mode: "managed",
      executor: "local",
      startedAt: 0,
      exitCode: 0,
    });
    assert.equal(summary.verdict, "PASS");
  });

  it("JSON 丢用例但退出码非 0 时仍判 FAIL", () => {
    const jsonPath = writeReport([{ title: "ok", status: "passed" }]);
    const summary = buildSummary({
      reports: [{ jsonPath }],
      mode: "managed",
      executor: "local",
      startedAt: 0,
      exitCode: 1,
    });
    assert.equal(summary.verdict, "FAIL", "退出码必须能压过 JSON 计数");
  });

  it("多份报告时聚合出分组粒度", () => {
    const good = writeReport([{ title: "a", status: "passed" }]);
    const bad = writeReport([
      { title: "b", status: "failed", error: "boom" },
    ]);
    const summary = buildSummary({
      reports: [
        { name: "冒烟与导航", jsonPath: good },
        { name: "插件子应用", jsonPath: bad },
      ],
      mode: "managed",
      executor: "local",
      startedAt: 0,
      exitCode: 1,
    });

    assert.equal(summary.groups.length, 2);
    assert.equal(summary.groups[0].verdict, "PASS");
    assert.equal(summary.groups[1].verdict, "FAIL");
    assert.equal(summary.totals.passed, 1);
    assert.equal(summary.totals.failed, 1);
  });

  it("报告文件缺失时退化为空计数而不抛错", () => {
    const summary = buildSummary({
      reports: [{ jsonPath: join(tmpdir(), "definitely-missing.json") }],
      mode: "attach",
      executor: "wsl",
      startedAt: 0,
      exitCode: 0,
    });
    assert.deepEqual(summary.totals, {
      passed: 0,
      failed: 0,
      skipped: 0,
      didNotRun: 0,
    });
  });

  it("看门狗跳过的分组出现在 groups 与 Markdown 里", () => {
    const good = writeReport([{ title: "a", status: "passed" }]);
    const summary = buildSummary({
      reports: [{ name: "冒烟与导航", jsonPath: good }],
      mode: "managed",
      executor: "local",
      startedAt: 0,
      exitCode: 1,
      skippedGroups: [{ name: "演练腿", reason: "整轮看门狗超时" }],
    });

    const skipped = summary.groups.find((g) => g.verdict === "SKIP");
    assert.ok(skipped, "超时分组必须以 SKIP 记入 groups");
    assert.equal(skipped.name, "演练腿");
    assert.match(renderMarkdown(summary), /分组「演练腿」未执行/);
  });
});

describe("renderMarkdown", () => {
  it("把失败用例的产物路径写进人读摘要", () => {
    const jsonPath = writeReport([
      {
        title: "渲染失败",
        status: "failed",
        error: "locator not found",
        attachments: [
          {
            name: "error-context",
            path: "D:/repo/frontend/e2e/.artifacts/x/error-context.md",
          },
          { name: "trace", path: "D:/repo/frontend/e2e/.artifacts/x/trace.zip" },
        ],
      },
    ]);
    const summary = buildSummary({
      reports: [{ jsonPath }],
      mode: "docker",
      executor: "wsl",
      startedAt: 0,
      exitCode: 1,
    });
    const md = renderMarkdown(summary);

    assert.match(md, /E2E 结果 — FAIL/);
    assert.match(md, /渲染失败/);
    assert.match(md, /error-context\.md/);
    assert.match(md, /trace\.zip/);
  });
});
