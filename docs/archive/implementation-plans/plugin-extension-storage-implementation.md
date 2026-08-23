# 插件扩展存储（Extension 宽表）实现计划

> 对应设计 spec：`../design-specs/2026-07-14-plugin-extension-storage-design.md`（已归档）
> 对应原始里程碑：`docs/superpowers/plans/2026-07-14-plugin-extension-storage-plan.md`（历史路径，文件未纳入仓库）
>
> 本计划基于源码探索落地，所有文件路径均已核实。

---

## 摘要

用 Halo 风格统一 JSON 宽表 + 三层路由策略（SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED）替换现有插件数据访问层。`ExtensionClient` 成为插件唯一持久化入口，框架自动注入 `group_name`/`kind` 身份过滤。彻底删除 `IPluginDataAccess`/`PluginDataAccessImpl`/`getDdlScript()`/`getDeclaredTables()`，DROP L4D2 的 4 张遗留表并用新模型重建。顺手修 `instanceId=0L` 硬编码与 `onInstanceCreate/Delete` 未调用两个 bug。

---

## 当前状态分析

### 要删除的代码（已核实路径）

| 文件 | 处理 |
|------|------|
| `backend/plugin/src/main/java/com/gameplatform/plugin/context/IPluginDataAccess.java` | 删除整个文件 |
| `backend/core/src/main/java/com/gameplatform/plugin/context/PluginDataAccessImpl.java` | 删除整个文件 |
| `backend/plugin/src/main/java/com/gameplatform/plugin/context/PluginDataAccessException.java`（若存在） | 删除 |
| `backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql` | 删除 |
| `GameEnhancementExtension.java` 第 190-206 行 `getDdlScript()`/`getDeclaredTables()` | 删除两个 default 方法 |
| `L4D2Extension.java` 第 110-115 行两个覆写 | 删除 |
| `plugin-l4d2/plugin.properties` 第 8-9 行 `plugin.tables`/`plugin.ddl` | 删除两行 |
| `PluginSpringContextFactory.java` 第 196-211 行 `executePluginDDL()` | 删除方法 |
| `PluginSpringContextFactory.java` 第 64-74 行 DDL 执行块 | 删除 |

### 要改造的代码（已核实路径）

| 文件 | 改造内容 |
|------|---------|
| `backend/core/.../plugin/context/PluginSpringContextFactory.java` | 第 82-85 行：`pluginDataAccess` Bean → `extensionClient` Bean；先调 `PluginSchemaManager.createSchemas()` |
| `backend/core/.../plugin/context/DefaultPluginContext.java` | 第 24-25 行：删 `dataAccess`/`declaredTables` 字段 |
| `backend/plugin/.../plugin/context/PluginContext.java` | 删 `getDataAccess()`/`getDeclaredTables()` 方法声明 |
| `backend/core/.../plugin/listener/PluginLifecycleHook.java` | 第 235-280 行：从 `onPluginStart/Stop` 移除 `executeExtensionStart/StopHooks` 调用（语义错误）；新增 `executeInstanceCreateHooks`/`executeInstanceStartHooks(instanceId)`/`executeInstanceStopHooks(instanceId)`/`executeInstanceDeleteHooks(instanceId)` 方法 |
| `backend/core/.../service/impl/InstanceServiceImpl.java` | 第 50-87(create)/114-131(delete)/176-227(start)/229-281(stop)：注入 `PluginLifecycleHook`，调用对应实例钩子 |
| `backend/core/.../controller/PluginController.java` | 第 112-117 行后：新增 `DELETE /{pluginId}/data` 端点 |
| `backend/core/.../common/exception/GlobalExceptionHandler.java` | 新增 4 个 `@ExceptionHandler`（Duplicate/NotFound/OptimisticLock/Store） |
| `backend/plugin-l4d2/.../controller/AdminController.java` | 删 `ConcurrentHashMap adminCache`，改用 `ExtensionClient` |
| `backend/plugin-l4d2/.../controller/MonitorController.java` | 删 `Random()` mock，改用 `ExtensionClient` 读写 `SystemMetric` |

### 关键基础设施现状

- `JdbcTemplate` Bean：通过 `mybatis-plus-spring-boot3-starter` 传递引入，可直接注入。
- `ObjectMapper` Bean：Spring Boot `JacksonAutoConfiguration` 自动注册，可直接注入。
- 类路径扫描：无 Reflections/ClassGraph 依赖 → 用 Spring 内置 `ClassPathScanningCandidateComponentProvider`。
- `GlobalExceptionHandler`：`@RestControllerAdvice`，返回 `Result<Void>`，已有 12 个 handler。
- `PluginController`：`@RequestMapping("/plugins")`，现有 9 个端点。
- `InstanceServiceImpl`：4 个生命周期方法，**当前与插件钩子完全无耦合**。

---

## 实施步骤（按编译绿色原则排序）

### 阶段 1：api 契约层（M1）

**新建** `backend/api/src/main/java/com/gameplatform/api/extension/`：

1. **`AbstractExtension.java`**
   ```java
   package com.gameplatform.api.extension;
   public abstract class AbstractExtension<T> {
       private String name;
       private String groupName;      // 框架填充
       private String kind;            // 框架填充
       private Integer version;        // 框架管理
       private ExtensionMetadata metadata;
       private T spec;
       private String status;
       // getters/setters
   }
   ```

2. **`ExtensionMetadata.java`**
   ```java
   package com.gameplatform.api.extension;
   public class ExtensionMetadata {
       private Map<String, String> labels;
       private Map<String, String> annotations;
       private Long creationTimestamp;
       private Long updateTimestamp;
       // getters/setters
   }
   ```

**验证**：`mvn -pl api -am compile`

---

### 阶段 2：plugin SDK 层（M2）

**新建** `backend/plugin/src/main/java/com/gameplatform/plugin/extension/`：

3. **`ExtensionModel.java`** — `@Target(TYPE) @Retention(RUNTIME)` 注解，属性 `strategy()`/`group()`/`kind()`。
4. **`Strategy.java`** — 枚举 `SHARED`/`PLUGIN_ISOLATED`/`MODEL_ISOLATED`。
5. **`ExtensionClient.java`** — 接口（spec §5.1 的方法签名）。
6. **`ListOptions.java`** + **`SpecFilter.java`** — 含 builder。
7. **`exception/ExtensionStoreException.java`**（runtime 基类）
8. **`exception/DuplicateExtensionException.java`**
9. **`exception/ExtensionNotFoundException.java`**
10. **`exception/OptimisticLockException.java`**

**改造** `backend/plugin/.../context/PluginContext.java`：
11. 删除 `getDataAccess()` 和 `getDeclaredTables()` 方法声明（以及 `import IPluginDataAccess`）。

**验证**：`mvn -pl plugin -am compile`（此阶段 plugin 模块不再引用 IPluginDataAccess）

---

### 阶段 3：core 基础设施（M3）

**新建** `backend/core/src/main/java/com/gameplatform/plugin/extension/`：

12. **`ResolvedRoute.java`** — record/table 值对象（`table`/`group`/`kind`/`strategy`）。
13. **`ExtensionRouter.java`**
    - `resolve(Class<? extends AbstractExtension<?>>, String pluginId) → ResolvedRoute`
    - `sanitize(String)`：非 `[a-z0-9_]` → `_`，转小写
    - 默认 group=pluginId、kind=simpleClassName，注解可覆盖
14. **`DdlTemplate.java`** — `generate(String tableName) → String`（spec §4.1 SQLite DDL + 3 个基础索引）
15. **`ExtensionScanner.java`**
    - 用 `ClassPathScanningCandidateComponentProvider(false)` + `AnnotationTypeFilter(ExtensionModel.class)`
    - `setResourceLoader(new DefaultResourceLoader(pluginClassLoader))`
    - `scan(String basePackage, ClassLoader) → Set<Class<? extends AbstractExtension<?>>>`
16. **`ExtensionQueryDialect.java`**（接口）— `<T> List<T> list(ResolvedRoute, Class<T>, ListOptions, JdbcTemplate, ObjectMapper)`
17. **`SqliteQueryDialect.java`** — 按 group_name+kind+status 拉 SQL 行，反序列化后内存过滤 specFilters/labelSelector

**单元测试** `backend/core/src/test/java/.../plugin/extension/`：
18. `ExtensionRouterTest` — 三策略表名 / sanitize / 默认推导 / 注解覆盖
19. `DdlTemplateTest` — SQL 正确性
20. `ExtensionScannerTest` — 扫描精度

**验证**：`mvn -pl core -am test -Dtest=ExtensionRouterTest,DdlTemplateTest,ExtensionScannerTest`

---

### 阶段 4：ExtensionClientImpl（M4）

**新建** `backend/core/.../plugin/extension/`：

21. **`ExtensionClientImpl.java`** — 实现 `ExtensionClient`
    - 构造注入：`JdbcTemplate`/`ExtensionRouter`/`String pluginId`/`ExtensionQueryDialect`/`ObjectMapper`/`Set<String> ownedTables`
    - `create(T)`：框架填 group/kind/version=1/时间戳，INSERT；主键冲突 → `DuplicateExtensionException`
    - `update(T)`：`UPDATE ... WHERE name=? AND group_name=? AND kind=? AND version=?`；受影响行数 0 → `OptimisticLockException`
    - `delete/get`：均带 group_name+kind 过滤
    - `list/count/listAll`：委托 `ExtensionQueryDialect`
    - `getManagedTables()`：返回 ownedTables
22. **`ExtensionRowMapper.java`** — `RowMapper<AbstractExtension<T>>`，用 ObjectMapper 反序列化 metadata/spec TEXT

**单元测试**：
23. `ExtensionClientImplTest`（内存 SQLite）— CRUD / 乐观锁冲突 / **跨插件隔离负向用例** / spec 内存过滤

**验证**：`mvn -pl core -am test -Dtest=ExtensionClientImplTest`

---

### 阶段 5：生命周期集成（M5）

**新建** `backend/core/.../plugin/extension/`：

24. **`ExtensionStoreInitializer.java`** — `@Component`，`@PostConstruct` 执行 `jdbcTemplate.execute(DdlTemplate.generate("extensions"))`
25. **`PluginSchemaManager.java`**
    - `createSchemas(String pluginId, ClassLoader cl, String basePackage) → Set<String>`：扫描 `@ExtensionModel` 类，对非 SHARED 建 `ext_*` 表，注册 ownership
    - `purge(String pluginId)`：DROP 该插件所有表
    - `ownership`：`ConcurrentHashMap<String, Set<String>>`
    - `getOwnedTables(String pluginId) → Set<String>`

**改造** `PluginSpringContextFactory.java`：
26. 第 64-74 行：删除 DDL 执行块
27. 第 76-89 行：在 `childContext.scan()` 之前，调 `pluginSchemaManager.createSchemas(pluginId, wrapper.getPluginClassLoader(), basePackage)`；创建 `ExtensionClientImpl` 并 `registerSingleton("extensionClient", client)`
28. 第 82-85 行：删除 `pluginDataAccess` 注册
29. 第 106-114 行：`DefaultPluginContext.builder()` 去掉 `.dataAccess(...)`/`.declaredTables(...)`
30. 第 196-211 行：删除 `executePluginDDL()` 方法
31. 第 344 行 `standardKeys`：移除 `plugin.tables`/`plugin.ddl`（可选，留着无害）

**改造** `DefaultPluginContext.java`：
32. 删除 `dataAccess`/`declaredTables` 字段及相关 builder 方法

**改造** `PluginLifecycleHook.java`：
33. 第 66 行：从 `onPluginStart` 移除 `executeExtensionStartHooks(pluginId)` 调用
34. 第 83 行：从 `onPluginStop` 移除 `executeExtensionStopHooks(pluginId)` 调用
35. 第 235-280 行：删除 `executeExtensionStartHooks(String pluginId)`/`executeExtensionStopHooks(String pluginId)`
36. 新增方法：
    - `executeInstanceCreateHooks(Long instanceId, Map<String,Object> config)` — 遍历所有已启动插件扩展点调 `onInstanceCreate`
    - `executeInstanceStartHooks(Long instanceId)` — 遍历调 `onInstanceStart`
    - `executeInstanceStopHooks(Long instanceId)` — 遍历调 `onInstanceStop`
    - `executeInstanceDeleteHooks(Long instanceId)` — 遍历调 `onInstanceDelete`
    - 需注入 `PluginManager`（已有 `@Lazy` 注入）

**改造** `InstanceServiceImpl.java`：
37. 注入 `PluginLifecycleHook`（构造器或 `@Lazy` 避免循环依赖）
38. `createInstance`（第 50-87 行末尾）：调 `pluginLifecycleHook.executeInstanceCreateHooks(id, configMap)`
39. `startInstance`（第 176-227 行，成功后）：调 `pluginLifecycleHook.executeInstanceStartHooks(id)`
40. `stopInstance`（第 229-281 行，成功后）：调 `pluginLifecycleHook.executeInstanceStopHooks(id)`
41. `deleteInstance`（第 114-131 行，删除前或后）：调 `pluginLifecycleHook.executeInstanceDeleteHooks(id)`

**改造** `PluginController.java`：
42. 第 117 行后新增：
    ```java
    @DeleteMapping("/{pluginId}/data")
    public Result<Void> purgePluginData(@PathVariable String pluginId) { ... }
    ```
    注入 `PluginSchemaManager`，校验插件非 STARTED 后调 `purge`。

**改造** `GlobalExceptionHandler.java`：
43. 新增 4 个 `@ExceptionHandler`：
    - `DuplicateExtensionException` → `Result.fail(409, ...)`
    - `ExtensionNotFoundException` → `Result.fail(ResultCode.NOT_FOUND)`
    - `OptimisticLockException` → `Result.fail(409, ...)`
    - `ExtensionStoreException` → `Result.fail(ResultCode.INTERNAL_SERVER_ERROR)`

**验证**：`mvn -pl core -am compile` + 手动启动观察建表日志

---

### 阶段 6：删除旧机制（M6）

> 与阶段 7 第一步交织，同次提交完成。

44. 删除 `backend/plugin/.../context/IPluginDataAccess.java`
45. 删除 `backend/core/.../plugin/context/PluginDataAccessImpl.java`
46. 删除 `PluginDataAccessException`（若存在）
47. 删除 `GameEnhancementExtension.java` 第 190-206 行 `getDdlScript()`/`getDeclaredTables()`
48. 删除 `L4D2Extension.java` 第 110-115 行两个覆写
49. 删除 `backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql`
50. 编辑 `plugin-l4d2/plugin.properties`：删除第 8-9 行 `plugin.tables`/`plugin.ddl`
51. grep 全项目 `IPluginDataAccess`/`PluginDataAccessImpl`/`pluginDataAccess`/`getDdlScript`/`getDeclaredTables`，清掉残留引用

**验证**：`mvn clean compile` 全模块通过

---

### 阶段 7：L4D2 迁移（M7）

**新建** `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/`：

52. **`Admin.java`** — `@ExtensionModel(strategy=MODEL_ISOLATED) extends AbstractExtension<AdminSpec>`
53. **`AdminSpec.java`** — `instanceId`/`steamId`/`name`/`level`
54. **`SystemMetric.java`** — `@ExtensionModel(strategy=MODEL_ISOLATED) extends AbstractExtension<SystemMetricSpec>`
55. **`SystemMetricSpec.java`** — `instanceId`/`metricType`/`value`/`timestamp`
56. **`DownloadTask.java`** — `@ExtensionModel(strategy=MODEL_ISOLATED) extends AbstractExtension<DownloadTaskSpec>`
57. **`DownloadTaskSpec.java`** — `instanceId`/`url`/`status`/`progress`
58. **`PluginConfig.java`** — `@ExtensionModel(strategy=PLUGIN_ISOLATED) extends AbstractExtension<PluginConfigSpec>`
59. **`PluginConfigSpec.java`** — `key`/`value`/`description`

**改造** `AdminController.java`：
60. 删 `ConcurrentHashMap<Long, List<AdminVO>> adminCache` 字段 + `getAdminsFromDatabase()` 方法
61. 注入 `ExtensionClient client`
62. list/add/remove 改为 `client.list(Admin.class, ...)`/`client.create(admin)`/`client.delete(Admin.class, name)`

**改造** `MonitorController.java`：
63. 删 `queryHistoryFromDatabase()` 的 `new Random()` mock
64. 注入 `ExtensionClient`，改为读写 `SystemMetric` Extension

**验证**：`mvn -pl plugin-l4d2 -am compile` + 启动后通过 API 验证 Admin 增删查

---

### 阶段 8：测试收尾（M8）

65. 补回归测试：基座表读写不受影响；插件 STOP/重启数据保留
66. `mvn test` 全绿
67. 更新 `docs/CODE_WIKI.md` 第 5.2/11 节（旧 `IPluginDataAccess` → 新 `ExtensionClient`）
68. 更新 `backend/AGENTS.md` 插件开发指南（新增 Extension 模型写法）

---

## 假设与决策

| # | 决策 | 依据 |
|---|------|------|
| 1 | `ExtensionScanner` 用 Spring `ClassPathScanningCandidateComponentProvider` | core pom 无 Reflections/ClassGraph，spring-context 已传递引入 |
| 2 | `ObjectMapper` 直接注入 Spring Boot 自动配置 Bean | 项目无自定义 `@Bean ObjectMapper`，自动配置已满足 |
| 3 | 从 `PluginLifecycleHook.onPluginStart/Stop` 移除 `executeExtensionStart/StopHooks` | 现有调用是语义 bug——`onInstanceStart` 应在实例启停时触发，非插件启停时 |
| 4 | `InstanceServiceImpl` 注入 `PluginLifecycleHook`（`@Lazy` 防循环依赖） | InstanceService 与 PluginLifecycleHook 都依赖 PluginManager，需防循环 |
| 5 | `PluginContext`/`DefaultPluginContext` 删除 `dataAccess`/`declaredTables` | ExtensionClient 作为独立 Bean 注入子容器，无需经 PluginContext 传递 |
| 6 | purge API 校验插件非 STARTED 才允许执行 | 避免运行中插件表被 DROP 导致读写异常 |
| 7 | 遗留 4 张 L4D2 表不在代码中 DROP，由 PluginSchemaManager 首次启动时检测旧前缀清理（可选）或手动清理 | 新表名 `ext_plugin_l4d2_*` 与旧表名 `l4d2_*` 不同，不会冲突；旧表残留无害但浪费空间 |

---

## 验证步骤

| 阶段 | 命令 |
|------|------|
| M1 | `mvn -pl api -am compile` |
| M2 | `mvn -pl plugin -am compile` |
| M3 | `mvn -pl core -am test -Dtest=ExtensionRouterTest,DdlTemplateTest,ExtensionScannerTest` |
| M4 | `mvn -pl core -am test -Dtest=ExtensionClientImplTest` |
| M5 | `mvn -pl core -am compile` + 启动主应用观察 `extensions` 表与 `ext_*` 表建表日志 |
| M6 | `mvn clean compile`（全模块） |
| M7 | `mvn -pl plugin-l4d2 -am compile` + 启动后 `curl` 验证 Admin CRUD |
| M8 | `mvn test`（全量绿色） |

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| `ClassPathScanningCandidateComponentProvider` 在 PF4J 插件 ClassLoader 下的兼容性 | 设 `setResourceLoader(new DefaultResourceLoader(pluginClassLoader))`；如失败退化为遍历插件 JAR entries |
| `PluginSpringContextFactory` 改造影响插件加载主流程 | 改完先手动加载 plugin-l4d2 验证端点注册正常 |
| 删 `IPluginDataAccess` 破坏 PluginContext 接口契约 | 同步改 PluginContext/DefaultPluginContext，plugin-l4d2 是唯一消费者 |
| `InstanceServiceImpl` 注入 `PluginLifecycleHook` 循环依赖 | 用 `@Lazy` 注入 |
| 旧 `l4d2_*` 表残留 | 文档说明手动 DROP，或在 PluginSchemaManager 加一次性清理逻辑 |
