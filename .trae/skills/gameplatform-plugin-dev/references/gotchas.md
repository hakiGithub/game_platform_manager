# 插件开发陷阱与要点

> 对齐版本：v3.1.0（ADR-0001 菜单归属权迁移）

## 1. 菜单机制（ADR-0001，v3.1.0 起变更）

**菜单由插件声明，宿主仅做校验与序列化。**

- 插件通过 `GameEnhancementExtension.getMenus()` 返回 `List<PluginMenuDeclaration>` 声明菜单清单。
- 宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 校验 path 唯一性、补全 `requireInstance` 默认值（null→true），序列化为 `PluginManifestVO.MenuConfig`。
- 宿主**不预置任何默认菜单**（不再有仪表盘/系统监控/服务器信息等固定菜单），插件需显式声明完整菜单列表（参考 plugin-l4d2 的 17 项菜单）。

**ADR-0001 废弃项（v3.1.0）**：
- `getManifest()` 的 `features` 字段（`rcon`/`mapManagement`/`playerManagement`）已废弃，宿主不再读取。
- `getManifest()` 中的 `frontend.menus` 字段（如存在）会被忽略。
- 宿主 `buildDefaultMenus()` 方法已删除。
- 主应用从 `manifest.frontend.menus` 的 path 集合推导 capabilities（不再依赖 features）。
- 旧插件需将 `features` 迁移到 `getMenus()` 显式声明。

**PluginMenuDeclaration 字段约束**：
- `path` 必填，同插件内必须唯一，重复或为空抛 `IllegalStateException`。
- `requireInstance` 默认 `true`；纯资源页（如地图中心）显式设 `Boolean.FALSE`。
- 推荐显式设置 `requireInstance`，避免依赖默认值导致误判。

## 2. 前端路由 path 必须与 getMenus() 声明的 path 对齐

子应用 `frontend/src/router/index.ts` 的路由 path 必须与 `getMenus()` 声明的菜单 path **完全一致**，否则点击菜单白屏。例如 `getMenus()` 声明 `/map-center`，路由表必须有对应 `/map-center`。

## 3. 前端三运行模式

`detectMode()`（`frontend/src/utils/runtime.ts`）：
- `wujie`：`window.__POWERED_BY_WUJIE__` 或 `props.mode==='wujie'` → `createWebHashHistory()`
- `dev`：`import.meta.env.DEV` → `createWebHistory('/')`
- `standalone`：其余 → `createWebHistory('/ui/')`

主应用可通过 Wujie `props.route` 指定子应用初始路由。

## 4. 前后端通信（Wujie）

- 初始数据：主应用经 Wujie `props` 下发（instanceId、token 等）。
- 运行时事件：`window.$wujie.bus.$on/$emit`。
- 旧 postMessage 通信仅兼容，新代码用 Wujie bus。

## 5. PluginContext 不再持数据访问

v3.0+ 起 `PluginContext` 仅持元数据（`getPluginId`/`getGameCode`/`getGameName`/`getVersion`/`getCustomProperties`）。持久化一律走子容器注入的 `ExtensionClient`。

## 6. ExtensionClient 身份隔离

- 绑定 pluginId，所有方法自动注入 `group_name = pluginId` 过滤。
- 只能访问本插件已声明的 `@ExtensionModel` 资源类。
- 插件 A 无法访问插件 B 数据，无例外。
- `update` 需带读到的 `version`（乐观锁）；并发改写抛 `OptimisticLockException`，需重新 `get`。

## 7. 文件路径安全（InstanceFileService）

- `relativePath` 相对实例"游戏数据根目录"，用正斜杠。
- Native/LinuxGSM：根目录 = `instance.installPath`
- Docker 类：根目录 = 容器内工作目录（`runtimeMetadata.containerWorkDir`）
- **禁止 `..`**，越界抛 `IllegalArgumentException`。

## 8. 异常层级

```
PluginException (基类, 持 pluginId)
├── PluginLoadException          加载失败（DDL/依赖）
├── PluginConfigException        配置缺失
└── PluginPathConflictException  控制器路径冲突（含 conflictPath + existingPluginId）

ExtensionStoreException (扩展资源存储基类)
├── DuplicateExtensionException  create 时 name 冲突
├── OptimisticLockException      update 时 version 不匹配
└── ExtensionNotFoundException   get/update/delete 目标缺失
```

> ⚠️ 文档历史曾误用 `PluginDataAccessException`，该类**不存在**于 backend。数据访问异常用 `ExtensionStoreException` 体系。

## 9. 任务 Handler 约束

- Handler 必须**无状态**，状态通过 `TaskContext` 传递。
- `TaskHandlerExtension` 标 `@Component`，`getTaskHandlers()` 返回的 Map 在构造时一次性创建缓存。
- `source` 由框架自动填 gameCode 大写，插件不要手动设。
- `execute` 循环必须定期检查 `isCancelled()`/`isTimeout()`，命中即 return `TaskResult.failure`。
- 不要在 `execute` 吞 `InterruptedException`（重设中断标志退出）。
- 不要在 `finally` 调 `reportProgress`（终态已强制刷盘）。
- payload 序列化上限 64KB；TaskResult 数据上限 256KB；日志每任务最多 500 条。
- `maxRetryCount`：幂等任务=3，有副作用任务=1。

## 10. 控制器路径冲突

两个插件注册相同 URL → `PluginPathConflictException` 阻止加载。规避：路径严格按 `/api/plugin/{gameCode}/` 前缀，`gameCode` 全局唯一。

## 11. standalone 模式

`plugin-{gameCode}-standalone` 提供独立 Spring Boot 应用 + 宿主服务独立实现（`StandaloneHostQueryService`/`StandaloneInstanceQueryService`/`StandaloneFileAccessService`/`StandaloneExtensionClient`），使插件脱离主应用运行。前端经 `/api/standalone/*` 取实例，实例信息存 localStorage。参考 `plugin-l4d2-standalone`。

## 12. 范围隔离（ADR-0002，v3.2.0 起变更）

**主应用 `core/` 与插件严格隔离，插件配置和表自管。**

- **配置**：插件 `@ConfigurationProperties` 类的字段 Java 默认值即配置来源；**禁止**在主应用 `application.yml` 写 `plugin.{gameCode}` 块。需要覆盖默认值时由 standalone yml 或环境变量处理。
- **表**：插件表由 ExtensionClient 的 `ext_plugin_{pluginId}_{resource}` 模式通过 `DdlTemplate` 动态建表；**禁止**在主应用 `db/migration/` 写 `{gameCode}_*` 前缀的插件专属表。
- **代码**：主应用 `core/` 不得 `import com.gameplatform.plugin.{gameCode}.*`。
- **例外**：游戏元数据 `core/resources/games/{gameCode}.yml` 由主应用维护（部署向导输入），不属于插件业务。
- **standalone 模式自治**：`plugin-{gameCode}-standalone/application.yml` 不受本规约约束。

详见 [ADR-0002](../../../../docs/design/adr/0002-main-app-plugin-scope-isolation.md)。

## 13. 版本与维护

- 本 SKILL 目录（`references/`）为插件开发文档唯一权威源（v3.1.0 起）。
- 主版本变更（破坏性 API 改动）→ 在 `references/changelog.md` 升版本号并记录。
- minor 变更只更 changelog。
- 接口签名以 `backend/plugin/` 源码为权威，新增即补登记到对应 `references/` 文件。
