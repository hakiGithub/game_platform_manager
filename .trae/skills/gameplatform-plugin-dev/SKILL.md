---
name: gameplatform-plugin-dev
description: GamePlatform 游戏服务器管理平台的插件开发与排查技能（用户级副本，跨项目可用）。在为 GamePlatform 平台（PF4J + Spring 子容器 + Wujie 微前端）开发新游戏插件、排查插件加载/菜单/持久化/任务/前端主题问题、或更新插件开发文档时使用，当前项目无需是平台仓库本身。涵盖扩展点（GameEnhancementExtension.getMenus/TaskHandlerExtension）、ExtensionClient 持久化、宿主服务面（HostQueryService/InstanceQueryService/InstanceFileService/FileAccessService）、菜单声明机制（ADR-0001：PluginMenuDeclaration + buildMenusFromDeclarations）、前端三运行模式、standalone 模式、异常体系。权威来源为 backend/plugin/ 与本 SKILL 目录（references/ 下分主题文档）。
agent_created: true
---

# GamePlatform 插件开发

> **用户级副本说明**：本 SKILL 复制自平台仓库 `D:\program\ai\game_platform_manger\.trae\skills\gameplatform-plugin-dev\`（v3.7.0）。文档内的相对路径链接（如 `backend/plugin/`、`docs/design/adr/...`、`scripts/deploy-plugin.sh`）均以**平台仓库根目录**为基准解析，在其它项目中使用时请对照该仓库。平台仓库更新后应重新同步本副本（含 `references/` 与 `examples/`）。

## 概述

本技能为 GamePlatform 平台（PF4J + Spring 子容器 + Wujie 微前端）的插件开发与排查提供程序性知识。完整开发指南位于本 SKILL 目录下的 `references/` 分主题文件中（v3.1.0 起，原 `docs/PLUGIN_DEV_GUIDE.md` 已删除并迁移到此；当前版本 v3.7.0，见 `references/changelog.md`）。

## 文档导航（references/ 索引）

| 文件 | 内容 | 适用场景 |
|---|---|---|
| `references/getting_started.md` | 版本约定、快速开始、项目结构、plugin.properties、Plugin 入口、pom.xml | 新建插件模块、配置骨架 |
| `references/extension_and_menus.md` | GameEnhancementExtension 实现、getManifest、getConfigFields、生命周期钩子、**getMenus + PluginMenuDeclaration（ADR-0001）**、PluginManifestVO 契约、菜单加载机制 | 实现扩展点、声明菜单、清单契约 |
| `references/extension_client.md` | @ExtensionModel 存储策略、ExtensionClient 全方法、ListOptions、安全约束 | 持久化扩展资源、CRUD |
| `references/host_services.md` | HostQueryService / InstanceQueryService / InstanceFileService / FileAccessService / **SshTunnelService（v3.7.0 ADR-0009）**、configInfo.database 组装、控制器规范 | 调用主机/实例/文件能力、SSH 隧道、实例数据库连接信息、路径前缀约束 |
| `references/task_handler.md` | TaskHandler / TaskHandlerExtension / TaskService、注册/实现/提交、进度节流、取消超时、互斥键、生命周期钩子 | 异步任务开发 |
| `references/frontend.md` | 两运行模式（detectMode）、Wujie 通信、前端目录结构、前端约定 | 双端插件前端开发 |
| `references/exceptions.md` | 异常类层级、框架异常使用、基于真实异常的 FAQ | 异常处理、问题排查 |
| `references/walkthrough_l4d2.md` | plugin-l4d2 完整双端参考实现剖析（后端入口/扩展资源/控制器/任务/前端） | 对照参考实现开发新插件 |
| `references/checklist.md` | 路径常量速查、安全配置约定、发布检查清单、验收标准 | 发布前自检、路径常量查阅 |
| `references/sdk_reference.md` | 扩展点（含 getMenus/PluginMenuDeclaration）、ExtensionClient、宿主服务面、PluginManifestVO、PluginConstants 接口签名速查 | 编码时查阅方法签名 |
| `references/gotchas.md` | 菜单机制陷阱（ADR-0001）、范围隔离规约（ADR-0002）、路径对齐、异常层级、文件路径安全、任务约束、**独立仓库构建陷阱（v3.6.0）**、子容器静默失败/`@Scheduled` 疑点、无 RCON 控制台通道 | 排查非显而易见的问题 |
| `references/changelog.md` | 版本与维护约定、历史快照、Changelog | 查阅版本演进、维护文档 |

## 何时使用

- 为本平台开发新游戏插件（plugin-{gameCode}）
- 排查插件加载失败、菜单不显示/白屏、持久化越权、任务卡死等问题
- 实现/调试 TaskHandler、ExtensionClient、宿主服务调用、`getMenus()` 菜单声明
- 更新或校对本 SKILL 目录文档与代码一致性

## 关键架构事实（必读）

1. **插件三形态**：纯后端 / 纯前端（当前无实现，预留）/ 双端。
2. **菜单归属权在插件（ADR-0001，v3.1.0）**：插件通过 `GameEnhancementExtension.getMenus()` 返回 `List<PluginMenuDeclaration>` 声明菜单清单；宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 仅做 path 唯一性校验与 `requireInstance` 默认值补全，**不预置任何默认菜单**。
   - `getManifest()` 的 `features` 字段**已废弃**，宿主不再读取；新插件不要写 `features`，旧插件需迁移到 `getMenus()`。
   - `getManifest()` 中的 `frontend.menus` 字段（如存在）会被忽略。
   - 主应用从 `manifest.frontend.menus` 的 path 集合推导 capabilities（不再依赖 features）。
3. **前端两运行模式**（ADR-0003，v3.3.0）：`detectMode()` → wujie(hash 路由) / dev(`/`)。`standalone` 模式已废弃。
4. **持久化唯一入口**：`ExtensionClient`，绑定 pluginId，自动 `group_name`+`kind` 身份过滤。`PluginContext`（v3.0+）仅持元数据，不持数据访问。
5. **双端配对链路**：`getMenus()` → 宿主 `buildMenusFromDeclarations` 校验序列化 → `/api/pf4j/plugin/{gameCode}/manifest` → 主应用侧边栏 → Wujie 加载子应用。子应用路由 path 必须与 `getMenus()` 声明的 path 严格对齐。
6. **PluginMenuDeclaration 强类型**（v3.1.0 新增）：`title`/`path`/`icon`/`order`/`parent`/`requireInstance`（默认 true，纯资源页如地图中心显式设 `Boolean.FALSE`）。同插件内 path 重复或为空抛 `IllegalStateException`。
7. **范围隔离（ADR-0002，v3.2.0）**：主应用 `core/` 与插件严格隔离——
   - 主应用配置文件（`application.yml`）**不得包含** `plugin.{gameCode}` 前缀的插件业务配置；插件配置由 `@ConfigurationProperties` 字段 Java 默认值自负，需要覆盖时由环境变量处理。
   - 主应用迁移目录（`db/migration/`）**不得包含** `{gameCode}_*` 前缀的插件专属表；插件表由 ExtensionClient 的 `ext_plugin_{pluginId}_{resource}` 模式通过 `DdlTemplate` 动态建表。
   - 主应用代码**不得 import** `com.gameplatform.plugin.{gameCode}.*` 插件业务包。
   - 例外：游戏元数据 `core/resources/games/{gameCode}.yml` 由主应用维护（部署向导输入）。
   - 详见 [ADR-0002](../../../docs/design/adr/0002-main-app-plugin-scope-isolation.md)。
8. **废弃 standalone 模式（ADR-0003，v3.3.0）**：`plugin-l4d2-standalone` 已物理删除，新增插件**不应**实现 standalone 独立运行模式。前端只支持 wujie + dev 两种模式。详见 [ADR-0003](../../../docs/design/adr/0003-deprecate-plugin-l4d2-standalone.md)。
9. **部署策略（v3.5.0）**：只改插件代码用 `bash scripts/deploy-plugin.sh` 热部署（构建插件 → PF4J API 卸载释放 Windows jar 文件锁 → 覆盖 `plugins/` 下的 jar → `POST /api/pf4j/plugins/load?jarName=...` 加载启动，**后端不重启**，卸载传 `purgeTasks=false` 保留任务中心历史）；改主应用代码（core/api/plugin 模块）才用 `start-all.sh` 重启。宿主 `loadPlugin` 为"先 start 再发现扩展点"（PF4J per-plugin 扩展查找仅对 STARTED 状态生效，v3.5.0 修复的隐藏 bug）。部署**独立仓库**构建的 jar：`PLUGIN_ID={id} JAR_NAME={jar} bash scripts/deploy-plugin.sh --skip-build --jar /path/to.jar`（见 `references/getting_started.md` §6.1）。
10. **插件前端 Night Operations token 隔离（ADR-0007，v3.5.0）**：Wujie shadow DOM 不继承宿主 CSS 变量，插件前端须**复制**主应用 `frontend/src/styles/variables.scss` 的 `--platform-*` token 副本自管（暗色单主题，无明暗切换）；小工具类（如 `terminalTheme.js`）同样复制。注意两个 sass/Wujie 陷阱见 `references/frontend.md` §8-9。详见 [ADR-0007](../../../docs/design/adr/0007-plugin-frontend-nightops-token-isolation.md)。
11. **平台能力三项扩展（ADR-0009，v3.7.0）**：① `SshTunnelService` SPI——插件经宿主 SSH 开本地端口转发隧道（平台凭据 `openByHost` / 插件自带凭据 `openWithCredentials`），本地端口仅绑 `127.0.0.1`，去重键含 ownerPluginId（跨插件不共享），引用计数 + 插件卸载/删主机三层兜底关闭；② `configInfo.database` 组装——带 DB 的 compose 游戏在 yml `dockerCompose.database` 声明（变量名引用式 portVar/passwordVar），部署与更新同路径组装进 `configInfo.database`；③ `onInstanceUpdate` 钩子——实例配置更新后收到完整新 configInfo（每次更新都触发，diff 由插件自理）。注意 `onInstanceCreate` 在部署前触发、其 configInfo 尚无 database 节（懒建模型）。详见 [ADR-0009](../../../docs/design/adr/0009-platform-capability-requirements.md)。

## 可用类速查（接入规范）

### 扩展点（backend/plugin/.../extension/）
| 类/接口 | 用途 | 详见 |
|---|---|---|
| `GameEnhancementExtension` | 游戏增强扩展点，插件后端入口；含 `getMenus()`、`getManifest()`、`getConfigFields()`、生命周期钩子 | `references/extension_and_menus.md` |
| `PluginMenuDeclaration` | 菜单声明强类型（v3.1.0 ADR-0001）；`@Builder` + `requireInstance` 默认 true | `references/extension_and_menus.md` §6 |
| `TaskHandlerExtension` | 任务处理器注册扩展点 | `references/task_handler.md` |
| `TaskHandler` | 任务处理器接口（getType/execute/isRetryable/getMaxRetryCount/onSubmit） | `references/task_handler.md` |
| `PluginConfigField` | 插件配置字段定义（前端自动渲染表单） | `references/extension_and_menus.md` §3 |
| `AbstractExtension<T>` | 扩展资源基类（持 id/name/spec/version/status/metadata） | `references/extension_client.md` |
| `@ExtensionModel` | 资源模型注解（声明存储策略 SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED） | `references/extension_client.md` §1 |

### 持久化与服务面
| 类/接口 | 用途 | 详见 |
|---|---|---|
| `ExtensionClient` | 插件唯一持久化入口（CRUD + status + count + getManagedTables） | `references/extension_client.md` |
| `HostQueryService` | 主机查询（CPU/内存/磁盘监控 + 主机详情） | `references/host_services.md` §1 |
| `InstanceQueryService` | 实例查询与控制（启停重启 + 日志 + 控制台命令） | `references/host_services.md` §2 |
| `InstanceFileService` | 实例感知文件 SPI（自动路由 SFTP/docker exec，禁止 `..`） | `references/host_services.md` §3 |
| `FileAccessService` | 主机级 SFTP + `executeCommand` | `references/host_services.md` §4 |
| `SshTunnelService` | SSH 本地端口转发隧道（v3.7.0 ADR-0009；openByHost/openWithCredentials/close，引用计数幂等） | `references/host_services.md` §5 |
| `TaskService` | 任务提交/查询/取消（注入子容器） | `references/task_handler.md` §4 |
| `PluginContext` | 插件元数据上下文（v3.0+ 仅元数据，不持数据访问） | `references/extension_and_menus.md` §4 |

### 契约与常量
| 类/接口 | 用途 | 详见 |
|---|---|---|
| `PluginManifestVO` | 插件清单契约（含 `frontend.menus` / `MenuConfig.requireInstance`） | `references/extension_and_menus.md` §7 |
| `PluginConstants` | 路径常量（`/api/plugin/{gameCode}`、`/api/pf4j/plugin/{gameCode}/ui/**`） | `references/checklist.md` §1 |
| `ListOptions` | 条件查询选项（status/label/specFilter/limit/offset/orderBy） | `references/extension_client.md` §3 |

### 异常体系
| 异常类 | 触发场景 | 详见 |
|---|---|---|
| `PluginException`（基类） | 持 pluginId | `references/exceptions.md` |
| `PluginLoadException` | 加载失败（DDL/依赖） | `references/exceptions.md` |
| `PluginConfigException` | 配置缺失 | `references/exceptions.md` |
| `PluginPathConflictException` | 控制器路径冲突 | `references/exceptions.md` |
| `ExtensionStoreException`（基类） | 扩展资源存储 | `references/exceptions.md` |
| `DuplicateExtensionException` | create 时 name 冲突 | `references/exceptions.md` |
| `OptimisticLockException` | update 时 version 不匹配 | `references/exceptions.md` |
| `ExtensionNotFoundException` | get/update/delete 目标缺失 | `references/exceptions.md` |

> 完整签名见 `references/sdk_reference.md`。**注意**：`PluginDataAccessException` 不存在，数据访问异常用 `ExtensionStoreException` 体系。

## 开发流程（分层）

### 第 1 层：最小后端插件（→ `references/getting_started.md`）

1. 建 Maven 模块 `plugin-{gameCode}`，依赖 `game-platform-plugin`（`provided` scope）。
2. 写 `plugin.properties`（必填 `plugin.id`/`plugin.class`/`plugin.version`，可选 `plugin.gameCode`/`plugin.basePackage`）。
3. 写 `{GameCode}Plugin extends Plugin`（PF4J 入口，仅生命周期日志）。
4. 写 `@Extension {GameCode}Extension implements GameEnhancementExtension`，实现 `getGameCode`/`getGameName`/`getVersion`/`getDescription`/`getBasePackage`。
5. 双端插件需实现 `getMenus()` 返回 `List<PluginMenuDeclaration>`（见第 5 层）。
6. 打包单 JAR 放入主应用 `plugins/`，启动后 `GET /api/pf4j/plugin/{gameCode}/manifest` 验证（应返回 `frontend.menus`）。

### 第 2 层：持久化与控制器（→ `references/extension_client.md`）

1. 定义业务 `Spec`（POJO）+ `{Resource} extends AbstractExtension<Spec>`，标 `@ExtensionModel(strategy=...)`。
2. 控制器路径必须以 `/api/plugin/{gameCode}/` 开头；注入 `ExtensionClient` 做 CRUD（方法全集见 `references/sdk_reference.md`）。
3. 需读主机/实例/文件时注入宿主服务面（§第 3 层）。

### 第 3 层：宿主服务面（→ `references/host_services.md`）

- `HostQueryService`：主机详情 + 资源监控。
- `InstanceQueryService`：实例查询 + 启停重启 + 日志 + 控制台命令。
- `InstanceFileService`：实例感知文件 SPI，自动路由 SFTP/docker exec；`relativePath` 相对游戏数据根目录，禁止 `..`。
- `FileAccessService`：主机级 SFTP + `executeCommand`。

签名见 `references/sdk_reference.md` §宿主服务面。

### 第 4 层：异步任务（→ `references/task_handler.md`）

1. 写 `TaskHandler implements`，实现 `getType`/`execute`/`isRetryable`/`getMaxRetryCount`/`getDefaultTimeoutMs`/`onSubmit`。
2. 写 `@Component TaskHandlerExtension`，构造时缓存 `Map<taskType, TaskHandler>`。
3. 注入 `TaskService` 提交任务（`source` 自动填 gameCode 大写）。
4. `execute` 循环必须检查 `isCancelled()`/`isTimeout()`；进度用 `reportProgress`（已节流）。

### 第 5 层：菜单声明与前端子应用（→ `references/extension_and_menus.md` + `references/frontend.md`）

1. 在 `{GameCode}Extension` 实现 `getMenus()`，返回 `List<PluginMenuDeclaration>`（含全部菜单，宿主不预置默认菜单）。
   - 同插件内 `path` 必须唯一（重复抛 `IllegalStateException`）。
   - 纯资源浏览页（如地图中心）显式 `.requireInstance(Boolean.FALSE)`。
2. 建 `plugin-{gameCode}/frontend`（Vue 3 + Vite），`utils/runtime.ts` 实现 `detectMode()`。
3. `router/index.ts` 路由 path 必须与 `getMenus()` 声明的 path **严格对齐**（否则点击菜单白屏）。
4. Wujie 模式用 `createWebHashHistory()`；通过 `window.$wujie.props` 读初始数据，`window.$wujie.bus` 收发事件。
5. standalone 模式已废弃（ADR-0003），新增插件不应实现。

## 排查速查

| 症状 | 先查 |
|---|---|
| 插件加载失败 | `PluginLoadException`；依赖 gameCode 是否已加载；`plugin.properties` 必填项 → `references/exceptions.md` |
| 菜单不显示 | `getMenus()` 是否实现并返回非空列表；`GET /api/pf4j/plugin/{gameCode}/manifest` 的 `frontend.menus` 字段 → `references/extension_and_menus.md` §8 |
| 菜单点击白屏 | 子应用路由 path 与 `getMenus()` 声明的 path 是否对齐 → `references/gotchas.md` §2 |
| `IllegalStateException`: 菜单 path 重复/为空 | `getMenus()` 返回值校验：path 非空、同插件内唯一 → `references/extension_and_menus.md` §6.3 |
| `features` 字段无效 | ADR-0001 已废弃 features，迁移到 `getMenus()` → `references/gotchas.md` §1 |
| 持久化越权报错 | 是否用了未声明的 `@ExtensionModel` 类；`ExtensionClient` 绑定 pluginId → `references/extension_client.md` §4 |
| update 报版本冲突 | `OptimisticLockException`；重新 `get` 拿 version 再更新 → `references/exceptions.md` |
| 任务卡死 | `execute` 是否漏检 `isCancelled`/`isTimeout` → `references/task_handler.md` §6 |
| 文件操作 IllegalArgumentException | `relativePath` 含 `..` 越界 → `references/host_services.md` §3 |
| 连不上实例数据库 | `configInfo.database` 是否存在（老实例裸变量回退）；隧道是否开启（连 `127.0.0.1:handle.localPort`）→ `references/host_services.md` §5-6 |
| 连接池用了旧密码/端口 | 是否消费 `onInstanceUpdate`（改配置后失效重建）；该钩子每次更新都触发 → `references/host_services.md` §6.3 |

## 参考实现

### 在线 demo（examples/plugin-mygame，独立可参考）

`examples/plugin-mygame/` 是一个**最小可运行双端插件示例**，自包含、不依赖 plugin-l4d2 源码即可理解，适合外部项目参考。覆盖核心能力：

| 文件 | 演示能力 |
|---|---|
| `examples/plugin-mygame/pom.xml` | Maven 依赖宿主 `game-platform-plugin` / `game-platform-api`（provided scope），PF4J Manifest 写入 |
| `examples/plugin-mygame/src/main/resources/plugin.properties` | PF4J 必填项（plugin.id/class/version）+ gameCode/basePackage |
| `examples/plugin-mygame/src/main/java/.../MyGamePlugin.java` | PF4J 入口（生命周期日志） |
| `examples/plugin-mygame/src/main/java/.../MyGameExtension.java` | `GameEnhancementExtension` 实现 + `getMenus()` 菜单声明（ADR-0001） + `getManifest()` 自描述 |
| `examples/plugin-mygame/src/main/java/.../extension/NoteResource.java` | `@ExtensionModel(strategy=MODEL_ISOLATED)` 扩展资源 |
| `examples/plugin-mygame/src/main/java/.../extension/NoteSpec.java` | 业务 Spec POJO |
| `examples/plugin-mygame/src/main/java/.../controller/NoteController.java` | REST 控制器（路径 `/api/plugin/mygame/notes`）+ `ExtensionClient` CRUD（create/get/list/update/delete + 乐观锁） |
| `examples/plugin-mygame/frontend/package.json` | 前端依赖（Vue 3 + Element Plus + Pinia + Wujie 兼容） |
| `examples/plugin-mygame/frontend/vite.config.ts` | `base: './'`、`outDir` 输出到后端 JAR `ui/`、dev proxy 转发 `/api` |
| `examples/plugin-mygame/frontend/src/main.ts` | Wujie 生命周期（bootstrap/mount/unmount）+ 三模式渲染 |
| `examples/plugin-mygame/frontend/src/utils/runtime.ts` | `detectMode()` 三模式检测 |
| `examples/plugin-mygame/frontend/src/router/index.ts` | 路由 path 与 `getMenus()` 严格对齐 + 三 history 模式 |
| `examples/plugin-mygame/frontend/src/api/request.ts` | API 封装（token 双源：Wujie props + localStorage） |
| `examples/plugin-mygame/frontend/src/stores/plugin.ts` | Pinia store（从 Wujie props 同步 instance/auth） |
| `examples/plugin-mygame/frontend/src/App.vue` | 根组件（简化版，不嵌套 MainLayout） |
| `examples/plugin-mygame/frontend/src/pages/Dashboard.vue` | 仪表盘页面（演示实例信息展示 + 笔记 CRUD） |

**使用方式**：
1. 复制 `examples/plugin-mygame/` 到外部项目
2. 后端：`mvn clean package`，将 JAR 放入主应用 `plugins/` 目录
3. 前端：`cd frontend && npm install && npm run build`（产物自动输出到后端 `ui/`）
4. 开发模式：`npm run dev`（端口 3100，proxy 转发 `/api` 到主应用 8080）

### 完整参考实现（backend/plugin-l4d2/）

`backend/plugin-l4d2/` 是完整双端参考实现（core / frontend 两件套），含 17 项菜单、爬虫、RCON、地图、SourceMod 插件管理等完整能力。开发新插件时对照其 `L4D2Extension`、`L4D2Plugin`、`plugin.properties`、`extension/` 资源类、`frontend/src/router/index.ts`。完整剖析见 `references/walkthrough_l4d2.md`。

> 外部项目无法读取 `backend/plugin-l4d2/` 源码时，以 `examples/plugin-mygame/` 为起点，按需参考 `references/` 文档扩展能力。

## 文档维护约定

- 本 SKILL 目录为插件开发文档的**唯一权威源**（v3.1.0 起，原 `docs/PLUGIN_DEV_GUIDE.md` 已删除并迁移到此）。
- 接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记到对应 `references/` 文件。
- 主版本变更（破坏性 API 改动）→ 在 `references/changelog.md` 升版本号并记录。
- ADR 关联：`docs/design/adr/0001-plugin-menu-ownership.md`（菜单归属权）、`docs/design/adr/0009-platform-capability-requirements.md`（SSH 隧道 / configInfo.database / onInstanceUpdate，v3.7.0）。
