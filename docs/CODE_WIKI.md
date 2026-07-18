# Game Platform Manager - Code Wiki

> 游戏服务器统一管理平台 · 结构化代码知识库
>
> 基于源码分析生成，覆盖项目整体架构、模块职责、关键类与函数、依赖关系及运行方式。

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [整体架构](#3-整体架构)
4. [项目结构](#4-项目结构)
5. [后端模块详解](#5-后端模块详解)
   - [5.1 api 模块（接口契约）](#51-api-模块接口契约)
   - [5.2 plugin 模块（插件扩展点 SDK）](#52-plugin-模块插件扩展点-sdk)
   - [5.3 core 模块（核心业务）](#53-core-模块核心业务)
   - [5.4 plugin-l4d2 模块（插件示例）](#54-plugin-l4d2-模块插件示例)
6. [前端架构详解](#6-前端架构详解)
7. [数据库设计](#7-数据库设计)
8. [游戏元数据机制](#8-游戏元数据机制)
9. [依赖关系总览](#9-依赖关系总览)
10. [项目运行方式](#10-项目运行方式)
11. [关键设计要点与已知改进点](#11-关键设计要点与已知改进点)

---

## 1. 项目概述

**Game Platform Manager** 是一个面向个人游戏服运维场景的轻量级管理后台，采用前后端分离架构，支持多游戏、多主机的统一管理。

### 核心能力

| 能力域 | 说明 |
|--------|------|
| 主机纳管 | SSH 连接管理、资源监控、Web 终端（XTerm.js） |
| 游戏部署 | 支持 LinuxGSM / Docker / Docker Compose 三种部署方式，通过适配器模式扩展 |
| 实例管理 | 游戏实例全生命周期（创建/启动/停止/重启/删除）、配置管理、远程文件管理（SFTP） |
| Docker 管理 | 容器/镜像/文件/关联的独立管理子模块，含 exec/attach/logs 实时 WebSocket |
| 插件扩展 | 基于 PF4J 的插件框架，单一扩展插槽 `GameEnhancementExtension`，支持热加载 |
| 备份还原 | 数据库备份（MySQL/PostgreSQL/SQLite）与文件备份，含进度、取消、校验 |
| 实时通信 | WebSocket 用于 SSH 终端、实例控制台/日志、Docker 终端/日志 |
| 微前端集成 | 前端采用 Wujie 微前端加载插件子应用，主子应用通过 bus 通信 |

### 工程特征

- **多模块 Maven 工程**：后端拆分为 `api` / `plugin` / `core` / `plugin-l4d2` / `plugin-template` 五个模块。
- **嵌入式数据库**：SQLite，零安装，数据库文件位于 `${user.home}/game-platform/data/game_platform.db`。
- **统一响应规范**：所有 REST 接口统一返回 `Result<T>` / `PageResult<T>`。
- **配置驱动**：游戏元数据通过 YAML 描述，可自动生成前端配置表单。

---

## 2. 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.5 | 核心框架 |
| Spring Security | 6.x | 安全认证（JWT + 无状态） |
| Spring WebSocket | 6.x | 实时通信 |
| MyBatis-Plus | 3.5.6 | ORM（含逻辑删除、自动填充） |
| SQLite | 3.45.2.0 | 嵌入式数据库 |
| Apache MINA SSHD | 2.12.1 | SSH/SFTP 连接 |
| Docker Java | 3.3.4 | Docker API（httpclient5 传输） |
| PF4J | 3.10.0 | 插件框架 |
| JWT (jjwt) | 0.12.5 | 令牌认证 |
| Hutool | 5.8.26 | 工具类（AES/SHA256 等） |
| SnakeYAML | 2.2 | 游戏元数据解析 |
| ini4j | 0.5.4 | INI 配置文件解析 |
| Thymeleaf | 6.x | 插件前端模板渲染 |
| springdoc | 2.3.0 | OpenAPI/Swagger 文档 |
| Lombok | 1.18.30 | 代码简化 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.4.21 | 前端框架（Composition API + `<script setup>`） |
| Vue Router | 4.3.0 | 路由管理 |
| Pinia | 2.1.7 | 状态管理 |
| Element Plus | 2.6.1 | UI 组件库（自动按需导入） |
| Axios | 1.6.8 | HTTP 请求 |
| XTerm.js | 5.3.0 | Web 终端 |
| Wujie / wujie-vue3 | 1.0.22 | 微前端（加载插件子应用） |
| Vite | 5.2.0 | 构建工具 |
| Vitest | 1.4.0 | 测试框架 |
| SCSS | 1.72.0 | CSS 预处理器 |

---

## 3. 整体架构

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      前端 (Vue 3 + Vite)                      │
│  views ─ stores ─ api ─ utils(request/ws) ─ plugins(Wujie)   │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTP /api  +  WebSocket /ws
┌───────────────────────────┴─────────────────────────────────┐
│                    后端 core 模块 (Spring Boot)                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Controller 层 (REST + WebSocket Handler)             │   │
│  │  Auth/Host/Instance/Game/Backup/Plugin/System/Docker  │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Service 层 (业务逻辑 + 部署编排 + 文件/配置)          │   │
│  │  + AOP(操作日志) + Security(JWT 过滤器)               │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Adapter 层 (部署适配器: LinuxGSM/Docker/Compose)     │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Plugin 框架 (PF4J 管理器 + Spring 子容器 + 沙箱)     │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Mapper (MyBatis-Plus) → SQLite                       │   │
│  └──────────────────────────────────────────────────────┘   │
│          │ SSH/SFTP (MINA SSHD)        │ Docker API          │
└──────────┼─────────────────────────────┼─────────────────────┘
           ▼                             ▼
   ┌───────────────┐            ┌───────────────┐
   │  远程游戏主机   │            │  Docker 引擎   │
   │  (LinuxGSM/Native)│        │  (容器化部署)   │
   └───────────────┘            └───────────────┘
           ▲
           │ 热加载 JAR (PF4J)
   ┌───────┴───────────────────────────────────────┐
   │  外部插件 (如 plugin-l4d2.jar)                  │
   │  └ 独立 Spring 子容器 + 独立前端子应用(Wujie)   │
   └───────────────────────────────────────────────┘
```

### 3.2 模块依赖关系

```
        ┌─────────────┐
        │  plugin-l4d2 │ (provided 依赖以下模块，打包为独立 JAR)
        └──────┬───────┘
               │
   ┌───────────┴────────────┐
   ▼                        ▼
┌─────────┐  provided   ┌─────────┐
│ plugin  │◄────────────│  core   │ (主应用，可执行 JAR)
│ (扩展点) │             │ (业务)  │
└────┬────┘             └────┬────┘
     │                       │
     └──────────┬────────────┘
                ▼
           ┌─────────┐
           │   api   │ (DTO/VO/Result/Exception，最底层契约)
           └─────────┘
```

- `api`：最底层，被所有模块依赖，只含数据契约。
- `plugin`：扩展点 SDK，被 `core` 和外部插件（如 `plugin-l4d2`）依赖。
- `core`：主业务实现，依赖 `api` + `plugin`，是唯一可启动模块。
- `plugin-l4d2`：插件实现，以 `provided` 依赖 `core`/`plugin`/`api`，打包成 PF4J JAR 由 `core` 运行时加载。

---

## 4. 项目结构

```
game_platform_manger/
├── backend/                          # 后端多模块 Maven 工程
│   ├── pom.xml                       # 父 POM (packaging=pom)
│   ├── api/                          # 接口契约模块 (DTO/VO/Result/Exception)
│   ├── plugin/                       # 插件扩展点 SDK 模块
│   ├── core/                         # 核心业务模块 (可启动)
│   ├── plugin-l4d2/                  # L4D2 插件示例 (含独立前端)
│   ├── plugin-template/              # 插件模板
│   ├── scripts/                      # 启动/停止/重启脚本 (bat/ps1/sh)
│   └── AGENTS.md                     # 后端开发指南
├── frontend/                         # 前端 Vue 3 工程
│   ├── src/
│   │   ├── api/                      # API 封装
│   │   ├── views/                    # 页面
│   │   ├── stores/                   # Pinia 状态
│   │   ├── components/               # 公共组件
│   │   ├── layouts/                  # 布局
│   │   ├── router/                   # 路由
│   │   ├── utils/                    # request.js / websocket.js
│   │   ├── plugins/                  # 微前端插件集成 (Wujie + SDK)
│   │   └── styles/
│   └── AGENTS.md                     # 前端开发指南
├── docs/                             # 文档目录
└── AGENTS.md                         # 项目总览
```

---

## 5. 后端模块详解

### 5.1 api 模块（接口契约）

`com.gameplatform:game-platform-api`，最底层模块，仅依赖 Jackson / Swagger 注解 / Jakarta Validation，**不含业务逻辑**。

| 子包 | 内容 |
|------|------|
| `common.result` | `Result<T>`（统一响应）、`PageResult<T>`（分页响应）、`ResultCode`（响应码枚举） |
| `common.exception` | `BusinessException`（业务异常基类） |
| `dto` | 请求 DTO：`LoginDTO`、`HostCreateDTO`、`InstanceCreateDTO`、`GameCreateDTO`、`PluginCreateDTO`、`PageQueryDTO` 等；`dto/docker/*` 为 Docker 子模块专用 |
| `vo` | 响应 VO：`LoginVO`、`HostVO`、`InstanceVO`、`GameVO`、`PluginVO`、`UserVO`、`HostResourceVO`、`LogVO`；`vo/docker/*` 为容器/镜像/文件 VO |

**统一响应格式**：

```json
{ "code": 200, "message": "操作成功", "data": {}, "timestamp": 1711084800000 }
```

分页响应 `data`：`{ current, size, total, pages, records }`。

---

### 5.2 plugin 模块（插件扩展点 SDK）

`com.gameplatform:game-platform-plugin`，定义插件 SDK 契约，被 `core` 和外部插件共用。**不含实现**。

#### 5.2.1 核心扩展点接口 `GameEnhancementExtension`

继承 PF4J `ExtensionPoint`，是平台**唯一扩展插槽**——每个插件必须恰好提供一个 `@Extension` 实现类。方法分六组：

| 分组 | 方法 | 说明 |
|------|------|------|
| 元数据（必须） | `getGameCode()` / `getGameName()` / `getVersion()` / `getDescription()` | 游戏唯一编码、名称、版本、描述 |
| 清单/配置（默认实现） | `getManifest()` / `getConfigFields()` | 提供前端动态加载清单；声明配置字段供框架自动渲染表单 |
| 生命周期钩子（默认空） | `onLoad` / `onUnload` / `onInstanceCreate` / `onInstanceStart` / `onInstanceStop` / `onInstanceDelete` / `onLoadError` | 插件加载、实例增删启停回调 |
| 前端资源（默认） | `getIcon()` / `getFrontendEntry()` | 图标与前端入口（默认 `assets/icon.png`、`index.html`） |
| Spring/存储（默认） | `getBasePackage()` | 子容器扫描包；持久化通过 `@ExtensionModel` 注解 + `ExtensionClient` 实现（见 5.2.4） |
| 依赖 | `getDependencies()` | 依赖的其他插件 gameCode 列表 |

#### 5.2.2 上下文三件套

| 类 | 职责 |
|----|------|
| `PluginContext`（接口） | 封装插件运行时信息：pluginId/gameCode/gameName/version/customProperties（v3.0 起移除 `dataAccess`/`declaredTables`，持久化交由子容器注入的 `ExtensionClient`） |
| `PluginContextHolder`（静态工具） | `ConcurrentHashMap` 持有所有已加载插件上下文，提供按 pluginId / gameCode 查询 |
| `ExtensionClient`（接口） | 插件**唯一持久化入口**：`create`/`update`/`delete`/`get`/`list`/`count`，由主应用在子容器注册为单例（绑定 pluginId，自动注入 `group_name`+`kind` 过滤，详见 5.2.4） |

#### 5.2.3 其他

- `PluginConstants`：路径前缀（`/pf4j`、`/api/plugin/{gameCode}`）、配置键名（`plugin.id` 等）、Bean 名、表名常量。
- `PluginConfigField`：配置字段 POJO（key/label/type/defaultValue/options/...），`type` 为枚举 `TEXT/NUMBER/BOOLEAN/SELECT/PASSWORD/TEXTAREA`。
- 异常体系：`PluginException`（基类）→ `PluginLoadException` / `PluginConfigException` / `PluginDataAccessException` / `PluginPathConflictException`。
- `PluginManifestVO` / `PluginStatusVO`：清单与状态 VO。

#### 5.2.4 扩展资源存储（Halo 风格统一宽表）

v2.0 起插件持久化统一采用 **Halo 风格的统一 JSON 宽表**，废弃旧的"DDL 脚本 + 表名白名单沙箱"机制。

**核心组件**：

| 类/注解 | 所在模块 | 职责 |
|--------|---------|------|
| `@ExtensionModel` | `plugin` | 标注在 `AbstractExtension<?>` 子类上，声明 `strategy` / `group` / `kind` |
| `Strategy` | `plugin` | 枚举：`SHARED`（全局 `extensions` 表）/ `PLUGIN_ISOLATED`（`ext_{pluginId}`）/ `MODEL_ISOLATED`（`ext_{pluginId}_{kind}`） |
| `AbstractExtension<T>` | `api` | 资源基类，字段与宽表列一一对应：`name` / `groupName` / `kind` / `version` / `metadata` / `spec<T>` / `status` |
| `ExtensionMetadata` | `api` | 元数据：`labels` / `annotations` / `creationTimestamp` / `updateTimestamp` |
| `ExtensionClient` | `plugin` | 插件唯一持久化入口（接口）：`create` / `update` / `delete` / `updateStatus` / `get` / `list` / `listAll` / `count` / `getManagedTables` |
| `ListOptions` | `plugin` | 列表查询选项（Builder 模式）：`status` / `label` / `specFilter("$.path", op, value)` / `createdAfter` / `limit` / `offset` / `orderBy` |
| `ExtensionClientImpl` | `core` | `ExtensionClient` 实现，绑定 pluginId，所有方法自动注入 `group_name` + `kind` 过滤 |
| `ExtensionRouter` | `core` | 路由层：在 SQL 构造时根据 `Strategy` 决定物理表名，强制身份过滤 |
| `PluginSchemaManager` | `core` | 扫描插件 `basePackage` 下的 `@ExtensionModel` 类，为非 SHARED 策略建专属表 |

**统一宽表 DDL**（所有策略共用结构，复合主键 `(name, group_name, kind)`）：

```sql
CREATE TABLE {表名} (
    name                 TEXT    NOT NULL,
    group_name           TEXT    NOT NULL,         -- 框架填充 = pluginId
    kind                 TEXT    NOT NULL,         -- 框架填充 = 类名或注解 kind
    version              INTEGER NOT NULL DEFAULT 1, -- 乐观锁
    metadata             TEXT,                     -- JSON: labels/annotations/timestamps
    spec                 TEXT,                     -- JSON: 业务数据（强类型 T）
    status               TEXT,                     -- 高频过滤字段
    creation_timestamp   INTEGER,                  -- epochMilli
    update_timestamp     INTEGER,
    PRIMARY KEY (name, group_name, kind)
);
```

**三层隔离策略**：

| 策略 | 物理表 | 隔离粒度 | 适用场景 |
|------|--------|---------|---------|
| `SHARED` | `extensions` | 仅逻辑隔离（按 group_name+kind 过滤） | 跨插件共享的小数据量资源 |
| `PLUGIN_ISOLATED` | `ext_{pluginId}` | 插件间物理隔离 | 插件内多模型混居，单表足够 |
| `MODEL_ISOLATED` | `ext_{pluginId}_{kind}` | 模型级隔离 | 高频写入/大量数据的独立模型（如监控指标、下载任务） |

**身份隔离机制**：`ExtensionClientImpl` 构造时绑定 pluginId，所有 `list`/`get`/`update`/`delete` SQL 由 `ExtensionRouter` 强制注入 `group_name = ?` 过滤，插件**无法**访问其他插件的数据。

**查询能力**：`ListOptions.builder()` 支持 spec JSON 路径过滤（`specFilter("$.instanceId", "=", value)`，SQLite 走 `json_extract` 或内存过滤）、label 过滤、时间范围（`createdAfter(epochMilli)`）、分页（`limit`/`offset`）、排序（`orderBy`）。

**乐观锁**：所有 `update` 操作校验 `version`，冲突时抛 `OptimisticLockException`。

**资源命名规范**：name 由插件自定义，建议格式 `{instanceId}-{业务键}`（如 `1-76561198000000001`、`1-1719900000000`），同表内唯一。

---

### 5.3 core 模块（核心业务）

`com.gameplatform:game-platform-core`，**唯一可启动模块**，包含启动类、所有业务实现、插件框架宿主侧实现。

#### 5.3.1 启动与配置

**启动类** [GamePlatformApplication.java](file:///d:/program/ai/game_platform_manger/backend/core/src/main/java/com/gameplatform/GamePlatformApplication.java)

```java
@SpringBootApplication
@EnableScheduling
public class GamePlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(GamePlatformApplication.class, args);
    }
}
```

**关键配置**（`core/src/main/resources/application.yml`）：

| 配置项 | 值/说明 |
|--------|---------|
| `server.port` | 8080 |
| `server.servlet.context-path` | `/api`（所有 REST 接口前缀） |
| 数据源 | `jdbc:sqlite:${user.home}/game-platform/data/game_platform.db`，HikariCP 连接池 |
| JWT | 密钥 + 7 天过期 + `Authorization` 头 + `Bearer` 前缀 |
| `game-platform.plugin.plugins-dir` | `${user.home}/game-platform/plugins`（热加载） |
| `game-platform.ssh` | 连接/会话超时 |
| `game-platform.docker` | `unix:///var/run/docker.sock`，API 1.43 |
| `game-platform.backup` | 备份目录、最大份数、保留天数、压缩格式 |
| `game-platform.metadata.scan-path` | `classpath:games/` |

**配置类**（`config/` 包）：

| 类 | 职责 |
|----|------|
| `SecurityConfig` | Spring Security：禁 CSRF、无状态会话、放行登录/Swagger/插件UI/WebSocket、JWT 过滤器 |
| `JwtConfig` | JWT 配置属性绑定 |
| `JwtTokenProvider` | Token 生成/解析/验证/刷新（HMAC-SHA 密钥） |
| `JwtAuthenticationFilter` | 请求拦截，从 Header 取 Token 校验并注入 SecurityContext |
| `UserDetailsServiceImpl` | Spring Security 用户加载 |
| `AuthenticationManagerConfig` | AuthenticationManager Bean |
| `MybatisPlusConfig` | 分页插件、乐观锁插件 |
| `WebSocketConfig` | 注册 6 个 WebSocket 端点 |
| `WebSocketHandlerConfig` | WebSocket 处理器 Bean |
| `CorsConfig` | 跨域配置 |
| `AsyncConfig` | `@EnableAsync` 异步线程池（用于日志/备份/部署） |
| `DatabaseInitializer` | 数据库初始化 |
| `SwaggerConfig` | OpenAPI 文档 |
| `GamePlatformConfig` | 平台自定义配置属性绑定（plugin/ssh/docker/backup/storage/metadata） |
| `GameYamlConfig` | 游戏 YAML 元数据模型 |

#### 5.3.2 控制器层（REST API 清单）

所有控制器统一 `@RestController` + `Result<T>` 包装，路径均带 `/api` context-path 前缀（下表路径不含 `/api`）。

**核心业务控制器**（`controller/`）：

| 控制器 | 基础路径 | 主要端点 |
|--------|----------|----------|
| `AuthController` | `/auth` | `POST /login`、`POST /logout`、`GET /info`、`PUT /password`、`POST /refresh` |
| `HostController` | `/hosts` | CRUD、`POST /{id}/test`（SSH 测试）、`GET /{id}/status`、`GET /{id}/resources`、`GET /{id}/ports`、`POST /{id}/hosts-preview`（hosts 预检）、`POST /{id}/hosts-refresh`（hosts 刷新） |
| `InstanceController` | `/instances` | CRUD、`POST /{id}/start|stop|restart`、`GET /{id}/status|logs|config`、`PUT /{id}/config`、文件管理（`GET/POST/DELETE /{id}/files`）、按主机/游戏查询 |
| `GameMetadataController` | `/games` | CRUD、`POST /scan`、`POST /scan/external`、`GET /{gameCode}/export`、`POST /import`、`POST /validate`、`GET /scan/stats` |
| `BackupController` | `/instances/{instanceId}/backups` | `POST /database`、`POST /files`、`GET /{id}`、`GET /{id}/progress`、`POST /{id}/cancel|restore|verify`、`DELETE /{id}`、`GET /{id}/download` |
| `PluginController` | `/plugins` | CRUD、`PUT /{id}/enable|disable`、`GET /enabled` |
| `SystemController` | `/system` | `GET /health|info|settings|statistics`、`PUT /settings`、`GET /logs`（分页/最近/按操作人/按类型）、`POST /cache/clear` |

**Docker 子模块控制器**（`controller/docker/`）：

| 控制器 | 基础路径 | 主要端点 |
|--------|----------|----------|
| `DockerContainerController` | `/docker/hosts/{hostId}/containers` | 列表/详情/启停/重启/删除/统计/健康/日志 |
| `DockerFileController` | `/docker/hosts/{hostId}/containers/{containerId}/files` | 浏览/读写/上传/下载/拷贝 |
| `DockerImageController` | `/docker/hosts/{hostId}/images` | 列表/删除/清理悬空镜像 |
| `DockerLinkController` | `/docker/links` | 容器与实例关联 CRUD、`POST /auto`（基于镜像名自动匹配） |

**插件框架控制器**（`core/plugin/controller/`）：

| 控制器 | 基础路径 | 说明 |
|--------|----------|------|
| `PluginFrameworkController` | `/pf4j` | 插件管理 API（列表/状态/清单/启停/重载/卸载）+ 静态资源服务 `/pf4j/plugin/{gameCode}/ui/**` |
| `PluginPageController` | `/plugin/{gameCode}` | Thymeleaf 渲染插件页面（`/ui`、`/ui/views/**`） |
| `PluginResourceController` | `/plugins/{gameCode}/ui` | Wujie 子应用静态资源（带 CORS 头） |

#### 5.3.3 服务层（关键服务）

**Service 接口与实现**（`service/` + `service/impl/`）：

| Service | 关键方法 | 业务要点 |
|---------|----------|----------|
| `UserService` / `UserServiceImpl` | `login`、`logout`、`getCurrentUser`、`changePassword` | SHA-256 校验密码（注：与 AGENTS.md 所述 BCrypt 不一致，实际用 Hutool `SecureUtil.sha256`）；登录生成 JWT 并更新登录信息 |
| `HostService` / `HostServiceImpl` | `createHost`、`testConnection`、`refreshStatus`、`refreshAllHostsStatus`、`getHostResourceInfo`、`previewHostsRefresh`、`refreshHosts` | SSH 私钥/密码 AES 加密存储（密钥 `GamePlatform2024`）；`refreshStatus` 通过 SSH 获取 CPU/内存/磁盘使用率；VO 转换剔除敏感字段；`previewHostsRefresh` 读取宿主机 `/etc/hosts` 识别待改域名；`refreshHosts` 将 127.0.0.1 域名改为 LAN IP 并刷新 DNS 缓存 |
| `InstanceService` / `InstanceServiceImpl` | `createInstance`、`startInstance`、`getInstanceStatus`、`deleteInstance` | 通过 `adapterFactory.getAdapter(deployType)` 选择适配器；启动流程：状态预校验→更新"启动中"→`adapter.start`→成功置运行；`getInstanceStatus` 自动同步 DB 与实际状态；删除前先停止 |
| `GameService` / `GameServiceImpl` | `createGame`、`updateGame`、`pageGames` | gameCode 唯一性校验；更新时 id/gameCode 不可改；支持 gameName/gameCode 关键词搜索 |
| `BackupService` / `BackupServiceImpl` | `createDatabaseBackup`、`createFileBackup`、`restoreBackup`、`verifyBackup`、`cancelBackup` | `@Async` 异步执行；`ConcurrentHashMap<Long,AtomicBoolean>` 跟踪取消标志；MySQL 用 mysqldump、PostgreSQL 用 pg_dump（PGPASSWORD）、SQLite 用 `.dump`；文件备份远程 `tar -czf` 后下载；还原先停实例再解压最后启动；`verifyBackup` 三重校验（存在+大小+MD5） |
| `PluginService` / `PluginServiceImpl` | `createPlugin`、`updatePluginStatus`、`pagePlugins` | **仅管理 `plugin_info` 表元数据**，不涉及 PF4J 实际加载（后者在 `plugin/` 子包） |
| `LogService` / `LogServiceImpl` | `log`（`@Async`）、`pageLogs`、`getRecentLogs` | 异步记录操作日志，异常不阻塞主业务；支持四字段 OR 搜索 |

**辅助服务类**（直接 `@Service`，无接口）：

| 类 | 职责 |
|----|------|
| `DeployService` | 部署流程统一编排器，8 阶段进度（INIT→ENV_CHECK→PORT_CHECK→RESOURCE_CHECK→PRE_DEPLOY→DEPLOY→HEALTH_CHECK→UPDATE_STATUS），支持 `@Async` 异步部署、失败回滚、`DeployProgressCallback` 回调 |
| `ConfigService` | 多格式配置文件读写，支持 Properties/YAML/JSON/INI 四种格式，支持嵌套 key（`.` 分隔）、格式互转 |
| `FileService` | 基于 SFTP 的远程文件操作（MINA SSHD `SshClient`/`SftpClient`），CRUD + 目录递归删除 + 文本读写，认证信息通过 `AesUtil.decrypt` 解密 |
| `HostsFileRefresher` | 宿主机 `/etc/hosts` 刷新服务：`previewRefresh`（SSH 读取 hosts 识别 127.0.0.1 回环域名）+ `refreshHosts`（生成新内容 → SFTP 上传 → sudo cp 覆盖 → 刷新 DNS 缓存）；支持 `selectedDomains` 选择性刷新；正确处理 `#` 行内注释 |
| `GameMetadataScanner` | `@PostConstruct` 启动时扫描 `classpath:games/*.yml`，SnakeYAML 解析 → 验证 → 转 `GameMetadata` 实体 → saveOrUpdate；支持外部目录扫描、导出 YAML |
| `LogService` | 操作日志异步记录 |

**Docker 子模块服务**（`service/docker/`）：`DockerContainerService`、`DockerFileService`、`DockerImageService`、`DockerContainerLinkService`，分别对应容器/文件/镜像/关联管理，通过 Docker Java API 远程操作。

#### 5.3.4 部署适配器（`adapter/`）

采用**适配器模式 + 工厂模式**，解耦具体部署方式。

| 类 | 职责 |
|----|------|
| `DeployAdapter`（接口） | 定义 `deploy`/`start`/`stop`/`restart`/`destroy`/`getStatus`/`healthCheck` 等方法，内含 `DeployType` 枚举（`linuxgsm`/`docker`/`docker-compose`/`native`） |
| `AbstractDeployAdapter`（抽象基类） | 注入 `SshUtil`/`HostMapper`/`InstanceMapper`/`AesUtil`；提供通用能力：`getHost`/`getInstance`/`executeCommand`（远程 SSH 命令）、`uploadFile`/`downloadFile`（SFTP）、`isPortInUse`、`isDockerInstalled`、`getAvailableDiskSpace`/`getAvailableMemory`、进度回调通知（`notifyProgress`/`notifyStageStart`/`notifyError`/`notifyComplete`） |
| `DeployAdapterFactory` | `@PostConstruct` 收集所有 `DeployAdapter` Bean 到 Map；`getAdapter(DeployType)` / `getAdapter(String typeCode)` / `supports()` |
| `LinuxGsmAdapter` | LinuxGSM 部署方式 |
| `DockerAdapter` | Docker 部署方式，`buildDockerRunCommand` 按 `mountHostCerts` 追加 `-v` 挂载宿主机证书 + `/etc/hosts:ro` |
| `DockerComposeAdapter` | Docker Compose 部署方式，`injectHostCertsMount` 动态注入 compose volumes（证书 + hosts）；compose V1/V2 兼容 |
| `DeployProgressCallback`（接口） | 部署进度回调：`onProgress`/`onStageStart`/`onStageComplete`/`onError`/`onLog`/`onComplete` |

`InstanceServiceImpl` 与 `DeployService` 均通过 `adapterFactory.getAdapter(deployType)` 获取适配器，`deployType` 为字符串（空值降级为 `native`）。

#### 5.3.5 安全与 JWT

- **认证流程**：`AuthController.login` → `UserService.login`（SHA-256 校验）→ `JwtTokenProvider.generateToken` → 返回 `LoginVO(token, tokenType, expiresIn, user)`。
- **请求鉴权**：`JwtAuthenticationFilter` 在 `UsernamePasswordAuthenticationFilter` 之前，从 `Authorization` 头取 Bearer Token，`JwtTokenProvider.validateToken` 校验，成功则注入 `SecurityContextHolder`。
- **Token**：HMAC-SHA 签名，7 天有效期，支持 `refreshToken`。
- **密码加密**：实际为 SHA-256（Hutool），非 BCrypt。
- **敏感数据**：SSH 私钥/密码、数据库密码使用 AES 加密存储（`AesUtil` / Hutool `SecureUtil.aes`）。
- **放行路径**：`/auth/login`、`/auth/register`、Swagger、`/plugins/**/ui/**`（插件前端）、`/ws/**`。

#### 5.3.6 WebSocket（`websocket/` + `WebSocketConfig`）

`WebSocketConfig` 注册 6 个端点（均 `setAllowedOriginPatterns("*")`）：

| 端点模式 | 处理器 | 用途 |
|----------|--------|------|
| `/ws/ssh/*` | `SshWebSocketHandler` | Web SSH 终端（XTerm.js） |
| `/ws/instance/*/logs` | `InstanceLogWebSocketHandler` | 实例日志流 |
| `/ws/instance/*/console` | `InstanceConsoleWebSocketHandler` | 实例控制台 |
| `/ws/docker/*/containers/*/exec` | `DockerExecWebSocketHandler` | Docker exec 终端 |
| `/ws/docker/*/containers/*/attach` | `DockerAttachWebSocketHandler` | Docker attach 终端 |
| `/ws/docker/*/containers/*/logs` | `DockerLogsWebSocketHandler` | Docker 实时日志 |

> 注意：因 `context-path=/api`，实际 WebSocket 路径为 `/api/ws/...`。前端 Vite 代理将 `/ws` 重写为 `/api/ws`。

#### 5.3.7 数据访问与实体

**实体类**（`entity/`，均继承 `BaseEntity`）：

| 实体 | 表名 | 说明 |
|------|------|------|
| `User` | `sys_user` | 用户 |
| `Host` | `host_info` | 主机（含 SSH 加密信息、资源使用率） |
| `GameMetadata` | `game_metadata` | 游戏元数据 |
| `GameInstance` | `game_instance` | 游戏实例 |
| `PluginInfo` | `plugin_info` | 插件信息 |
| `BackupRecord` | `backup_record` | 备份记录 |
| `OperationLog` | `operation_log` | 操作日志 |
| `DockerContainerLink` | `docker_container_link` | 容器与实例关联 |

`BaseEntity` 提供：`id`（自增）、`createTime`/`updateTime`（自动填充）、`createBy`/`updateBy`、`deleted`（`@TableLogic` 逻辑删除）、`remark`。

**Mapper**（`mapper/`）：均继承 MyBatis-Plus `BaseMapper<T>`，`UserMapper` 有自定义 XML（`resources/mapper/UserMapper.xml`）。

**类型处理器**（`handler/`）：`JsonTypeHandler`、`JsonListTypeHandler`（JSON 字段映射）、`MyMetaObjectHandler`（自动填充 createTime/updateTime/createBy/updateBy）。

**AOP**：`@OperationLog` 注解 + `OperationLogAspect` 切面，自动记录操作日志。

**定时任务**（`task/`）：`BackupCleanupTask`（备份过期清理）、`HostMonitorTask`（主机状态监控）。

#### 5.3.8 插件框架宿主实现（`core/plugin/`）

这是插件框架的"宿主侧"，负责加载/管理插件、注入上下文、动态注册控制器。

| 类 | 职责 |
|----|------|
| `GamePlatformPluginManager` | 继承 PF4J `DefaultPluginManager`；重写 `createPluginLoader` 用 `parentFirst=true` 的 `PluginClassLoader`，确保扩展点接口在父 ClassLoader 统一加载 |
| `PluginAutoLoader` | `@Component` + `ApplicationRunner`；两阶段加载：`run()` 加载并启动 JAR，`onApplicationReady` 为每个扩展点创建 Spring 子容器 |
| `PluginSpringContextFactory` | **核心组件**：为每个插件创建独立 `AnnotationConfigApplicationContext`（父=主应用）；调用 `PluginSchemaManager.createSchemas` 为非 SHARED 策略建专属表；扫描 `basePackage`；注册 `ExtensionClient` 单例（绑定 pluginId + ownedTables）；把插件 `@RestController` 动态注册到主 `RequestMappingHandlerMapping`；含**路径冲突检测**（`registeredPaths`） |
| `ExtensionClientImpl` | `ExtensionClient` 实现：基于 `JdbcTemplate` + `ExtensionRouter`，构造时绑定 pluginId，所有方法自动注入 `group_name`+`kind` 过滤；含 `DataAccessException` 分支识别 SQLite PRIMARY KEY 冲突 |
| `ExtensionRouter` | 路由层：根据 `@ExtensionModel.strategy` 决定物理表名（SHARED → `extensions` / PLUGIN_ISOLATED → `ext_{pluginId}` / MODEL_ISOLATED → `ext_{pluginId}_{kind}`），强制身份过滤 |
| `PluginSchemaManager` | 扫描插件 `basePackage` 下 `@ExtensionModel` 类，为非 SHARED 策略建专属表（执行 DDL），返回 ownedTables 供 `ExtensionClientImpl` 持有 |
| `DefaultPluginContext` | `PluginContext` 实现（Lombok `@Builder`，字段 final） |
| `PluginFrameworkService`/`Impl` | 对外能力：查询/清单/启停/重载/卸载/资源读取；含 `manifestCache` 缓存；`getManifestByPluginId` 先读 JAR 内 `manifest.json`，否则从扩展点构建 |
| `PluginLifecycleHook` | `@Component`，处理 PF4J 状态变化副作用（同步 `plugin_info` 表 status/runtimeState/loadTime/startTime）；v2.0 新增 `executeInstanceCreateHooks` / `executeInstanceStartHooks` / `executeInstanceStopHooks` / `executeInstanceDeleteHooks` 四个方法，按 `gameCode` 匹配通知 `GameEnhancementExtension` 实例钩子（被 `InstanceServiceImpl` 在实例生命周期调用） |
| `PluginStateEventListener` | 实现 `PluginStateListener`，映射 PF4J 状态到 `PluginLifecycleHook` 方法 |
| `PluginConfig` | 插件目录/热加载/扫描间隔配置；`@Bean pluginManager()` |
| `PluginThymeleafConfig` | 自定义 `ITemplateResolver`，从插件 ClassLoader 读取 `ui/*.html` 模板 |
| `PluginUtils` | 工具类：gameCode/extensionClass 缓存查找、`plugin.properties` 加载、路径处理（`stripApiPrefix`、`buildFrontendEntryUrl`） |

**插件加载流程**：

```
1. PluginAutoLoader.run() → pluginManager.loadPlugins() 加载所有 JAR
2. pluginManager.startPlugins() 启动插件
3. ApplicationReadyEvent → initPluginSpringContexts()
   ├─ PluginUtils.findPluginIdByExtension 找到 pluginId
   ├─ PluginUtils.loadPluginProperties 读取 plugin.properties
   ├─ PluginSpringContextFactory.loadPluginSpringContext
   │   ├─ 解析 basePackage
   │   ├─ PluginSchemaManager.createSchemas 扫描 @ExtensionModel，为非 SHARED 策略建专属表
   │   ├─ 创建子 ApplicationContext（parent=主应用, classLoader=插件CL）
   │   ├─ 注册 ExtensionClient 单例（ExtensionClientImpl，绑定 pluginId + ownedTables）
   │   ├─ scan(basePackage) + refresh()
   │   ├─ 找出 @RestController，注册到主 RequestMappingHandlerMapping（含路径冲突检测）
   │   ├─ 构建 DefaultPluginContext → PluginContextHolder.register
   │   └─ 调用 extension.onLoad(context) 钩子
   └─ 失败则 extension.onLoadError(null, e)
```

---

### 5.4 plugin-l4d2 模块（插件示例）

`com.gameplatform:plugin-l4d2`，求生之路2 游戏服务器增强插件，是扩展点 SDK 的完整参考实现。打包为 PF4J JAR（`maven-jar-plugin` 写入 `Plugin-Id/Class/Version` Manifest）。

#### 5.4.1 后端

| 类 | 职责 |
|----|------|
| `L4D2Plugin` | 继承 PF4J `Plugin`，`start`/`stop`/`delete` 仅日志 |
| `L4D2Extension` | `@Extension` 实现 `GameEnhancementExtension`：gameCode=`l4d2`，basePackage=`com.gameplatform.plugin.l4d2`；v2.0 起持久化由 4 个 `@ExtensionModel(strategy=MODEL_ISOLATED)` 类承载（见下表） |
| `L4D2Config` | `@ConfigurationProperties(prefix="plugin.l4d2")`：RCON 超时/重试、VPK 扫描/缓存配置 |
| `L4D2StandaloneApp` | `@SpringBootApplication`，独立运行模式（`java -jar ...-standalone.jar`），便于调试 |
| `RconService` | 实现 **Source RCON 协议**（小端序 TCP），支持 `executeCommand`/`getStatus`/`changeMap`/`kick`/`ban`/`changeDifficulty`/`changeGameMode`/`setMaxPlayers`；含中英难度/游戏模式互译表 |
| `VpkParserService` | VPK 文件解析业务，含双 Map 缓存（campaignCache + 时间戳） |
| `VpkParser` | VPK 二进制格式解析（签名 `0x55AA1234`，目录树解析，mission 文件 VDF 解析） |

**扩展资源模型**（`com.gameplatform.plugin.l4d2.extension`，均 `@ExtensionModel(strategy=MODEL_ISOLATED)`，物理表 `ext_{pluginId}_{kind}`）：

| Resource 类 | Spec 类 | 物理表 kind | name 规范 | 主要字段 |
|------------|---------|------------|----------|---------|
| `AdminResource` | `AdminSpec` | `AdminResource` | `{instanceId}-{steamId}` | instanceId/steamId/adminFlags/remark/isActive |
| `SystemMetricResource` | `SystemMetricSpec` | `SystemMetricResource` | `{instanceId}-{timestamp}` | instanceId/timestamp/cpuPercent/cpuMaxCore/memUsed/memTotal/swapUsed/netUpSpeed/netDownSpeed/diskUsed/diskTotal |
| `PluginConfigResource` | `PluginConfigSpec` | `PluginConfigResource` | `{instanceId}-{pluginName}` | instanceId/pluginName/pluginStatus/description/version/author/enableTime/isDeleted/remark |
| `DownloadTaskResource` | `DownloadTaskSpec` | `DownloadTaskResource` | `{instanceId}-{timestamp}` | instanceId/taskUrl/taskStatus/progress/filename/fileSize/downloadedSize/downloadSpeed/errorMessage/fileType/targetPath/startTime/completeTime/retryCount/maxRetry/isDeleted/remark |

**控制器迁移状态**：v2.0 起 `AdminController`（管理员 CRUD）与 `MonitorController`（监控指标历史）已从内存 mock / Random 数据迁移到 `ExtensionClient` 持久化；其他控制器（`RconController`/`MapController`/`ServerConfigController`/`PluginManageController`）保留原实现，后续可逐步迁移。

**控制器端点**（路径前缀 `/api/plugin/l4d2`）：

| 控制器 | 路径前缀 | 主要能力 |
|--------|----------|----------|
| `RconController` | `/rcon` | status/execute/change-map/kick/ban/change-difficulty/change-gamemode/set-max-players/map-list |
| `MapController` | `/maps` | 列表/上传/删除/刷新（VPK 地图） |
| `MonitorController` | `/monitor` | status/history/realtime/cpu-trend/memory-trend/network-trend |
| `AdminController` | `/admins` | 管理员 CRUD/重载/同步（SourceMod admins.cfg） |
| `ServerConfigController` | `/server-config` | 获取/更新/重载/文件内容读写 |
| `PluginManageController` | `/plugins` | **SourceMod 插件**（.smx）管理 + 预设（coop/versus/realism/survival） |

> 注意双层"插件"概念：平台用 PF4J 管理 L4D2 插件本身，而 L4D2 插件内部又通过 `PluginManageController` 管理 SourceMod 插件（.smx）。

#### 5.4.2 前端

`plugin-l4d2/frontend/`，独立 Vue 3 + TypeScript 工程，构建产物输出到 `src/main/resources/ui/`（嵌入 JAR）。页面：Dashboard/Maps/Plugins/Rcon/Monitor/Admins/ServerConfig/ServerInfo。通过 Wujie 被主应用加载。

---

## 6. 前端架构详解

### 6.1 入口与路由

**入口** [main.js](file:///d:/program/ai/game_platform_manger/frontend/src/main.js)：创建 Vue 应用，注册 Pinia/Router/Element Plus（中文）/所有 EP 图标/WujieVue。

**路由** [router/index.js](file:///d:/program/ai/game_platform_manger/frontend/src/router/index.js)：

- `createWebHistory` 模式，路由守卫 `beforeEach`：未登录访问受保护页 → 跳 `/login`；已登录访问 `/login` → 跳首页；`afterEach` 结束 NProgress。
- 主布局 `MainLayout.vue` 下嵌套子路由：

| 路径 | 页面 | 说明 |
|------|------|------|
| `/dashboard` | 仪表盘 | 首页 |
| `/host/list`、`/host/terminal/:id` | 主机列表、Web 终端 | XTerm.js |
| `/instance/list`、`/instance/detail/:id`、`/instance/deploy` | 实例列表/详情/部署 | |
| `/game/list` | 游戏列表 | |
| `/plugin` | 插件管理 | |
| `/plugin/:gameCode(.*)*` | **插件子应用** | `PluginTab.vue`，Wujie 加载 |
| `/docker/list`、`/docker/container/:id` | Docker 容器管理 | |
| `/system/settings`、`/system/logs` | 系统设置/日志 | |

### 6.2 状态管理（Pinia stores）

| Store | 职责 |
|-------|------|
| `user.js` | token/userInfo/permissions；`login`/`logout`/`fetchUserInfo`/`refreshToken`/`changePassword`/`hasPermission`；token 持久化到 localStorage |
| `host.js` | 主机列表/当前主机/加载状态 |
| `instance.js` | 实例列表/当前实例/`runningCount` 计算属性 |
| `backup.js` | 备份列表/进度 |
| `docker.js` | Docker 容器/镜像状态 |
| `app.js` | 应用级状态（主题等） |
| `plugins/stores/pluginStore.ts` | 插件清单/菜单/选中菜单/就绪状态；`loadManifest`（调 `/pf4j/plugin/{gameCode}/manifest`） |

### 6.3 API 层（`api/`）

每个文件封装一类接口，统一通过 `utils/request.js`（Axios 实例）调用：

| 文件 | 封装接口 |
|------|----------|
| `auth.js` | login/logout/getUserInfo/refreshToken/changePassword |
| `host.js` | 主机 CRUD/测试/状态/资源 |
| `instance.js` | 实例 CRUD/启停/配置/文件 |
| `game.js` | 游戏元数据 CRUD/扫描 |
| `plugin.js` | 插件 CRUD/启停 |
| `backup.js` | 备份创建/还原/下载/校验 |
| `docker.js` | Docker 容器/镜像/文件/关联 |
| `system.js` | 系统信息/设置/日志/统计 |

**`utils/request.js`**：`baseURL=/api`，请求拦截器注入 `Authorization: Bearer {token}`；响应拦截器判断 `code===0||200` 返回 `data.data`，401 弹框重新登录，统一错误提示。

### 6.4 微前端插件集成（`plugins/`）

平台采用 **Wujie 微前端** 加载插件子应用，主子应用通过 Wujie bus 通信。

| 文件 | 职责 |
|------|------|
| `wujie/apps.config.js` | 注册子应用列表（如 `l4d2-plugin` → `http://localhost:9000`，关联 gameCode） |
| `sdk/pluginSDK.ts` | **插件端 SDK**：从 `window.$wujie.props` 读初始数据，通过 `window.$wujie.bus` 收发事件；提供 `ready`/`navigate`/`notify`/`confirm`/`request`(get/post/put/delete) |
| `communication/pluginCommunication.ts` | **主应用端通信管理**：`usePluginCommunication` 组合式函数，监听子应用 READY/NAVIGATE/NOTIFY/CONFIRM/API_REQUEST；`handleApiRequest` 代理子应用 HTTP 请求（自动带 token） |
| `types/messageTypes.ts` | 消息类型与 Payload 定义（INIT/AUTH/THEME_CHANGE/CONFIRM_RESULT/READY/NAVIGATE/NOTIFY/CONFIRM/API_REQUEST/API_RESPONSE）+ `MessageTypes` 常量 + 类型守卫 |
| `stores/pluginStore.ts` | 插件清单/菜单/选中状态 |
| `components/PluginContainer.vue` | Wujie 容器，挂载子应用 |
| `components/PluginMenu.vue` | 插件菜单 |
| `components/PluginTab.vue` | 插件 Tab 宿主：左侧菜单 + 右侧 `PluginContainer`，监听 gameCode 加载清单 |

**通信流程**：

```
主应用                      Wujie bus                    插件子应用
  │  props(instance/auth/theme) ──────────────────────► │
  │                                                     │ ready()
  │ ◄──────────── {name}:READY ──────────────────────── │
  │  {name}:AUTH / {name}:THEME_CHANGE ───────────────► │
  │                                                     │
  │ ◄──────────── {name}:NAVIGATE/NOTIFY/CONFIRM ────── │
  │  处理后 {name}:CONFIRM_RESULT ────────────────────► │
  │                                                     │
  │ ◄──────────── {name}:API_REQUEST ────────────────── │
  │  代理请求(带token) → {name}:API_RESPONSE ─────────► │
```

事件名约定为 `{子应用name}:{type}`，子应用 name 形如 `plugin-{gameCode}`，保证同一游戏插件复用同一 Wujie 沙箱。

### 6.5 WebSocket（`utils/websocket.js`）

`WebSocketClient` 类：自动重连（可配次数/间隔）、心跳（`ping`/`pong`）、手动关闭标志。导出工厂函数：

- `createSSHTerminal({hostId, ...})` → `/ws/ssh/{hostId}`
- `createInstanceLogStream({instanceId, ...})` → `/ws/instance/{id}/logs`
- `createInstanceConsole({instanceId, ...})` → `/ws/instance/{id}/console`

连接时自动在 URL 附加 `?token=` 完成鉴权。`buildWebSocketUrl` 支持开发环境相对路径（Vite 代理）与生产环境完整 URL。

### 6.6 构建配置（`vite.config.js`）

- 自动导入：`unplugin-auto-import`（vue/vue-router/pinia + ElementPlusResolver）+ `unplugin-vue-components`（ElementPlusResolver），生成 `auto-imports.d.ts` / `components.d.ts`。
- 开发代理：`/api` → `http://localhost:8080`；`/ws` → `ws://localhost:8080`（重写为 `/api/ws`）。
- 构建：手动 chunks（element-plus / vue-vendor / xterm），SCSS 全局注入 `variables.scss`。

---

## 7. 数据库设计

SQLite 数据库，7 张核心表 + 迁移表（`db/migration/`）。

### 7.1 核心表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `sys_user` | 用户 | username(唯一)、password_hash、last_login_time/ip |
| `host_info` | 主机 | host_name、ip_address(唯一)、ssh_port/user、ssh_password/private_key(加密)、online_status、os_type、cpu_cores/memory_mb/disk_gb、cpu_usage/memory_usage/disk_usage、last_check_time |
| `game_metadata` | 游戏元数据 | game_name、game_code(唯一)、supported_deploy_types(JSON)、default_port、environment_deps(JSON)、deploy_config(JSON)、custom_operations(JSON) |
| `game_instance` | 游戏实例 | instance_name(唯一)、host_id(FK)、game_id(FK)、deploy_type、port_config(JSON)、run_status(0停止/1运行/2异常)、online_players、config_info(JSON)、install_path、start/stop_command、database_config(JSON)、last_backup_time |
| `plugin_info` | 插件信息 | plugin_id(唯一)、plugin_name、version、status(0禁用/1启用)、extension_points(JSON)、config_schema(JSON) |
| `operation_log` | 操作日志 | operator、operation_type、operation_target、operation_content、operation_result(success/fail)、ip_address、error_message |
| `backup_record` | 备份记录 | instance_id(FK)、backup_name、backup_type(FULL/INCREMENTAL)、target_type(DATABASE/FILES)、database_type(MYSQL/POSTGRESQL/SQLITE)、file_size/path/md5、status(0备份中/1成功/2失败)、progress(0-100)、source_path、retry_count |

### 7.2 通用字段

所有表含：`id`(自增)、`create_time`、`update_time`、`is_deleted`(逻辑删除 0/1)、`remark`。`game_instance` 用 `is_deleted`，部分表用 `deleted`（迁移表 `V1.1__add_missing_columns.sql` 处理差异）。

### 7.3 迁移与插件表

- `db/migration/`：`V1.1.0__plugin_framework.sql`、`V1.1__add_missing_columns.sql`、`V1.2__add_host_info_columns.sql`、`V1.3__add_docker_container_link.sql`、`V1.4__L4D2_plugin_tables.sql`（旧版独立表，v2.0 起废弃，保留供历史迁移）。
- v2.0 起插件持久化采用 Halo 风格统一宽表（见 5.2.4）：
  - 全局共享表 `extensions`（`Strategy.SHARED`）。
  - 插件级隔离表 `ext_{pluginId}`（`Strategy.PLUGIN_ISOLATED`）。
  - 模型级隔离表 `ext_{pluginId}_{kind}`（`Strategy.MODEL_ISOLATED`，L4D2 的 4 个模型均用此策略，由 `PluginSchemaManager.createSchemas` 在插件加载时自动建表）。
- 所有扩展表共用宽表 DDL：`name`/`group_name`/`kind`/`version`/`metadata`/`spec`/`status`/`creation_timestamp`/`update_timestamp`，复合主键 `(name, group_name, kind)`。

---

## 8. 游戏元数据机制

游戏通过 `core/src/main/resources/games/*.yml` 描述（minecraft/palworld/valheim/rust/l4d2），由 `GameMetadataScanner` 在启动时加载入库。

### 8.1 YAML 结构（以 minecraft.yml 为例）

| 顶层字段 | 说明 |
|----------|------|
| `game.code/name/description/version/icon` | 游戏基础信息 |
| `deployTypes` | 支持的部署方式（docker/linuxgsm/native） |
| `defaultPorts` | 默认端口（game/query/rcon） |
| `dependencies` | 环境依赖（java/memory/disk） |
| `docker` | Docker 部署配置（image/tag/env/volumes/ports/resources/healthCheck） |
| `linuxgsm` | LinuxGSM 部署配置（script/gameCode/configFile/installDir） |
| `configSchema` | **配置表单 Schema**：properties（字段类型/默认值/枚举/标签/component）、required、layout（columns/groups） |
| `customOperations` | 自定义操作（备份/清理日志/查看玩家/重启，含 confirm/async/timeout） |

### 8.2 配置表单驱动

`configSchema` 描述每个配置项的 `component`（number/select/switch/input），前端据此**自动生成可视化配置表单**，无需为每款游戏手写配置 UI。

---

## 9. 依赖关系总览

### 9.1 后端 Maven 依赖（核心）

```
core
├── game-platform-api        (内部)
├── game-platform-plugin     (内部)
├── spring-boot-starter-{web,validation,security,aop,websocket,thymeleaf}
├── mybatis-plus-spring-boot3-starter
├── sqlite-jdbc
├── pf4j
├── sshd-core + sshd-sftp    (Apache MINA SSHD)
├── docker-java-core + docker-java-transport-httpclient5
├── jjwt-{api,impl,jackson}
├── jackson-databind + jackson-datatype-jsr310
├── snakeyaml
├── ini4j
├── hutool-all
├── springdoc-openapi-starter-webmvc-ui
└── spring-boot-devtools (runtime)

plugin
├── game-platform-api        (内部)
├── pf4j
└── jackson-databind

api
├── jackson-databind + jackson-datatype-jsr310
├── swagger-annotations
└── jakarta.validation-api

plugin-l4d2 (全部 provided)
├── game-platform-{plugin,api,core}
├── pf4j
├── spring-boot-starter-{,web,security,thymeleaf}
├── jackson-databind
└── hutool-all
```

### 9.2 前端 NPM 依赖（核心）

| 依赖 | 用途 |
|------|------|
| `vue` / `vue-router` / `pinia` | 框架三件套 |
| `element-plus` + `@element-plus/icons-vue` | UI |
| `axios` | HTTP |
| `wujie` + `wujie-vue3` | 微前端 |
| `xterm` + `xterm-addon-{fit,search,web-links}` | Web 终端 |
| `dayjs` | 日期 |
| `nprogress` | 进度条 |
| `unplugin-auto-import` / `unplugin-vue-components` | 自动导入（dev） |
| `vitest` + `@vue/test-utils` + `happy-dom` | 测试（dev） |

### 9.3 模块间运行时依赖

- `core` 启动时通过 `PluginAutoLoader` 扫描 `plugins-dir` 加载 `plugin-l4d2.jar`。
- `plugin-l4d2` 的 `@RestController` 被动态注册到 `core` 的 `RequestMappingHandlerMapping`。
- `plugin-l4d2` 前端构建产物嵌入 JAR `ui/`，由 `PluginResourceController` 提供，Wujie 加载。
- 前端 `frontend` 通过 Vite 代理与 `core` 的 8080 端口通信；插件子应用（如 L4D2 前端）独立部署（开发 9000），由 Wujie 沙箱加载。

---

## 10. 项目运行方式

### 10.1 环境要求

- **JDK 17+**
- **Maven 3.6+**（后端构建）
- **Node.js 16+** + **npm**（前端构建）
- 操作系统：Windows / Linux / macOS（提供 bat/ps1/sh 三套脚本）

### 10.2 后端运行

```bash
cd backend

# 编译
mvn clean compile

# 开发模式运行（core 模块）
mvn spring-boot:run -pl core
# 或指定 profile
mvn spring-boot:run -pl core -Dspring-boot.run.profiles=dev

# 打包（生成 core/target/*.jar）
mvn clean package -DskipTests

# 生产运行
java -jar core/target/game-platform-core-1.0.0-exec.jar

# 测试
mvn test

# 代码覆盖率
mvn jacoco:report
```

**脚本方式**（`backend/scripts/`）：

```bash
# Windows
scripts\start.bat          # 生产环境（java -jar）
scripts\start.bat dev      # 开发环境（mvn spring-boot:run）
scripts\stop.bat
scripts\restart.bat
# Linux/macOS
./scripts/start.sh dev
```

脚本含 JVM 参数（G1GC、堆转储、GC 日志）、PID 管理、端口监听检测、健康检查（`/actuator/health`）。

启动后：

- 后端服务：`http://localhost:8080/api`
- Swagger UI：`http://localhost:8080/api/swagger-ui.html`
- OpenAPI：`http://localhost:8080/api/v3/api-docs`

### 10.3 前端运行

```bash
cd frontend

# 安装依赖
npm install

# 开发模式（端口 3000，代理 /api 与 /ws 到 8080）
npm run dev

# 构建生产版本
npm run build

# 预览
npm run preview

# 测试
npm run test           # watch
npm run test:run       # 单次
npm run test:coverage  # 覆盖率

# 代码检查
npm run lint
```

启动后访问 `http://localhost:3000`。

### 10.4 插件构建与部署

```bash
cd backend

# 编译 L4D2 插件
mvn clean package -pl plugin-l4d2 -am -DskipTests
# 产物：plugin-l4d2/target/plugin-l4d2-1.0.0.jar
```

**部署**：将 JAR 放入 `${user.home}/game-platform/plugins/`，重启主应用或调用热加载 API：

```bash
POST /api/pf4j/plugins/{pluginId}/reload
POST /api/pf4j/plugins/{pluginId}/start
POST /api/pf4j/plugins/{pluginId}/stop
```

**L4D2 插件前端独立构建**：

```bash
cd backend/plugin-l4d2/frontend
npm install
npm run build    # 产物输出到 src/main/resources/ui/，随 JAR 打包
```

### 10.5 数据目录

运行时在 `${user.home}/game-platform/` 下生成：

| 子目录 | 内容 |
|--------|------|
| `data/game_platform.db` | SQLite 数据库 |
| `plugins/` | 插件 JAR |
| `backups/` | 备份文件 |
| `storage/` | 文件存储 |
| `temp/` | 临时文件 |
| `logs/` | 日志 |

---

## 11. 关键设计要点与已知改进点

### 11.1 关键设计

1. **适配器模式解耦部署方式**：`DeployAdapter` 接口 + `DeployAdapterFactory` + 3 实现，`InstanceService`/`DeployService` 通过工厂按 `deployType` 获取适配器，新增部署方式只需实现接口并注册 Bean。

2. **PF4J 单一扩展插槽**：所有插件通过 `GameEnhancementExtension` 一个接口接入；`parentFirst=true` 保证扩展点接口在父 ClassLoader 统一加载，避免多插件类型转换失败。

3. **插件 Spring 子容器隔离**：每个插件独立 `AnnotationConfigApplicationContext`，父容器为主应用；子容器控制器动态注册到主 `RequestMappingHandlerMapping`，实现端点挂载与卸载。

4. **Halo 风格统一宽表存储**：插件持久化统一走 `@ExtensionModel` + `ExtensionClient`，三层隔离策略（SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED）适配不同场景；`ExtensionRouter` 在 SQL 构造时强制注入 `group_name`+`kind` 过滤，插件**无法**访问其他插件或主应用的数据；复合主键 `(name, group_name, kind)` + 乐观锁 `version` 列保证并发安全。

5. **路径冲突检测**：`PluginSpringContextFactory.registeredPaths` 记录所有已注册 URL，新插件注册前检测冲突，抛 `PluginPathConflictException`。

6. **Wujie 微前端集成**：主应用通过 `PluginResourceController` 提供带 CORS 的子应用静态资源；`PluginManifestVO.frontendEntry` 作为 Wujie 子应用入口；主子应用通过 `bus.$emit/$on` 以 `{name}:{type}` 事件名通信，主应用代理子应用 API 请求自动带 token。

7. **配置驱动 UI**：游戏 YAML `configSchema` 描述表单字段，前端自动生成配置界面，新增游戏无需改前端代码。

8. **异步与可取消**：日志记录、备份、部署均为 `@Async`；备份用 `AtomicBoolean` 支持取消。

9. **统一响应与异常**：`Result<T>` 包装 + `GlobalExceptionHandler` 全局异常处理 + `@OperationLog` AOP 日志。

10. **宿主机 hosts 刷新与容器共享**：`HostsFileRefresher` 通过 SSH 读取宿主机 `/etc/hosts`，将 127.0.0.1 回环域名改为 LAN IP（支持用户选择性刷新），`sudo cp` 覆盖后刷新 DNS 缓存。部署时若启用 `mountHostCerts`，三个 Docker 类适配器会将宿主机 `/etc/hosts` 只读挂载到容器，使容器直接共享宿主机刷新后的 hosts 解析结果，避免 bridge 网络下容器 DNS 解析到 127.0.0.1 导致反向代理失败。

11. **SshUtil 连接池复用**：采用共享 `SshClient` + `ConcurrentHashMap<HostKey, CachedSession>` 会话池，按 `host+port+username` 复用已认证会话；后台守护线程每 60s 清理闲置超时会话（5min）；SFTP 和命令执行通过 `executeWithRetry` 自动重建失效会话。相比每次新建连接（约 3.3s），metrics 接口从 14.9s 降至 2.1s（约 7 倍提升）。

### 11.2 已知改进点（代码中 TODO）

- `UserServiceImpl` 密码哈希用 SHA-256，与 AGENTS.md 所述 BCrypt 不一致；`SecurityConfig` 已提供 `BCryptPasswordEncoder` Bean 但未使用。
- `HostServiceImpl` AES 密钥硬编码 `GamePlatform2024`，应从配置读取。
- 多个 ServiceImpl 的 `getCurrentUser()` 硬编码返回 `"admin"`（仅 `InstanceServiceImpl` 正确从 SecurityContext 获取）。
- `HostController.scanPorts` 返回空列表，未实现端口扫描。
- `DockerLinkController.getUserId` 硬编码 `1L`。
- `MonitorController.getStatus` 仍返回 Random 实时值（v2.0 已将历史查询迁移到 `ExtensionClient` 持久化，但实时指标采集仍为 mock）。
- `PluginLifecycleHook.onInstanceStart/Stop` 传硬编码 `0L`（应为实际 instanceId）。
- `RconController.getHostFromInstance` 硬编码 `127.0.0.1`。
- `PluginManageController` 仍用内存缓存代替数据库表（v2.0 已定义 `PluginConfigResource` 扩展模型但控制器尚未迁移）。

---

*文档生成时间：2026-07-14 · 最后更新：2026-07-19（hosts 刷新 + mountHostCerts + SshUtil 连接池）*
