---
name: gameplatform-plugin-dev
description: >
  GamePlatform 游戏服务器管理平台的插件开发与排查技能（用户级副本，跨项目可用）。在为 GamePlatform
  平台（PF4J + Spring 子容器 + Wujie 微前端）开发或修改游戏插件（plugin-{gameCode}）、声明菜单与扩展点
  （getMenus / TaskHandler / ScheduledTask）、使用 ExtensionClient 持久化、调用宿主服务
  （主机/实例/文件/SSH 隧道/RCON/实例动态信息）、开发 Wujie 插件前端，或排查插件加载失败、菜单不显示/白屏、持久化越权、
  任务卡死、更新插件开发文档时使用。Use when developing, debugging, or migrating GamePlatform
  platform plugins. 主应用 core/api/plugin 模块自身功能开发、非 GamePlatform 平台的插件开发、
  纯前端样式微调不适用本 skill。
agent_created: true
---

# GamePlatform 插件开发

GamePlatform 是面向游戏服务器运维的轻量管理平台（PF4J + Spring 子容器 + Wujie 微前端）。平台只提供插件框架与宿主能力面，具体游戏功能由插件注入。一个插件由三部分组成：

- **后端（Java）**：跑在平台为插件创建的 Spring 子容器里，实现 `GameEnhancementExtension` 扩展点 + REST 控制器
- **前端（Vue 3 + Vite）**：Wujie 子应用；菜单由后端 `getMenus()` 声明，路由 path 严格对齐
- **清单（plugin.properties）**：PF4J 必填项（`plugin.id` / `plugin.class` / `plugin.version`）+ `plugin.gameCode` / `plugin.basePackage`

> **版本优先**：写代码前先查 `references/changelog.md` 的破坏性变更速查表，确认目标平台的插件 API 面。在平台仓库内编码时，接口签名一律以 `backend/plugin/` 源码为准；跨项目时用 `references/sdk-reference.md` 离线快照，不要凭记忆猜签名。

## Quick Start

最小可运行插件只需三件物：`plugin.properties`、`{GameCode}Plugin`（PF4J 入口）、`{GameCode}Extension`（扩展点实现），完整骨架代码见 `references/getting-started.md` §2-6：

```bash
# 1. 新建 Maven 模块 plugin-mygame，写骨架三件物（getting-started.md §2-5，pom 规范 §6）
# 2. 打包为单 JAR 放入平台 plugins/ 目录，启动主应用即被加载
mvn clean package

# 3. 验证清单（应返回含 frontend.menus 的 JSON）
curl http://localhost:8080/api/pf4j/plugin/mygame/manifest

# 4. 双端插件：frontend/ 下 npm install && npm run build（产物输出到后端 src/main/resources/ui/）
#    开发模式 npm run dev（proxy 转发 /api 到后端 8080），详见 references/frontend.md
```

**改动后如何生效**：只改插件代码 → `bash scripts/deploy-plugin.sh` 热部署（构建 → 卸载释放 Windows jar 文件锁 → 覆盖 → 加载，**后端免重启**）；改了主应用（core/api/plugin 模块）→ `bash scripts/start-all.sh` 重启。

## 开发工作流

1. **骨架**：Maven 模块 `plugin-{gameCode}` + `plugin.properties` + `{GameCode}Plugin`（PF4J 入口，仅生命周期日志）+ `{GameCode}Extension`（扩展点实现）→ `references/getting-started.md`
2. **菜单**：双端插件实现 `getMenus()` 返回 `List<PluginMenuDeclaration>`（宿主不预置任何默认菜单）→ `references/extension-and-menus.md` §6
3. **持久化**：定义 `{Resource} extends AbstractExtension<Spec>` 标 `@ExtensionModel`，注入 `ExtensionClient` 做 CRUD → `references/persistence.md`
4. **控制器与宿主服务**：路径必须以 `/api/plugin/{gameCode}/` 开头；需要主机/实例/文件/SSH 隧道/RCON 能力时注入宿主服务面 → `references/host-services.md`
5. **异步任务**：实现 `TaskHandler` + `TaskHandlerExtension` 注册，注入 `TaskService` 提交 → `references/async-tasks.md`
6. **定时任务**：实现 `ScheduledTaskHandler`（独立于任务中心），可选声明式默认计划或注入 `ScheduleService` → `references/scheduled-tasks.md`
7. **前端**：`frontend/` Vue 3 + Vite 子应用，`utils/runtime.ts` 实现 `detectMode()`，路由 path 与 `getMenus()` 声明严格对齐 → `references/frontend.md`
8. **验证**：manifest 返回正确清单、菜单可点、插件 API 可调；发布前过一遍 → `references/checklist.md`

## 关键不变量（违反即踩坑）

1. **菜单归属插件**（ADR-0001）：`getMenus()` 是唯一菜单来源；`getManifest()` 的 `features` 字段已废弃，宿主不再读取。
2. **路由对齐**：子应用路由 path 必须与 `getMenus()` 声明的 path 完全一致——不一致 = 菜单点击白屏。
3. **持久化唯一入口** `ExtensionClient`（绑定 pluginId，自动 group_name+kind 隔离）；`PluginContext` 仅持元数据。
4. **路径前缀**：控制器必须以 `/api/plugin/{gameCode}/` 开头，跨插件重复注册抛 `PluginPathConflictException`。
5. **范围隔离**（ADR-0002）：主应用不写插件配置、不建插件表、不 import 插件包；插件配置与表自管。
6. **两运行模式**（ADR-0003）：只支持 wujie（hash 路由）+ dev；standalone 已废弃，新插件不要实现。
7. **token 复制不共享**（ADR-0007）：Wujie shadow DOM 不继承宿主 CSS 变量，Night Ops token 必须复制进插件自管，不要跨目录 import。
8. **两套任务体系**（ADR-0011）：任务中心 `TaskHandler`（重试/互斥/生命周期钩子）与定时任务 `ScheduledTaskHandler`（无重试无互斥）接口与语义不通用。
9. **热部署保留历史**：卸载必须传 `purgeTasks=false`（deploy-plugin.sh 默认如此），`true` 仅用于彻底移除插件。

## References 索引

| 文件 | 内容 | 何时读 |
|---|---|---|
| `references/getting-started.md` | 版本约定、快速开始、项目结构、plugin.properties、Plugin 入口、pom.xml、独立仓库构建（§6.1） | 新建插件模块、配置骨架；在平台仓库外打包插件时 |
| `references/extension-and-menus.md` | GameEnhancementExtension 全实现：元数据 / getManifest（features 已废弃）/ getConfigFields / 生命周期钩子 / **getMenus + PluginMenuDeclaration（ADR-0001）** / PluginManifestVO 契约 / 菜单加载链路 / getDeployConfigs（ADR-0008）/ **InstanceInfoProvider 实例动态信息（ADR-0017）** | 实现扩展点、声明菜单、自描述清单、声明部署配置模板、提供玩家数等实时信息 |
| `references/persistence.md` | `@ExtensionModel` 三种存储策略、ExtensionClient 全方法、ListOptions、安全约束 | 声明扩展资源、CRUD、条件查询；**禁止拼 SQL**，specFilter 走参数化 |
| `references/host-services.md` | HostQueryService / InstanceQueryService / InstanceFileService（SFTP 与 docker exec 自动路由）/ FileAccessService / SshTunnelService（ADR-0009）/ **RconService（ADR-0016）** / **文件传输进度回调** / configInfo.database 组装 / 控制器规范 | 读写实例文件、执行远程命令、SSH 隧道连实例数据库、执行 RCON 命令（勿自建 RCON 连接）；**不要自写 docker cp 到本地路径** |
| `references/async-tasks.md` | TaskHandler / TaskHandlerExtension / TaskService、注册/实现/提交、进度节流、取消超时、互斥键 | 开发异步任务；**Handler 必须无状态**，循环必须检查 `isCancelled`/`isTimeout` |
| `references/scheduled-tasks.md` | ScheduledTaskHandler / ScheduledTaskDeclarationExtension / ScheduleService、声明式 upsert 语义、来源隔离、重叠 SKIPPED、插件生命周期联动（ADR-0011） | 开发定时任务；与任务中心完全分离，不要混用两套 Handler |
| `references/frontend.md` | 两运行模式 detectMode、Wujie 通信、目录结构、Night Ops token 隔离（ADR-0007）、popper 定位修正、前端易缺失文件清单 | 开发 Wujie 子应用；弹层漂移**不要用** popper-options / teleported 方案，用 wujiePopperFix |
| `references/exceptions.md` | 异常类层级、框架异常使用、基于真实异常的 FAQ | 抛错/接错；按异常类名排查问题 |
| `references/gotchas.md` | 19 节非显而易见的陷阱：菜单机制、路由对齐、隔离规约、路径安全、热部署、独立构建四坑、子容器静默失败、无 RCON 控制台通道等 | 行为与预期不符时先查这里 |
| `references/checklist.md` | 路径常量速查、安全配置约定、发布检查清单、验收标准 | 发布前自检、查路径常量与缓存策略 |
| `references/sdk-reference.md` | 全部扩展点 / 服务接口签名速查（跨项目离线快照） | 编码时查方法签名；语义疑义回到对应主题文件与源码 |
| `references/changelog.md` | 版本演进、**破坏性变更速查表**、维护约定 | 动手前确认 API 兼容性；平台升级后对照 |
| `references/walkthrough-l4d2.md` | plugin-l4d2 完整双端参考实现剖析 | 对照完整实现开发新插件（平台仓库内） |

## 排查速查

| 症状 | 先查 |
|---|---|
| 插件加载失败 | `PluginLoadException`；依赖 gameCode 是否已加载；plugin.properties 必填项 → `references/exceptions.md` |
| 菜单不显示 | `getMenus()` 是否返回非空列表；manifest 的 `frontend.menus` 字段 → `references/extension-and-menus.md` §8 |
| 菜单点击白屏 | 子应用路由 path 与 `getMenus()` 声明是否对齐 → `references/gotchas.md` §2 |
| 插件 API 全 500 但状态 STARTED | 子容器 bean 创建失败静默继续，grep 日志"Spring 上下文创建失败" → `references/gotchas.md` §16 |
| 热部署后菜单消失/任务历史丢失 | 是否用了 `purgeTasks=true`；宿主 loadPlugin 版本 → `references/gotchas.md` §13 |
| `IllegalStateException`: 菜单 path 重复/为空 | `getMenus()` 校验：path 非空、同插件内唯一 → `references/extension-and-menus.md` §6.3 |
| `features` 字段无效 | ADR-0001 已废弃 features，迁移到 `getMenus()` → `references/gotchas.md` §1 |
| 持久化越权报错 | 是否用了未声明的 `@ExtensionModel` 类；ExtensionClient 绑定 pluginId → `references/persistence.md` §4 |
| update 报版本冲突 | `OptimisticLockException`；重新 get 拿 version 再更新 → `references/exceptions.md` |
| 任务卡死 | `execute` 是否漏检 `isCancelled`/`isTimeout` → `references/async-tasks.md` §6 |
| 定时计划不触发 | cron 合法性 / enabled·paused 状态 / handlerKey 与 `getKey()` 一致 → `references/scheduled-tasks.md` §8 |
| 定时触发即 FAILED | run 的 errorMessage；handlerKey 未注册 / Handler 异常 → `references/scheduled-tasks.md` §8 |
| 文件操作 IllegalArgumentException | `relativePath` 含 `..` 越界 → `references/host-services.md` §3 |
| 连不上实例数据库 | `configInfo.database` 是否存在（老实例裸变量回退）；隧道连 `127.0.0.1:handle.localPort()` → `references/host-services.md` §5-6 |
| 连接池用了旧密码/端口 | 是否消费 `onInstanceUpdate`（每次更新都触发，失效重建自理）→ `references/host-services.md` §6.3 |

## 参考实现

- **平台仓库内的 `plugin-l4d2` 插件**：完整双端参考实现（20 项菜单、爬虫、RCON、地图、SourceMod 插件管理），剖析见 `references/walkthrough-l4d2.md`；平台仓库内可直接对照源码。
- 平台仓库外：以 `references/` 分主题文档 + `references/sdk-reference.md` 离线签名快照为参照，从 `references/getting-started.md` §2 骨架起步。

## 文档定位与维护（ADR-0014）

- **API 权威**永远是 `backend/plugin/` 源码；本 skill 是面向 AI 的**自包含副本**（用户级副本在其他项目读不到平台仓库，故全文自带）。
- 与面向人的权威文档 `docs/plugin-development/`（平台仓库内）**概念对齐、不逐字一致**；文件名已与其对齐（连字符、`persistence` / `async-tasks` 等语义命名）。
- skill 存在两处**逐字同步**的副本：平台仓库 `.trae/skills/gameplatform-plugin-dev/`（编辑源）与用户级 `~/.agents/skills/gameplatform-plugin-dev/`；修改先落工作区，再整目录复制同步。
- 接口签名、路径常量、异常类新增/变更 → 先改代码，再登记到对应 `references/` 文件，并在 `references/changelog.md` 记条目；破坏性变更升版本号。
- 决策背景：ADR-0001（菜单归属）、ADR-0002（范围隔离）、ADR-0003（废弃 standalone）、ADR-0007（token 隔离）、ADR-0009（平台能力扩展）、ADR-0011（定时任务）、ADR-0014（文档三副本分工）。
