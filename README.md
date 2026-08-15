# Game Platform Manager

> 游戏服务器统一管理平台 — 面向个人游戏服运维场景的轻量级管理后台

采用前后端分离 + 插件化架构，支持多游戏、多主机的统一管理。

## 核心特性

- **主机纳管** — SSH 连接管理、资源监控、Web 终端
- **游戏部署** — 支持 LinuxGSM / Docker / Docker Compose 三种部署方式
- **实例管理** — 全生命周期、配置管理、文件管理、状态同步
- **插件扩展** — 基于 PF4J 的插件框架，支持游戏增强扩展点与 Wujie 微前端集成
- **RCON 控制台** — 游戏服务器远程命令控制
- **备份还原** — 实例数据备份与恢复
- **操作审计** — 操作日志记录与导出

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.2.5 · MyBatis-Plus · SQLite · Apache MINA SSHD · Docker Java · PF4J |
| 前端 | Vue 3.4 · Vite 5 · Element Plus 2.6 · Pinia · XTerm.js · Wujie 微前端 |
| 数据库 | SQLite（嵌入式，零安装） |

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven 3.8+

### 一键启动（全栈）

```powershell
.\scripts\rebuild-restart-all.ps1
```

支持参数：`-SkipBackendCompile` / `-SkipPlugins` / `-SkipFrontend`

bash 版（Git Bash）：

```bash
bash scripts/start-all.sh
```

支持参数：`--backend-only` / `--frontend-only` / `--skip-compile` / `--skip-plugins` / `--port` / `--db`

### 分别启动

**后端**（默认 8080 端口）

```bash
cd backend
mvn spring-boot:run
```

**前端**（默认 3000 端口，proxy 转发 `/api` 到 8080）

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:3000 即可使用。

## 项目结构

```
game_platform_manger/
├── backend/          # 后端（git submodule，Spring Boot 多模块）
├── frontend/         # 前端（git submodule，Vue 3 + Vite）
├── docs/             # 项目文档（分层组织）
├── scripts/          # 全栈一键脚本
├── .trae/skills/     # AI Skill 文档（插件开发等）
└── AGENTS.md         # AI Agent 协作指南
```

## 文档导航

| 文档 | 说明 |
|------|------|
| [AI Agent 协作指南](AGENTS.md) | 项目总览、工程约定、安全规范（开发者必读） |
| [架构文档](docs/architecture/ARCHITECTURE.md) | 系统架构、模块职责、数据流 |
| [API 接口文档](docs/api/api-doc.md) | REST API 规范与端点说明 |
| [插件开发指南](.trae/skills/gameplatform-plugin-dev/SKILL.md) | PF4J 插件开发完整指南 |
| [UI/UE 设计规范](docs/design/ui-design-spec.md) | 界面设计与交互规范 |
| [ADR 决策记录](docs/design/adr/README.md) | 架构决策记录 |
| [测试文档](docs/testing/) | UI 测试策略、用例模板、E2E 清单 |
| [后端开发指南](backend/AGENTS.md) | 后端模块开发约定 |
| [前端开发指南](frontend/AGENTS.md) | 前端模块开发约定 |

## 插件开发

本平台采用 PF4J 插件框架，支持为不同游戏开发独立的增强插件。完整的插件开发指南位于 [.trae/skills/gameplatform-plugin-dev/](.trae/skills/gameplatform-plugin-dev/SKILL.md)，包含：

- 快速开始与项目结构规范
- 扩展点（`GameEnhancementExtension`）与菜单声明（ADR-0001）
- `ExtensionClient` 持久化与宿主服务面
- 任务中心 SDK（`TaskHandler` / `TaskHandlerExtension`）
- Wujie 微前端集成与三种运行模式
- 参考实现 `examples/plugin-mygame`

## 许可证

本项目为个人使用，未指定开源许可证。
