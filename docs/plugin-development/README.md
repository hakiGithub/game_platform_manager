# GamePlatform 插件开发指南

> 面向开源贡献者的插件开发文档。本指南覆盖 GamePlatform（PF4J + Spring 子容器 + Wujie 微前端）平台的插件开发全流程：从新建插件骨架、声明菜单、持久化扩展资源、调用宿主能力，到异步任务、定时任务与插件前端集成。

GamePlatform 是一个面向游戏服务器运维的轻量级管理后台。平台自身只提供**插件框架**与**宿主能力面**，具体游戏的功能（菜单、配置、任务、前端页面）由插件以扩展点方式注入。本指南帮助你在不改动主应用的前提下，为某个游戏开发并发布一个可被平台加载的插件。

完整 API 与架构背景见仓库根目录的 [架构文档](../architecture/ARCHITECTURE.md)、[API 文档](../api/api-doc.md) 与 [架构决策记录（ADR）](../design/adr/)。

---

## 本文档能帮你做什么

- 为本平台开发新游戏插件（`plugin-{gameCode}`）
- 排查插件加载失败、菜单不显示 / 白屏、持久化越权、任务卡死等问题
- 实现 / 调试 `TaskHandler`、定时任务、`ExtensionClient`、宿主服务调用、`getMenus()` 菜单声明
- 基于示例插件快速跑通一个最小可运行插件

---

## 文档导航

| 文档 | 内容 | 适用场景 |
|---|---|---|
| [reference/getting-started.md](reference/getting-started.md) | 版本约定、快速开始、项目结构、`plugin.properties`、Plugin 入口、`pom.xml` | 新建插件模块、配置骨架 |
| [reference/extension-and-menus.md](reference/extension-and-menus.md) | `GameEnhancementExtension` 实现、`getManifest`、`getConfigFields`、生命周期钩子、**`getMenus()` + `PluginMenuDeclaration`（ADR-0001）**、`PluginManifestVO` 契约、菜单加载机制 | 实现扩展点、声明菜单、清单契约 |
| [reference/persistence.md](reference/persistence.md) | `@ExtensionModel` 存储策略、`ExtensionClient` 全方法、`ListOptions`、安全约束 | 持久化扩展资源、CRUD |
| [reference/host-services.md](reference/host-services.md) | `HostQueryService` / `InstanceQueryService` / `InstanceFileService` / `FileAccessService` / `SshTunnelService`、`configInfo.database` 组装、控制器规范 | 调用主机 / 实例 / 文件能力、SSH 隧道、实例数据库连接信息 |
| [reference/async-tasks.md](reference/async-tasks.md) | `TaskHandler` / `TaskHandlerExtension` / `TaskService`、注册 / 实现 / 提交、进度节流、取消超时、互斥键、生命周期钩子 | 异步任务开发 |
| [reference/scheduled-tasks.md](reference/scheduled-tasks.md) | `ScheduledTaskHandler` / `ScheduledTaskDeclarationExtension` / `ScheduleService`、声明式默认计划、来源隔离、cron 触发、重叠 SKIPPED、插件生命周期联动（[ADR-0011](../design/adr/0011-scheduled-task-management.md)） | 定时任务开发 |
| [reference/frontend.md](reference/frontend.md) | 两运行模式（`detectMode`）、Wujie 通信、前端目录结构、前端约定 | 双端插件前端开发 |
| [reference/exceptions.md](reference/exceptions.md) | 异常类层级、框架异常使用、基于真实异常的 FAQ | 异常处理、问题排查 |
| [reference/walkthrough-l4d2.md](reference/walkthrough-l4d2.md) | `plugin-l4d2` 完整双端参考实现剖析（后端入口 / 扩展资源 / 控制器 / 任务 / 前端） | 对照参考实现开发新插件 |
| [reference/checklist.md](reference/checklist.md) | 路径常量速查、安全配置约定、发布检查清单、验收标准 | 发布前自检、路径常量查阅 |
| [reference/sdk-reference.md](reference/sdk-reference.md) | 扩展点、`ExtensionClient`、宿主服务面、`PluginManifestVO`、`PluginConstants` 接口签名速查 | 编码时查阅方法签名 |
| [reference/gotchas.md](reference/gotchas.md) | 菜单机制陷阱（ADR-0001）、范围隔离规约（ADR-0002）、路径对齐、异常层级、文件路径安全、任务约束、子容器静默失败、无 RCON 控制台通道 | 排查非显而易见的问题 |
| [CHANGELOG.md](CHANGELOG.md) | 版本与维护约定、历史快照、Changelog | 查阅版本演进、维护文档 |

---

## 关键架构事实（必读）

1. **插件三形态**：纯后端 / 纯前端（当前无实现，预留）/ 双端。
2. **菜单归属权在插件（[ADR-0001](../design/adr/0001-plugin-menu-ownership.md)）**：插件通过 `GameEnhancementExtension.getMenus()` 返回 `List<PluginMenuDeclaration>` 声明菜单清单；宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 仅做 path 唯一性校验与 `requireInstance` 默认值补全，**不预置任何默认菜单**。
   - `getManifest()` 的 `features` 字段**已废弃**，宿主不再读取；新插件不要写 `features`，旧插件需迁移到 `getMenus()`。
   - 主应用从 `manifest.frontend.menus` 的 path 集合推导 capabilities（不再依赖 features）。
3. **前端两运行模式**（[ADR-0003](../design/adr/0003-deprecate-plugin-l4d2-standalone.md)）：`detectMode()` → wujie（hash 路由）/ dev（`/`）。`standalone` 模式已废弃。
4. **持久化唯一入口**：`ExtensionClient`，绑定 pluginId，自动 `group_name` + `kind` 身份过滤。`PluginContext` 仅持元数据，不持数据访问。
5. **双端配对链路**：`getMenus()` → 宿主 `buildMenusFromDeclarations` 校验序列化 → `GET /api/pf4j/plugin/{gameCode}/manifest` → 主应用侧边栏 → Wujie 加载子应用。子应用路由 path 必须与 `getMenus()` 声明的 path 严格对齐。
6. **`PluginMenuDeclaration` 强类型**：`title` / `path` / `icon` / `order` / `parent` / `requireInstance`（默认 `true`，纯资源页如地图中心显式设 `Boolean.FALSE`）。同插件内 path 重复或为空抛 `IllegalStateException`。
7. **范围隔离（[ADR-0002](../design/adr/0002-main-app-plugin-scope-isolation.md)）**：主应用 `core/` 与插件严格隔离——主应用配置文件不得包含 `plugin.{gameCode}` 前缀；主应用迁移目录不得包含 `{gameCode}_*` 前缀插件专属表；主应用代码不得 import 插件业务包。例外：游戏元数据 `core/resources/games/{gameCode}.yml` 由主应用维护。
8. **废弃 standalone 模式（[ADR-0003](../design/adr/0003-deprecate-plugin-l4d2-standalone.md)）**：`plugin-l4d2-standalone` 已物理删除，新增插件**不应**实现 standalone 独立运行模式。前端只支持 wujie + dev 两种模式。
9. **部署策略**：只改插件代码用 `bash scripts/deploy-plugin.sh` 热部署（构建插件 → PF4J API 卸载释放 jar 文件锁 → 覆盖 `plugins/` 下的 jar → `POST /api/pf4j/plugins/load?jarName=...` 加载启动，**后端不重启**）；改主应用代码（core/api/plugin 模块）才用 `start-all.sh` 重启。部署**独立仓库**构建的 jar：`PLUGIN_ID={id} JAR_NAME={jar} bash scripts/deploy-plugin.sh --skip-build --jar /path/to.jar`。
10. **插件前端 token 隔离（[ADR-0007](../design/adr/0007-plugin-frontend-nightops-token-isolation.md)）**：Wujie shadow DOM 不继承宿主 CSS 变量，插件前端须**复制**主应用 `frontend/src/styles/variables.scss` 的 `--platform-*` token 副本自管（暗色单主题，无明暗切换）。
11. **平台能力三项扩展（[ADR-0009](../design/adr/0009-platform-capability-requirements.md)）**：① `SshTunnelService` SPI——插件经宿主 SSH 开本地端口转发隧道；② `configInfo.database` 组装——带 DB 的 compose 游戏在 yml 声明；③ `onInstanceUpdate` 钩子——实例配置更新后收到完整新 configInfo。
12. **定时任务体系（[ADR-0011](../design/adr/0011-scheduled-task-management.md)）**：独立于任务中心的 cron 周期性执行体系，三张表（`scheduled_task` / `scheduled_task_run` / `scheduled_task_run_log`）。插件两条渠道定义计划：声明式（`ScheduledTaskDeclarationExtension`）与编程式（`ScheduleService`）。`ScheduledTaskHandler` 与任务中心 `TaskHandler` 完全分离。

---

## 可用类速查（接入规范）

### 扩展点（`backend/plugin/.../extension/`）
| 类 / 接口 | 用途 | 详见 |
|---|---|---|
| `GameEnhancementExtension` | 游戏增强扩展点，插件后端入口；含 `getMenus()`、`getManifest()`、`getConfigFields()`、生命周期钩子 | [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| `PluginMenuDeclaration` | 菜单声明强类型（[ADR-0001](../design/adr/0001-plugin-menu-ownership.md)）；`@Builder` + `requireInstance` 默认 `true` | [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| `TaskHandlerExtension` | 任务处理器注册扩展点 | [reference/async-tasks.md](reference/async-tasks.md) |
| `TaskHandler` | 任务处理器接口（`getType` / `execute` / `isRetryable` / `getMaxRetryCount` / `onSubmit`） | [reference/async-tasks.md](reference/async-tasks.md) |
| `ScheduledTaskHandler` | 定时任务处理器（`@Component` 一个 Handler，getKey / execute，无重试无互斥） | [reference/scheduled-tasks.md](reference/scheduled-tasks.md) |
| `ScheduledTaskDeclarationExtension` | 声明式默认计划扩展点（返回 `List<ScheduleDeclaration>`，按 pluginId:key upsert） | [reference/scheduled-tasks.md](reference/scheduled-tasks.md) |
| `PluginConfigField` | 插件配置字段定义（前端自动渲染表单） | [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| `AbstractExtension<T>` | 扩展资源基类（持 id / name / spec / version / status / metadata） | [reference/persistence.md](reference/persistence.md) |
| `@ExtensionModel` | 资源模型注解（声明存储策略 SHARED / PLUGIN_ISOLATED / MODEL_ISOLATED） | [reference/persistence.md](reference/persistence.md) |

### 持久化与服务面
| 类 / 接口 | 用途 | 详见 |
|---|---|---|
| `ExtensionClient` | 插件唯一持久化入口（CRUD + status + count + getManagedTables） | [reference/persistence.md](reference/persistence.md) |
| `HostQueryService` | 主机查询（CPU / 内存 / 磁盘监控 + 主机详情） | [reference/host-services.md](reference/host-services.md) |
| `InstanceQueryService` | 实例查询与控制（启停重启 + 日志 + 控制台命令） | [reference/host-services.md](reference/host-services.md) |
| `InstanceFileService` | 实例感知文件 SPI（自动路由 SFTP / docker exec，禁止 `..`） | [reference/host-services.md](reference/host-services.md) |
| `FileAccessService` | 主机级 SFTP + `executeCommand` | [reference/host-services.md](reference/host-services.md) |
| `SshTunnelService` | SSH 本地端口转发隧道（[ADR-0009](../design/adr/0009-platform-capability-requirements.md)） | [reference/host-services.md](reference/host-services.md) |
| `TaskService` | 任务提交 / 查询 / 取消（注入子容器） | [reference/async-tasks.md](reference/async-tasks.md) |
| `ScheduleService` | 定时计划编程式服务（注入子容器，自动绑定本插件 source） | [reference/scheduled-tasks.md](reference/scheduled-tasks.md) |
| `PluginContext` | 插件元数据上下文（仅元数据，不持数据访问） | [reference/extension-and-menus.md](reference/extension-and-menus.md) |

### 契约与常量
| 类 / 接口 | 用途 | 详见 |
|---|---|---|
| `PluginManifestVO` | 插件清单契约（含 `frontend.menus` / `MenuConfig.requireInstance`） | [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| `PluginConstants` | 路径常量（`/api/plugin/{gameCode}`、`/api/pf4j/plugin/{gameCode}/ui/**`） | [reference/checklist.md](reference/checklist.md) |
| `ListOptions` | 条件查询选项（status / label / specFilter / limit / offset / orderBy） | [reference/persistence.md](reference/persistence.md) |

### 异常体系
| 异常类 | 触发场景 | 详见 |
|---|---|---|
| `PluginException`（基类） | 持 pluginId | [reference/exceptions.md](reference/exceptions.md) |
| `PluginLoadException` | 加载失败（DDL / 依赖） | [reference/exceptions.md](reference/exceptions.md) |
| `PluginConfigException` | 配置缺失 | [reference/exceptions.md](reference/exceptions.md) |
| `PluginPathConflictException` | 控制器路径冲突 | [reference/exceptions.md](reference/exceptions.md) |
| `ExtensionStoreException`（基类） | 扩展资源存储 | [reference/exceptions.md](reference/exceptions.md) |
| `DuplicateExtensionException` | create 时 name 冲突 | [reference/exceptions.md](reference/exceptions.md) |
| `OptimisticLockException` | update 时 version 不匹配 | [reference/exceptions.md](reference/exceptions.md) |
| `ExtensionNotFoundException` | get / update / delete 目标缺失 | [reference/exceptions.md](reference/exceptions.md) |

> 完整签名见 [reference/sdk-reference.md](reference/sdk-reference.md)。**注意**：`PluginDataAccessException` 不存在，数据访问异常用 `ExtensionStoreException` 体系。

---

## 开发流程（分层）

### 第 1 层：最小后端插件
→ [reference/getting-started.md](reference/getting-started.md)

1. 建 Maven 模块 `plugin-{gameCode}`，依赖 `game-platform-plugin`（`provided` scope）。
2. 写 `plugin.properties`（必填 `plugin.id` / `plugin.class` / `plugin.version`，可选 `plugin.gameCode` / `plugin.basePackage`）。
3. 写 `{GameCode}Plugin extends Plugin`（PF4J 入口，仅生命周期日志）。
4. 写 `@Extension {GameCode}Extension implements GameEnhancementExtension`，实现 `getGameCode` / `getGameName` / `getVersion` / `getDescription` / `getBasePackage`。
5. 双端插件需实现 `getMenus()` 返回 `List<PluginMenuDeclaration>`（见第 5 层）。
6. 打包单 JAR 放入主应用 `plugins/`，启动后 `GET /api/pf4j/plugin/{gameCode}/manifest` 验证（应返回 `frontend.menus`）。

### 第 2 层：持久化与控制器
→ [reference/persistence.md](reference/persistence.md)

1. 定义业务 `Spec`（POJO）+ `{Resource} extends AbstractExtension<Spec>`，标 `@ExtensionModel(strategy=...)`。
2. 控制器路径必须以 `/api/plugin/{gameCode}/` 开头；注入 `ExtensionClient` 做 CRUD。
3. 需读主机 / 实例 / 文件时注入宿主服务面（第 3 层）。

### 第 3 层：宿主服务面
→ [reference/host-services.md](reference/host-services.md)

- `HostQueryService`：主机详情 + 资源监控。
- `InstanceQueryService`：实例查询 + 启停重启 + 日志 + 控制台命令。
- `InstanceFileService`：实例感知文件 SPI，自动路由 SFTP / docker exec；`relativePath` 相对游戏数据根目录，禁止 `..`。
- `FileAccessService`：主机级 SFTP + `executeCommand`。

签名见 [reference/sdk-reference.md](reference/sdk-reference.md)。

### 第 4 层：异步任务与定时任务
→ [reference/async-tasks.md](reference/async-tasks.md) + [reference/scheduled-tasks.md](reference/scheduled-tasks.md)

异步任务：
1. 写 `TaskHandler implements`，实现 `getType` / `execute` / `isRetryable` / `getMaxRetryCount` / `getDefaultTimeoutMs` / `onSubmit`。
2. 写 `@Component TaskHandlerExtension`，构造时缓存 `Map<taskType, TaskHandler>`。
3. 注入 `TaskService` 提交任务（`source` 自动填 gameCode 大写）。
4. `execute` 循环必须检查 `isCancelled()` / `isTimeout()`；进度用 `reportProgress`（已节流）。

定时任务（[ADR-0011](../design/adr/0011-scheduled-task-management.md)，独立模型）：
1. 写 `@Component ScheduledTaskHandler`（一个 Handler 一个 key，`getKey` / `getDisplayName` / `execute`）。
2. （可选）写 `@Component ScheduledTaskDeclarationExtension` 声明默认计划，或注入 `ScheduleService` 运行时创建。
3. 默认计划与用户改过的计划不冲突（upsert 跳过 userModified），用户在界面上可自行启停 / 改 cron。

### 第 5 层：菜单声明与前端子应用
→ [reference/extension-and-menus.md](reference/extension-and-menus.md) + [reference/frontend.md](reference/frontend.md)

1. 在 `{GameCode}Extension` 实现 `getMenus()`，返回 `List<PluginMenuDeclaration>`（含全部菜单，宿主不预置默认菜单）。
   - 同插件内 `path` 必须唯一（重复抛 `IllegalStateException`）。
   - 纯资源浏览页（如地图中心）显式 `.requireInstance(Boolean.FALSE)`。
2. 建 `plugin-{gameCode}/frontend`（Vue 3 + Vite），`utils/runtime.ts` 实现 `detectMode()`。
3. `router/index.ts` 路由 path 必须与 `getMenus()` 声明的 path **严格对齐**（否则点击菜单白屏）。
4. Wujie 模式用 `createWebHashHistory()`；通过 `window.$wujie.props` 读初始数据，`window.$wujie.bus` 收发事件。
5. standalone 模式已废弃（[ADR-0003](../design/adr/0003-deprecate-plugin-l4d2-standalone.md)），新增插件不应实现。

---

## 排查速查

| 症状 | 先查 |
|---|---|
| 插件加载失败 | `PluginLoadException`；依赖 gameCode 是否已加载；`plugin.properties` 必填项 → [reference/exceptions.md](reference/exceptions.md) |
| 菜单不显示 | `getMenus()` 是否实现并返回非空列表；`GET /api/pf4j/plugin/{gameCode}/manifest` 的 `frontend.menus` 字段 → [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| 菜单点击白屏 | 子应用路由 path 与 `getMenus()` 声明的 path 是否对齐 → [reference/gotchas.md](reference/gotchas.md) |
| `IllegalStateException`：菜单 path 重复 / 为空 | `getMenus()` 返回值校验：path 非空、同插件内唯一 → [reference/extension-and-menus.md](reference/extension-and-menus.md) |
| `features` 字段无效 | [ADR-0001](../design/adr/0001-plugin-menu-ownership.md) 已废弃 features，迁移到 `getMenus()` → [reference/gotchas.md](reference/gotchas.md) |
| 持久化越权报错 | 是否用了未声明的 `@ExtensionModel` 类；`ExtensionClient` 绑定 pluginId → [reference/persistence.md](reference/persistence.md) |
| update 报版本冲突 | `OptimisticLockException`；重新 `get` 拿 version 再更新 → [reference/exceptions.md](reference/exceptions.md) |
| 任务卡死 | `execute` 是否漏检 `isCancelled` / `isTimeout` → [reference/async-tasks.md](reference/async-tasks.md) |
| 定时计划不触发 | cron 合法性 / enabled·paused 状态 / Handler 是否注册（key 与计划 handlerKey 一致）→ [reference/scheduled-tasks.md](reference/scheduled-tasks.md) |
| 定时触发即 FAILED | run 的 errorMessage；handlerKey 未注册 / Handler 异常 → [reference/scheduled-tasks.md](reference/scheduled-tasks.md) |
| 文件操作 IllegalArgumentException | `relativePath` 含 `..` 越界 → [reference/host-services.md](reference/host-services.md) |
| 连不上实例数据库 | `configInfo.database` 是否存在（老实例裸变量回退）；隧道是否开启（连 `127.0.0.1:handle.localPort`）→ [reference/host-services.md](reference/host-services.md) |
| 连接池用了旧密码 / 端口 | 是否消费 `onInstanceUpdate`（改配置后失效重建）；该钩子每次更新都触发 → [reference/host-services.md](reference/host-services.md) |

---

## 示例插件

### 在线示例（`examples/plugin-mygame/`）

[`examples/plugin-mygame/`](examples/plugin-mygame/) 是一个**最小可运行双端插件示例**，自包含、不依赖 `plugin-l4d2` 源码即可理解，是开发新插件的推荐起点。

| 文件 | 演示能力 |
|---|---|
| `examples/plugin-mygame/pom.xml` | Maven 依赖宿主 `game-platform-plugin` / `game-platform-api`（provided scope），PF4J Manifest 写入 |
| `examples/plugin-mygame/src/main/resources/plugin.properties` | PF4J 必填项（plugin.id / class / version）+ gameCode / basePackage |
| `examples/plugin-mygame/src/main/java/.../MyGamePlugin.java` | PF4J 入口（生命周期日志） |
| `examples/plugin-mygame/src/main/java/.../MyGameExtension.java` | `GameEnhancementExtension` 实现 + `getMenus()` 菜单声明（ADR-0001）+ `getManifest()` 自描述 |
| `examples/plugin-mygame/src/main/java/.../extension/NoteResource.java` | `@ExtensionModel(strategy=MODEL_ISOLATED)` 扩展资源 |
| `examples/plugin-mygame/src/main/java/.../extension/NoteSpec.java` | 业务 Spec POJO |
| `examples/plugin-mygame/src/main/java/.../controller/NoteController.java` | REST 控制器（路径 `/api/plugin/mygame/notes`）+ `ExtensionClient` CRUD（含乐观锁） |
| `examples/plugin-mygame/frontend/` | Vue 3 + Vite 前端（`detectMode()` 三模式、`router` path 对齐、API 封装、Pinia store） |

**使用方式**：
1. 复制 `examples/plugin-mygame/` 到你的插件工程。
2. 后端：`mvn clean package`，将 JAR 放入主应用 `plugins/` 目录。
3. 前端：`cd frontend && npm install && npm run build`（产物自动输出到后端 `ui/`）。
4. 开发模式：`npm run dev`（端口 3100，proxy 转发 `/api` 到主应用 8080）。

### 完整参考实现（`backend/plugin-l4d2/`）

`backend/plugin-l4d2/` 是完整双端参考实现（core / frontend 两件套），含 20 项菜单、爬虫、RCON、地图、SourceMod 插件管理等完整能力。开发新插件时对照其 `L4D2Extension`、`L4D2Plugin`、`plugin.properties`、`extension/` 资源类、`frontend/src/router/index.ts`。完整剖析见 [reference/walkthrough-l4d2.md](reference/walkthrough-l4d2.md)。

> 外部项目无法读取 `backend/plugin-l4d2/` 源码时，以 `examples/plugin-mygame/` 为起点，按需参考 [reference/](reference/) 文档扩展能力。

---

## 贡献与维护说明

- 本文档是插件开发文档的**唯一权威源**。接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记到对应 `reference/` 文件。
- 主版本变更（破坏性 API 改动）→ 在 [CHANGELOG.md](CHANGELOG.md) 升版本号并记录，并将旧版另存归档。
- 关联 ADR：[ADR-0001 插件菜单归属](../design/adr/0001-plugin-menu-ownership.md)、[ADR-0002 范围隔离](../design/adr/0002-main-app-plugin-scope-isolation.md)、[ADR-0003 废弃 standalone](../design/adr/0003-deprecate-plugin-l4d2-standalone.md)、[ADR-0007 前端 token 隔离](../design/adr/0007-plugin-frontend-nightops-token-isolation.md)、[ADR-0009 平台能力](../design/adr/0009-platform-capability-requirements.md)、[ADR-0011 定时任务](../design/adr/0011-scheduled-task-management.md)。
- 文档中相对路径（`backend/...`、`scripts/...`、`docs/...`）均以**仓库根目录**为基准解析。
