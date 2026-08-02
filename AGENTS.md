# Game Platform Manager - 项目总览

> AI Agent 协作指南 - 根目录

---

## 项目概述

游戏服务器统一管理平台是一个面向个人游戏服运维场景的轻量级管理后台，采用前后端分离 + 插件化架构，支持多游戏、多主机的统一管理。

### 核心功能
- **主机纳管**: SSH连接管理、资源监控、Web终端
- **游戏部署**: 支持 LinuxGSM/Docker/Docker Compose 三种部署方式
- **实例管理**: 游戏实例生命周期管理、配置管理、文件管理、状态同步
- **插件扩展**: PF4J插件框架，支持游戏增强扩展点与微前端集成
- **RCON 控制台**: 游戏服务器远程命令控制
- **备份还原**: 实例数据备份与恢复
- **操作审计**: 操作日志记录与导出

---

## 技术栈概览

### 后端 (Backend)
| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.5 | 核心框架 |
| Spring Security | 6.x | 安全认证 |
| Spring WebSocket | 6.x | 实时通信 |
| MyBatis-Plus | 3.5.6 | ORM框架 |
| SQLite | 3.45.2.0 | 嵌入式数据库 |
| Apache MINA SSHD | 2.12.1 | SSH连接 |
| Docker Java | 3.3.4 | Docker API |
| PF4J | 3.10.0 | 插件框架 |
| JWT | 0.12.5 | 令牌认证 |
| Hutool | 5.8.26 | 工具类库 |

### 前端 (Frontend)
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.4.21 | 前端框架 |
| Vue Router | 4.3.0 | 路由管理 |
| Pinia | 2.1.7 | 状态管理 |
| Element Plus | 2.6.1 | UI组件库 |
| Axios | 1.6.8 | HTTP请求 |
| XTerm.js | 5.3.0 | Web终端 |
| Wujie | - | 微前端插件集成 |
| Vite | 5.2.0 | 构建工具 |
| Vitest | 1.4.0 | 单元/组件测试 |
| Playwright | - | E2E / UI 自动化 |

---

## 项目结构

```
game_platform_manger/
├── backend/                          # 后端项目 (Spring Boot 多模块)
│   ├── api/                          # API 契约模块 (DTO/VO)
│   ├── core/                         # 核心应用模块 (启动类、控制器、服务)
│   ├── plugin/                       # 插件 SDK 模块 (扩展点、服务接口)
│   ├── plugin-l4d2/                  # L4D2 游戏增强插件
│   │   ├── frontend/                 # 插件前端 (Vue 3 + Vite)
│   │   ├── plugin-l4d2-core/         # 插件核心 JAR
│   │   └── plugin-l4d2-standalone/   # 插件独立运行模式
│   ├── scripts/                      # 后端重启脚本
│   └── AGENTS.md                     # 后端开发指南
│
├── frontend/                         # 主前端项目 (Vue 3 + Vite)
│   ├── src/
│   │   ├── api/                      # API接口
│   │   ├── components/               # 公共组件
│   │   ├── layouts/                  # 布局组件
│   │   ├── router/                   # 路由配置
│   │   ├── stores/                   # Pinia状态管理
│   │   ├── styles/                   # 样式文件
│   │   ├── tests/                    # 测试文件
│   │   ├── App.vue                   # 根组件
│   │   └── main.js                   # 入口文件
│   ├── scripts/                      # 前端重启脚本
│   ├── package.json                  # NPM配置
│   └── AGENTS.md                     # 前端开发指南
│
├── docs/                             # 文档（分层组织）
│   ├── architecture/                 # 架构文档
│   ├── api/                          # API 接口文档
│   ├── design/                       # 设计文档（adr/ specs/ docker/ ui-design-spec）
│   ├── testing/                      # 测试文档（ui-testing/ + 用例）
│   └── archive/                      # 归档文档（历史实施计划）
├── .trae/skills/                     # SKILL 文档（插件开发等）
│   └── gameplatform-plugin-dev/      # 插件开发 SKILL（references/ 分主题文档）
├── scripts/                          # 全栈一键脚本
├── AGENTS.md                         # 项目总览 (本文件)
└── README.md                         # 项目入口
```

---

## 模块职责

### 后端模块

| 模块 | 职责 |
|------|------|
| api | DTO/VO 契约，供 core 与 plugin 共享 |
| core | 主应用：控制器、服务实现、数据库访问、WebSocket、Docker/SSH 交互 |
| plugin | 插件 SDK：扩展点接口、插件框架服务、宿主能力服务接口 |
| plugin-l4d2 | L4D2 游戏增强插件：RCON、地图、SourceMod 插件管理、配置预设 |

### 前端模块

| 模块 | 职责 |
|------|------|
| frontend | 主应用：主机、实例、游戏、系统管理、插件菜单、Wujie 微前端容器 |
| plugin-l4d2/frontend | 插件子应用：L4D2 专属管理页面 |

---

## 运行模式

前端支持三种运行模式，通过 `detectMode()` 区分：

| 模式 | 路由基座 | 用途 |
|------|----------|------|
| Wujie 插件模式 | `/plugin/l4d2/ui/` | 以微前端方式嵌入主应用 |
| Standalone 部署模式 | `/ui/` | 独立部署，根路径重定向到 `/ui/index.html` |
| Vite 开发模式 | `/` | 本地开发，proxy 转发 `/api` 到后端 8080 |

---

## 前后端协作

### API 通信规范

#### 基础配置
- **基础路径**: `/api`
- **认证方式**: JWT Token
- **请求头**: `Authorization: Bearer {token}`

#### 统一响应格式
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1711084800000
}
```

#### 分页响应格式
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "current": 1,
    "size": 10,
    "total": 100,
    "pages": 10,
    "records": []
  },
  "timestamp": 1711084800000
}
```

### WebSocket 通信

| 端点 | 用途 |
|------|------|
| `/ws/ssh` | Web SSH 终端 |
| `/ws/instance/console` | 实例控制台 |
| `/ws/instance/log` | 实例日志流 |
| `/ws/docker/{hostId}/containers/{containerId}/exec` | Docker 容器终端 |

---

## 常用命令

### 后端
```bash
cd backend

# 编译
mvn clean compile

# 运行
mvn spring-boot:run

# 测试
mvn test

# 打包
mvn clean package

# 代码覆盖率
mvn jacoco:report
```

### 前端
```bash
cd frontend

# 安装依赖
npm install

# 开发模式
npm run dev

# 构建
npm run build

# 测试
npm run test

# 代码检查
npm run lint
```

### 全栈一键脚本
```powershell
# 编译 + 插件打包 + 启动前后端
.\scripts\rebuild-restart-all.ps1

# 仅改后端 Java 代码
.\scripts\rebuild-restart-all.ps1 -SkipPlugins

# 仅改前端代码
.\scripts\rebuild-restart-all.ps1 -SkipBackendCompile -SkipPlugins
```

---

## 关键工程约定

- **范围隔离（ADR-0002）**：主应用 `core/` 不得包含插件业务配置（`plugin.{gameCode}` 前缀）和插件专属表（`{gameCode}_*` 前缀）；插件配置由 `@ConfigurationProperties` 字段默认值自负，插件表由 ExtensionClient 的 `ext_plugin_{pluginId}_{resource}` 模式自管。游戏元数据 `games/{gameCode}.yml` 是例外，由主应用维护。详见 [ADR-0002](docs/design/adr/0002-main-app-plugin-scope-isolation.md)
- 扩展资源基类使用 Hutool 雪花 ID（String 类型 PRIMARY KEY），保留 name 作为 NOT NULL UNIQUE 业务标识
- 游戏实例表使用 `host_id` + `instance_name` 联合唯一索引
- Docker 类部署（docker / docker-compose / linuxgsm-docker）统一支持 `mountHostCerts` 选项，默认关闭
- `DockerComposeAdapter` 优先使用 `docker compose`，回退 `docker-compose`
- `LinuxGsmDockerAdapter` 无条件注入 `/etc/ssl/certs/ca-certificates.crt` 只读挂载
- SSH 认证优先使用解析后的私钥，其次解密密码，禁止将用户名作为密码
- `SshUtil` 使用连接池模式（共享 SshClient + CachedSession 会话池），后台每 60s 清理空闲超时会话
- RCON 连接采用 `RconConnectionResolver` → `RconConnectionManager` → `RconService` 三层架构
- 实例详情拆分为静态接口 `GET /instances/{id}` 与动态接口 `GET /instances/{id}/metrics`
- 插件 UI 资源路径需在 `SecurityConfig` 中放行 `/pf4j/plugin/*/ui/**` 和 `/pf4j/plugins/*/ui/**`
- `PluginFrameworkController.getPluginResource` 对 `index.html` 返回 `Cache-Control: no-store`，其余带 hash 的 JS/CSS 保留 7 天缓存
- `InstanceVO` 必须包含 `iconUrl` 与 `runtimeMetadata` 字段
- 部署向导展示游戏所有默认端口（`defaultPorts` Map），主端口 `game` 单独输入，其余作为附加端口允许编辑

---

## 安全规范

1. **认证授权**: JWT Token + Spring Security
2. **密码加密**: BCrypt
3. **敏感数据**: AES加密存储
4. **输入校验**: 全局参数校验
5. **SQL注入**: MyBatis参数绑定
6. **XSS防护**: 前端转义 + 后端过滤

---

## 错误码规范

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未授权 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器错误 |
| 1001-1999 | 业务错误码 |

---

## 测试文档

| 文档 | 描述 |
|------|------|
| [UI 测试文档总览](docs/testing/ui-testing/README.md) | UI 测试策略、工具链、用例模板 |
| [E2E 验证清单](docs/testing/ui-testing/07-e2e-checklist.md) | 发布前验收清单 |
| [部署任务状态机测试用例](docs/testing/deploy-task-status-machine-ui-test-cases.md) | 部署任务相关用例 |

---

## 相关文档

- [项目 README](README.md) — 项目总览与快速开始
- [文档导航](docs/README.md) — docs/ 目录分层说明
- [后端开发指南](backend/AGENTS.md)
- [前端开发指南](frontend/AGENTS.md)
- [API接口文档](docs/api/api-doc.md)
- [UI/UE设计规范](docs/design/ui-design-spec.md)
- [架构文档](docs/architecture/ARCHITECTURE.md)
- [ADR 决策记录](docs/design/adr/README.md)
  - [ADR-0001 插件菜单归属](docs/design/adr/0001-plugin-menu-ownership.md)
  - [ADR-0002 主应用与插件范围隔离规约](docs/design/adr/0002-main-app-plugin-scope-isolation.md)
- [插件开发指南](.trae/skills/gameplatform-plugin-dev/SKILL.md)

---

*最后更新: 2026-08-03*
