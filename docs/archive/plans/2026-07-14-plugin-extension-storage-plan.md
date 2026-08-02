# 插件扩展存储（Extension 宽表）实现计划

> 对应设计文档：[2026-07-14-plugin-extension-storage-design.md](../../design/specs/2026-07-14-plugin-extension-storage-design.md)
>
> 本计划按"编译在每个里程碑末尾保持绿色"原则排序。每个阶段结束有验证点。

---

## 里程碑总览

| # | 阶段 | 关键产出 | 验证 |
|---|------|---------|------|
| M1 | api 契约层 | `AbstractExtension<T>` / `ExtensionMetadata` | `mvn -pl api compile` |
| M2 | plugin SDK 层 | `@ExtensionModel` / `Strategy` / `ExtensionClient` 接口 / `ListOptions` / 异常 | `mvn -pl plugin compile` |
| M3 | core 基础设施 | `ExtensionRouter` / `DdlTemplate` / `ResolvedRoute` / `ExtensionScanner` / `ExtensionQueryDialect`+`SqliteQueryDialect` | 单元测试 |
| M4 | `ExtensionClientImpl` | CRUD + 乐观锁 + 身份注入 + SQLite 内存过滤 | 单元测试（含跨插件隔离负向用例） |
| M5 | 生命周期集成 | `ExtensionStoreInitializer` / `PluginSchemaManager` / 改造 `PluginSpringContextFactory` / 修 `instanceId=0L` / 补 `onInstanceCreate/Delete` / purge API / 异常映射 | 启动 + 插件加载集成测试 |
| M6 | 删除旧机制 | 删 `IPluginDataAccess` 系 + 接口方法 + `executePluginDDL` + L4D2 覆写 + DDL 文件 + `plugin.properties` 声明 | 全量编译 + 旧表 DROP |
| M7 | L4D2 迁移 | 4 个 Extension 类 + Spec + 改造 `AdminController`/`MonitorController` | L4D2 控制器集成测试 |
| M8 | 测试收尾 | 回归测试 + 文档更新 | `mvn test` 全绿 |

---

## M1 · api 契约层

**目标**：提供插件编译期可见的纯 POJO 基类，无 Spring 依赖。

**新建文件**（`backend/api/src/main/java/com/gameplatform/api/extension/`）：
- `AbstractExtension<T>` — 況化基类，字段：`name` / `groupName` / `kind` / `version` / `metadata` / `spec` / `status`，标准 getter/setter。
- `ExtensionMetadata` — `labels` / `annotations` / `creationTimestamp` / `updateTimestamp`。

**验证**：`mvn -pl api -am compile`

---

## M2 · plugin SDK 层

**目标**：定义插件开发者面向的注解、接口与异常。

**新建文件**（`backend/plugin/src/main/java/com/gameplatform/plugin/extension/`）：
- `ExtensionModel.java` — `@Target(TYPE) @Retention(RUNTIME)`，属性 `strategy` / `group` / `kind`。
- `Strategy.java` — 枚举 `SHARED` / `PLUGIN_ISOLATED` / `MODEL_ISOLATED`。
- `ExtensionClient.java` — 接口（设计文档 §5.1）。
- `ListOptions.java` + `SpecFilter.java` — 设计文档 §5.2，含 builder。
- `exception/` — `ExtensionStoreException`（基类，runtime）、`DuplicateExtensionException`、`ExtensionNotFoundException`、`OptimisticLockException`。

**依赖**：plugin 模块 pom 已依赖 api，无需改动。

**验证**：`mvn -pl plugin -am compile`

---

## M3 · core 基础设施

**目标**：路由、DDL、扫描、查询方言。

**新建文件**（`backend/core/src/main/java/com/gameplatform/plugin/extension/`）：
- `ResolvedRoute.java` — 值对象（table / group / kind / strategy）。
- `ExtensionRouter.java` — `resolve(Class, pluginId)`，含 `sanitize`（非 `[a-z0-9_]` 替换为 `_` 并小写）。
- `DdlTemplate.java` — `generate(tableName)` 返回 SQLite DDL（设计文档 §4.1）。
- `ExtensionScanner.java` — 用插件 ClassLoader + basePackage 扫描 `@ExtensionModel` 类（复用 Reflections 或简化遍历；项目已有依赖则用，否则手写类路径扫描）。
- `ExtensionQueryDialect.java`（接口）+ `SqliteQueryDialect.java`（实现）— 设计文档 §5.3。

**单元测试**（`backend/core/src/test/java/.../extension/`）：
- `ExtensionRouterTest` — 三策略表名、sanitize 防注入、默认 group/kind 推导、注解覆盖。
- `DdlTemplateTest` — SQL 正确性、表名注入。
- `ExtensionScannerTest` — 只扫到 `@ExtensionModel` 类。

**验证**：`mvn -pl core -am test -Dtest=ExtensionRouterTest,DdlTemplateTest,ExtensionScannerTest`

---

## M4 · `ExtensionClientImpl`

**目标**：核心 CRUD 实现，含乐观锁与强制身份注入。

**新建文件**（`backend/core/.../plugin/extension/`）：
- `ExtensionClientImpl.java` — 实现 `ExtensionClient`，构造注入 `JdbcTemplate` / `ExtensionRouter` / `pluginId` / `ExtensionQueryDialect` / `ObjectMapper`。
  - `create`：框架填 `group_name`/`kind`/`version=1`/时间戳，INSERT；主键冲突 → `DuplicateExtensionException`。
  - `update`：`UPDATE ... WHERE name=? AND group_name=? AND kind=? AND version=?`，受影响行数 0 → `OptimisticLockException`。
  - `delete` / `get`：均带 `group_name+kind` 过滤。
  - `list` / `count` / `listAll`：委托 `ExtensionQueryDialect`（SQLite 走内存过滤）。
  - `getManagedTables`：从 `PluginSchemaManager.ownership` 查当前 pluginId 拥有的表（注入 `PluginSchemaManager` 或由工厂传入 ownedTables）。
- `ExtensionRowMapper.java` — 反序列化行到 `AbstractExtension<T>`（用 ObjectMapper 解析 metadata/spec TEXT）。

**单元测试**（内存 SQLite）：
- CRUD 全流程
- 乐观锁冲突
- **跨插件隔离负向用例**：两个 `ExtensionClientImpl`（不同 pluginId）操作同一 SHARED 表，A 读不到 B 写的数据
- spec 内存过滤（SQLite 方言）

**验证**：`mvn -pl core -am test -Dtest=ExtensionClientImplTest`

---

## M5 · 生命周期集成

**目标**：表自动建/删，钩子修好，purge API 就位。

**新建文件**（`backend/core/.../plugin/`）：
- `extension/ExtensionStoreInitializer.java` — `@Component`，`@PostConstruct` 执行 `DdlTemplate.generate("extensions")` 建全局表。
- `extension/PluginSchemaManager.java` — `createSchemas(pluginId, classLoader)` / `purge(pluginId)` / `ownership`（`ConcurrentHashMap<pluginId, Set<table>>`）。
- `extension/PluginTableOwnership.java` — 持有者注册表（可并入 PluginSchemaManager）。

**改造文件**：
- `plugin/context/PluginSpringContextFactory.java`：
  - 删除 `executePluginDDL()` 及调用。
  - `loadPluginSpringContext()` 中：先 `pluginSchemaManager.createSchemas(...)` → 建 `ExtensionClientImpl`（绑定 pluginId + ownedTables）→ `registerSingleton("extensionClient", client)` → `scan` + `refresh`。
  - 删除原注册 `pluginDataAccess` 的代码。
- `plugin/listener/PluginLifecycleHook.java`：
  - `executeExtensionStartHooks` / `executeExtensionStopHooks`：把硬编码 `0L` 改为接收 `Long instanceId` 参数（方法签名扩展），从调用方传入真实值。
- `service/impl/InstanceServiceImpl.java`（或实例启停入口）：
  - 启动/停止实例时，把真实 `instanceId` 传入 `PluginLifecycleHook.executeExtensionStart/StopHooks(instanceId)`。
  - create/delete 实例流程中，遍历已启动插件扩展点调用 `onInstanceCreate(instanceId, config)` / `onInstanceDelete(instanceId)`。
- `controller/PluginManageController.java`（或对应管理控制器）：
  - 新增 `DELETE /api/pf4j/plugins/{pluginId}/data` → `pluginSchemaManager.purge(pluginId)`，校验插件状态非 STARTED。
- `handler/GlobalExceptionHandler.java`：
  - 增加 `@ExceptionHandler` for `DuplicateExtensionException`(409) / `ExtensionNotFoundException`(404) / `OptimisticLockException`(409) / `ExtensionStoreException`(500)。

**集成测试**：加载 plugin-l4d2 → 自动建 `ext_*` 表 → ExtensionClient 读写 → purge 删表。

**验证**：`mvn -pl core -am test` + 手动启动主应用观察日志建表。

---

## M6 · 删除旧机制

**目标**：清理替换掉的代码与遗留表，编译仍绿色。

> ⚠️ 顺序：先删 L4D2 的覆写（M7 之前），再删接口方法，避免编译断裂。本里程碑与 M7 第一步交织。

**删除文件**：
- `backend/plugin/.../context/IPluginDataAccess.java`
- `backend/core/.../plugin/context/PluginDataAccessImpl.java`
- `backend/core/.../plugin/context/PluginDataAccessException.java`（若存在）
- `backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql`

**删除代码片段**：
- `GameEnhancementExtension.java`：删 `getDdlScript()` / `getDeclaredTables()` 两个 default 方法。
- `L4D2Extension.java`：删第 110/115 行两个覆写方法。
- `plugin-l4d2/src/main/resources/plugin.properties`：删 `plugin.tables` 与 `plugin.ddl` 两行。
- `PluginSpringContextFactory.java`：删 `executePluginDDL()` 方法（M5 已停调用，此处删定义）。
- grep 全项目 `IPluginDataAccess` / `PluginDataAccessImpl` / `pluginDataAccess` / `getDdlScript` / `getDeclaredTables`，清掉残留引用。

**数据库清理**：在 SQLite 库执行 `DROP TABLE IF EXISTS` 删除 4 张遗留表（`l4d2_system_metric`/`l4d2_plugin_config`/`l4d2_download_task`/`l4d2_admin`）。可写一个一次性清理脚本或首次启动时由 `PluginSchemaManager` 检测旧表名前缀并清理（可选）。

**验证**：`mvn clean compile` 全模块通过；启动主应用无报错。

---

## M7 · L4D2 迁移

**目标**：用新模型重建 L4D2 数据访问。

**新建文件**（`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/`）：
- `Admin.java`（`@ExtensionModel(strategy=MODEL_ISOLATED)` extends `AbstractExtension<AdminSpec>`）
- `SystemMetric.java`（MODEL_ISOLATED）
- `DownloadTask.java`（MODEL_ISOLATED）
- `PluginConfig.java`（PLUGIN_ISOLATED）
- 各自 `Spec` POJO（`AdminSpec` / `SystemMetricSpec` / `DownloadTaskSpec` / `PluginConfigSpec`），字段从旧表列映射（旧 `instance_id` → spec.instanceId，无外键）。

**改造文件**（`backend/plugin-l4d2/.../controller/`）：
- `AdminController.java`：删 `ConcurrentHashMap adminCache` + `getAdminsFromDatabase()` TODO，注入 `ExtensionClient`，list/add/remove 改为 ExtensionClient 调用（设计文档 §7.3）。
- `MonitorController.java`：删 `queryHistoryFromDatabase()` 的 `new Random()` mock，改为读写 `SystemMetric` Extension。
- 其余控制器（Rcon/Map/ServerConfig）若用 RCON/主应用服务则不动。

**集成测试**：L4D2 `AdminController` 用真实 ExtensionClient 增删查。

**验证**：`mvn -pl plugin-l4d2 -am test`；启动后通过 API 验证 Admin 增删查落库到 `ext_plugin_l4d2_admin`。

---

## M8 · 测试收尾与文档

- 补回归测试：主应用 7 张基座表读写不受影响；插件 STOP/重启数据保留（不 DROP）。
- `mvn test` 全绿。
- 更新 `docs/CODE_WIKI.md` 第 5.2/11 节相关描述（旧 `IPluginDataAccess` → 新 `ExtensionClient`，删除已知改进点中已修复项）。
- 更新 `backend/AGENTS.md` 插件开发指南：新增 Extension 模型写法示例。

---

## 并行与依赖

- M1 → M2 → M3 → M4 严格顺序（后者依赖前者类型）。
- M5 依赖 M3/M4。
- M6 与 M7 第一步交织（删 L4D2 覆写必须在删接口方法同一次提交）。
- M5、M6、M7 完成后 M8 收尾。

## 风险点

- `ExtensionScanner` 类路径扫描在 PF4J 插件 ClassLoader 下的兼容性——优先复用项目已有扫描工具，必要时退化为遍历插件 JAR entries。
- `PluginSpringContextFactory` 改造影响插件加载主流程——改完先手动加载 plugin-l4d2 验证。
- 删接口方法会破坏任何未迁移的第三方插件——本项目仅 plugin-l4d2，已确认可破坏性变更。
