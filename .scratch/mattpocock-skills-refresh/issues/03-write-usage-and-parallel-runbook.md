# 03: 编写中文使用指南与并行执行 runbook

Status: resolved
Type: task
Blocked by: None

## 要交付的行为

项目维护者能在一页内看懂什么时候使用哪个 skill，如何从想法走到 spec 和 tickets，如何选择单票 `/implement` 或整 spec `/implement-spec`，以及本仓库的 `.scratch` local tracker、`GLOSSARY.md`、`docs/design/adr/` 特例。

## 验收

- [x] 新增中文使用指南，覆盖主线：`grill-with-docs` → `to-spec` → `to-tickets` → `implement-spec` 或 `implement` → `code-review` → `pr` → `retro`。
- [x] 用表格说明至少这些 skill 的适用场景：`ask-matt`、`grill-with-docs`、`to-spec`、`to-tickets`、`implement`、`implement-spec`、`tdd`、`code-review`、`pr`、`retro`、`triage`、`diagnosing-bugs`、`wayfinder`、`improve-codebase-architecture`。
- [x] 明确 local tracker 的一票一文件约定、`Status:` 与 `Blocked by:` 语义、ready frontier 的领取规则。
- [x] 给出并排执行的推荐做法：先完成前置票，再让无依赖票并行；每张实现票按 task graph 合并到同一 integration branch。
- [x] 说明 `implement` 与 `implement-spec` 的取舍：单票或小改走前者的 fresh context；整份 spec 的并行 frontier 走后者。
- [x] 说明 `resolving-merge-conflicts` 已下线，遇到冲突由 agent 按当前 git 状态、提交意图与自动化检查直接解决。
- [x] 说明更新与漂移检查入口，不复制上游 skill 全文。
- [x] 根 `AGENTS.md` 只增加一个导航指针，不重复整页内容。

## 完成证据

- 指南文件：`docs/agents/skill-workflow-runbook.md`
- `AGENTS.md` 最小导航指针。
- 首次使用短路径与整 spec 并行短路径均包含在指南。

## Answer

- 实现提交 `62bce0b4`，合入 integration branch 后为 `b625330`。
- PowerShell 检查块可解析；必需 skill 覆盖 14/14；文档路径存在；`git diff --check` 通过。
