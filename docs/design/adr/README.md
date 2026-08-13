# Architecture Decision Records (ADR)

> 本目录记录 Game Platform Manager 项目中具有长期影响的技术决策。
>
> 每条 ADR 记录：背景（Context）、决策（Decision）、后果（Consequences）、备选方案（Alternatives）。
> ADR 一旦写入即冻结为历史记录；如需推翻，新建 ADR 引用前序编号并标注 Supersedes。

## ADR 索引

| 编号 | 标题 | 状态 | 日期 |
|------|------|------|------|
| [0001](0001-plugin-menu-ownership.md) | 插件菜单归属与 `getMenus()` 扩展点 | Accepted | 2026-08-02 |
| [0002](0002-main-app-plugin-scope-isolation.md) | 主应用与插件范围隔离规约 | Accepted | 2026-08-03 |
| [0003](0003-deprecate-plugin-l4d2-standalone.md) | 废弃 plugin-l4d2-standalone 模块 | Accepted | 2026-08-03 |
| [0004](0004-host-lan-identification.md) | 主机局域网标识（isLanHost）引入 | Accepted | 2026-08-09 |
| [0005](0005-run-status-vocabulary-unification.md) | run_status 状态词汇表统一（InstanceStatus 唯一权威） | Accepted | 2026-08-13 |

## 术语表

参见 [glossary.md](glossary.md)。

## 写作约定

- 文件名：`NNNN-kebab-case-title.md`（NNNN 为四位顺序号，从 0001 起）
- 状态取值：`Proposed` / `Accepted` / `Superseded by NNNN` / `Deprecated`
- 每条 ADR 控制在 300 行以内，长论述拆到 spec 文档并在 Context 中引用
- 决策以"主语 + 谓语 + 宾语 + 理由"句式表述，避免"我们决定…"的模糊表述

## 相关文档

- [插件开发指南](../../../.trae/skills/gameplatform-plugin-dev/SKILL.md)
- [架构文档](../../architecture/ARCHITECTURE.md)
- [设计 spec 文档目录](../specs/)
