# 归档文档

本目录存放**已不再维护**的历史文档，仅作溯源参考，不参与日常开发阅读。

## 目录

### `design-specs/`

2026-07 系列的**早期设计 spec**（按日期命名，如扩展雪花 ID、插件扩展存储、部署任务与状态机、实例状态同步、文件服务 SPI、RCON 连接重构等），以及 `doc-consistency-audit-2026-08-23.md` 一致性审计记录。已归档，不在主文档树引用。

### `design-docker/`

早期 Docker 模块设计（`database.md` / `requirement.md` / `ui-design.md`），已归档，不在主文档树引用。

### `implementation-plans/`

2026 年各次重构的**实现计划 / 续接计划**（如扩展存储宽表、L4D2 解耦、雪花 ID 等）。

- 这些计划与 `../design-specs/` 下对应的设计文档内容重叠，内部引用的 `docs/superpowers/specs/` 路径为历史路径（当前 specs 已归档至 `../design-specs/`）。
- 对应设计意图与最终落地均已沉淀到 `../design-specs/` 与 `../design/adr/`，故此处仅保留作为执行过程溯源。

### `issues-archive.json`

早期（v1.0.0）的缺陷跟踪记录，原游离于仓库根目录、未纳入测试文档体系。归档备查，当前缺陷管理以 `../testing/` 下的用例与 E2E 清单为准。
