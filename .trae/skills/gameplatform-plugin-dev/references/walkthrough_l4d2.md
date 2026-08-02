# 参考实现剖析（plugin-l4d2）

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin-l4d2/` 源码

`plugin-l4d2` 是完整的双端插件参考实现，含三个子模块：

```
plugin-l4d2/
├── plugin-l4d2-core/         # 核心 JAR（嵌入式）
├── plugin-l4d2-standalone/   # standalone 独立运行模式
└── frontend/                 # Vue 3 + Vite 子应用
```

## 1. 后端入口

**L4D2Plugin**（`plugin-l4d2-core`）：标准 PF4J 入口，仅日志。

**L4D2Extension**（`GameEnhancementExtension` 实现）：
- `getGameCode()` = `"l4d2"`，`getGameName()` = `"求生之路2"`
- `getManifest()` 返回 `apiEndpoints` map + 元数据（**ADR-0001 已删除 `features` 与 `frontend.menus` 字段**）
- `getMenus()` 返回 17 项 `PluginMenuDeclaration`（仪表盘、地图管理、地图中心[requireInstance=false]、控制台、系统监控、玩家统计、游玩时长、管理员、服务器信息、服务器配置、重启管理、版本信息、日志、备份还原、插件管理、预设场景、下载管理）
- 实现全部生命周期钩子：`onInstanceCreate`（懒初始化插件库）、`onInstanceStart/Stop/Delete`
- `getIcon()` = `"assets/l4d2-icon.png"`，`getBasePackage()` = `"com.gameplatform.plugin.l4d2"`

**plugin.properties**：`plugin.id=plugin-l4d2`、`plugin.class=...L4D2Plugin`、`plugin.gameCode=l4d2`、`plugin.basePackage=...`。

## 2. 扩展资源（@ExtensionModel）

L4D2 声明了多个资源模型：`AdminResource`/`AdminSpec`、`PluginConfigResource`/`PluginConfigSpec`、`PluginBackupResource`/`PluginBackupSpec`、`DownloadTaskResource`、`SystemMetricResource`、`ChunkUploadResource`、`PlayerStatSnapshotResource` 等，均通过 `ExtensionClient` 持久化。

## 3. 控制器与宿主服务

控制器按 `/api/plugin/l4d2/*` 前缀：`MapController`、`BackupController`、`DownloadController`、`PresetController`、`LogsController`、`ChunkUploadController`、`PlaytimeController`、`PluginStoreController`。通过注入 `InstanceQueryService`、`InstanceFileService`、`FileAccessService`、`ExtensionClient`、`TaskService` 实现业务。

## 4. 任务处理器

`TaskHandlerExtension` 实现（`@Component`）注册 `crawl`、`backup` 等 Handler，`source` 自动填充为 `L4D2`。

## 5. 前端子应用

- `utils/runtime.ts` 的 `detectMode()` 区分三模式
- `router/index.ts` 路由 path（`/dashboard`、`/maps`、`/map-center`、`/rcon`、`/monitor`、`/player-stats` 等）与 `L4D2Extension.getMenus()` 声明的菜单 path 严格对齐（ADR-0001）
- `stores/plugin.ts` 在 Wujie 模式从 `window.$wujie.props` 读取实例信息，standalone 模式从 localStorage + `/api/standalone/instances` 读取

## 6. Standalone 模式

`plugin-l4d2-standalone` 提供 `L4D2StandaloneApp`（独立 Spring Boot 启动）+ 宿主服务独立实现（`StandaloneHostQueryService` 等）+ `/api/standalone/*` 端点 + `StandaloneSpaController` 服务 `/ui/` 前端，使 L4D2 插件可脱离主应用独立部署运行。
