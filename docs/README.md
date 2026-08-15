# 项目文档

本目录按开源项目标准分层组织，所有文档按用途归类到子目录。

## 目录结构

```
docs/
├── architecture/       # 架构文档（系统设计、模块职责、数据流）
│   └── ARCHITECTURE.md
├── api/                # API 参考文档
│   └── api-doc.md
├── design/             # 设计文档
│   ├── adr/            # 架构决策记录（ADR）
│   │   ├── README.md
│   │   ├── glossary.md
│   │   └── 0001-plugin-menu-ownership.md
│   ├── specs/          # 设计 spec（功能设计文档）
│   ├── docker/         # Docker 模块设计
│   │   ├── requirement.md
│   │   ├── database.md
│   │   └── ui-design.md
│   └── ui-design-spec.md  # UI/UE 设计规范
├── testing/            # 测试文档
│   ├── ui-testing/     # UI 测试策略、用例、E2E 清单
│   ├── deploy-task-status-machine-issues.md
│   └── deploy-task-status-machine-ui-test-cases.md
└── archive/            # 归档文档（历史计划，不再维护）
    └── plans/          # 迭代实施计划（已实现或废弃）
```

## 文档分层说明

| 层级 | 目录 | 受众 | 说明 |
|------|------|------|------|
| 架构 | `architecture/` | 全体开发者 | 系统整体架构、模块划分、数据流 |
| API 参考 | `api/` | 前后端开发者 | REST API 端点、请求/响应格式 |
| 设计决策 | `design/adr/` | 架构维护者 | 具有长期影响的技术决策记录 |
| 设计文档 | `design/specs/` | 功能开发者 | 具体功能的设计方案 |
| UI 设计 | `design/` | 前端开发者 | 界面设计规范与交互稿 |
| 测试 | `testing/` | QA / 开发者 | 测试策略、用例模板、验收清单 |
| 归档 | `archive/` | — | 历史实施计划，仅作参考 |

## 开发者入口

新加入项目的开发者请按以下顺序阅读：

1. [根目录 README.md](../README.md) — 项目总览与快速开始
2. [AGENTS.md](../AGENTS.md) — AI Agent 协作指南、工程约定
3. [架构文档](architecture/ARCHITECTURE.md) — 系统架构
4. [API 文档](api/api-doc.md) — 接口规范
5. [插件开发指南](../.trae/skills/gameplatform-plugin-dev/SKILL.md) — 如需开发插件

## 维护约定

- 新增功能时同步更新 `api/` 和 `testing/` 对应文档
- 架构变更须新建 ADR 记录（参见 `design/adr/README.md` 写作约定）
- Bug 修复在 `testing/ui-testing/07-e2e-checklist.md` 中补充回归用例
- 实施计划完成后直接归档删除，不在主文档树保留（避免文档膨胀）
