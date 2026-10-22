# 03 — E2E 结果落盘与交付

**Status:** resolved
**Type:** task
**Blocked by:** 01

## 任务

让"跑完把结果给我"变成读一个文件，而不是守终端或翻 HTML 报告。

### 产物

`frontend/e2e/.last-run/`（gitignored）下两个文件，两种执行模式（本地 / WSL）都产出：

- **`summary.json`** — 给脚本与 Agent 读：
  ```json
  {
    "verdict": "PASS|FAIL",
    "startedAt": "...", "finishedAt": "...", "durationMs": 0,
    "mode": "managed|attach", "executor": "local|wsl",
    "totals": { "passed": 0, "failed": 0, "skipped": 0, "didNotRun": 0 },
    "failures": [
      { "file": "e2e/plugins/l4d2/...", "title": "...",
        "error": "...", "errorContext": "e2e/.artifacts/.../error-context.md",
        "screenshot": "...", "trace": "..." }
    ],
    "skips": [ { "file": "...", "title": "...", "reason": "SKIP (no test host)" } ],
    "report": "e2e/.report/index.html"
  }
  ```
- **`summary.md`** — 给人读：总数/通过/失败/跳过 + 失败清单（用例名 + 错误一行摘要 +
  产物路径）+ 显式 SKIP 及原因 + HTML 报告路径。

### 要点

1. 失败明细从 Playwright 输出解析不够可靠 —— 优先读 `e2e/.artifacts/` 下的
   `error-context.md`（Playwright 已自动生成），把路径写进 json。
2. 整轮（`--round`）模式同样产出，按分组聚合。
3. runner 结束前打印 `summary.md` 路径，让人知道去哪看。
4. 落盘失败（如磁盘/权限）不能影响主流程退出码 —— 记 warning 即可。

## 验收

- [x] 本地与 WSL 两种模式都产出 `summary.json` / `summary.md`。
- [x] 有一次失败时，summary 里能直接看到失败用例名与 error-context 路径。
- [x] 整轮模式产出的 summary 含分组粒度结果。
- [x] 有显式 SKIP 时，summary 里能看到 SKIP 原因。

## 落地结果（2026-10-22）

新增 `frontend/e2e/support/run-summary.mjs`（归一化 + Markdown 渲染 + 落盘）
与 `summarize-cli.mjs`（给不经过 runner 的容器路径用）。三条执行路径都接上了：

| 路径 | 落盘方式 | 实测 |
|------|---------|------|
| `npm run e2e`（受管） | runner 透传 `E2E_JSON_REPORT`，收尾调 `finalizeSummary` | PASS/FAIL 均产出 |
| `npm run e2e -- --attach` | 同上 | 8/8 auth 用例 PASS，`mode=attach / executor=local` |
| `npm run e2e:round` | 每组 `--reporter=list,blob,json`，按 `PLAYWRIGHT_JSON_OUTPUT_DIR/NAME` 落盘，最后聚合 | `groups[]` 含每组判定 |
| `bash scripts/e2e-docker-run.sh` | `e2e-docker-pw.sh` 包一层，跑完调 `summarize-cli.mjs` | 8/8 auth 用例 PASS，`mode=docker / executor=wsl` |

### 关键决策

1. **数据源用 Playwright 官方 JSON reporter，不解析终端输出。** 终端只有计数，
   没有"哪条失败、产物在哪"；JSON 里每个 result 的 `attachments` 直接带
   `error-context.md` / 截图 / trace 的真实路径。
2. **json reporter 是条件挂载的。** `playwright.config.js` 只在 `E2E_JSON_REPORT`
   有值时追加，日常 `npx playwright test` 不往 `.last-run` 写文件。
3. **`verdict` = JSON 计数 AND 退出码。** 起栈失败时 JSON 里一条用例都没有，
   只看计数会误判 PASS —— 这个坑在本地 attach 到不存在后端时实测踩到过。
   现在这种情形写 `runnerError`，Markdown 里标「**未跑起来**」。
4. **上一轮 summary 先删再跑。** 否则本轮崩溃时旧 summary 会被误读成最新结果。
5. **落盘失败只 warning。** 磁盘/权限异常绝不改变退出码。
6. **看门狗跳过的分组显式记 SKIP**（`groups[].verdict = "SKIP"` + `reason`），
   不能因为"没有 JSON"就无声消失。

### 验证

- 框架层单测 10 条：`npm run e2e:support:test`（status 归一化、分组聚合、
  退出码压过计数、报告缺失降级、Markdown 产物路径、看门狗 SKIP）。
- 造一条必失用例实跑，summary 里拿到：
  `失败：临时失败用例...` + `上下文：e2e/.artifacts/.../error-context.md`
  + 截图 + trace 三件套（验证后已删除该临时用例）。
- 顺手修了一个 ANSI 污染：Playwright 的 `error.message` 带颜色码，
  直接落盘会变成乱码方块，现在统一清洗后再写。

