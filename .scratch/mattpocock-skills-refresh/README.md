# Matt Pocock Skills 刷新与接入

来源：2026-10-19 对 `mattpocock/skills` 的版本核对。

## 目标

- 把项目内已采用的 skill 固定到上游 `49dd158d`（`v1.3.1`）。
- 新增 `implement-spec`、`pr`、`retro`。
- 移除上游已删除的 `resolving-merge-conflicts`。
- 对齐 `GLOSSARY.md` 约定。
- 给项目维护者留下中文主线流程与并行 ticket 使用指南。

## Tickets

| 票 | 一句话 | Blocked by |
|----|--------|------------|
| [01](issues/01-pin-upstream-and-sync-project-copies.md) | 固定上游 commit，刷新 lock 与项目级 skill 副本 | None |
| [02](issues/02-migrate-glossary-naming.md) | 把 `CONTEXT.md` 术语源迁移为 `GLOSSARY.md`，保留兼容指针 | 01 |
| [03](issues/03-write-usage-and-parallel-runbook.md) | 写中文使用指南与并行执行 runbook | None |
| [04](issues/04-verify-refresh-and-agent-integration.md) | 验证 lock、副本、agent 可发现性与文档接入 | 01, 02, 03 |

## 第一批可并行 frontier

- 票 01：版本刷新，是后续验证与命名迁移的底座。
- 票 03：使用指南，可基于已确认的 1.3.1 清单独立编写。

票 02 在 01 完成后开始。票 04 在其余三票完成后统一收口。

## 推荐执行方式

```text
$implement-spec .scratch/mattpocock-skills-refresh/spec.md
```

只处理单票时：

```text
$implement .scratch/mattpocock-skills-refresh/issues/01-pin-upstream-and-sync-project-copies.md
```

本轮只记录 ticket，不同步 `.agents/` 或 `.claude/`，等待后续并行执行。

## 执行顺序

1. 同时领取 01 与 03。
2. 01 完成后领取 02。
3. 02 完成后领取 04。

不要并行修改 `skills-lock.json` 与 `GLOSSARY.md` 的同一批次，否则验证票无法区分“上游副本漂移”和“术语入口迁移”。
