# 02: 把领域术语源迁移为 GLOSSARY.md

Status: resolved
Type: task
Blocked by: 01

## 要交付的行为

上游新版工程 skill 能自动在仓库根目录找到领域术语；现有链接与历史文档仍能理解旧名已迁移。仓库只有一个权威术语源，不再让 `CONTEXT.md` 与 `GLOSSARY.md` 各自演化。

## 验收

- [x] 现有术语内容迁入根目录 `GLOSSARY.md`。
- [x] `CONTEXT.md` 保留为简短兼容指针，指向 `GLOSSARY.md`，不复制术语正文。
- [x] `docs/agents/domain.md` 把权威读取入口改为 `GLOSSARY.md`，继续声明 ADR 位于 `docs/design/adr/`。
- [x] 根 `AGENTS.md` 的 `Domain docs` 指引改为读取 `GLOSSARY.md`，保留单上下文布局说明。
- [x] 活跃规则、README、agent 配置不再把 `CONTEXT.md` 当权威术语源；历史 ADR、ticket 与归档内容可保留原引用。
- [x] 用 `rg` 审计 `CONTEXT.md` 命中，逐项确认属于兼容指针、历史记录或需要迁移的活跃指引。

## 验证

- 从根目录按 `GLOSSARY.md` 能读到现有全部领域术语。
- 访问 `CONTEXT.md` 会明确指向新文件。
- `$domain-modeling`、`$grill-with-docs`、`$wait-what` 的读取路径与仓库布局一致。

## Answer

- 根目录 `GLOSSARY.md` 承载原有 71 个术语条目，`CONTEXT.md` 仅保留兼容指针。`docs/agents/domain.md`、根 `AGENTS.md` 和活跃源码注释已切换到新入口。
- `rg` 剩余旧路径命中仅来自历史 ADR 与测试研究记录。
