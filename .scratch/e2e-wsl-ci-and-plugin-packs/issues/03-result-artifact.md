# 03 — E2E 结果落盘与交付

**Status:** ready-for-agent
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

- [ ] 本地与 WSL 两种模式都产出 `summary.json` / `summary.md`。
- [ ] 有一次失败时，summary 里能直接看到失败用例名与 error-context 路径。
- [ ] 整轮模式产出的 summary 含分组粒度结果。
- [ ] 有显式 SKIP 时，summary 里能看到 SKIP 原因。

