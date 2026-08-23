# 插件扩展存储重构 — 续接实现计划

> 接续前次会话已批准的实现计划 `.trae/documents/plugin-extension-storage-implementation.md`。
> 上次会话完成 M1/M2/M3/M4 的核心代码创建，但 core 模块尚未编译通过，且发现一个关键 DDL bug 需在落地前修复。
>
> 本计划仅描述**剩余工作**（M3 收尾 → M8），不重复已完成内容。

---

## 摘要

续接插件扩展存储重构的剩余工作：
1. **关键修复**：`DdlTemplate` 主键从单列 `name` 改为复合 `(name, group_name, kind)`，否则 SHARED/PLUGIN_ISOLATED 策略下跨插件/跨 kind 同名资源会误报冲突。
2. **M3 收尾**：让 core 模块恢复编译绿色（`PluginSpringContextFactory` 仍引用已删除的 `IPluginDataAccess`/`PluginDataAccessImpl`）。
3. **M5**：创建 `PluginSchemaManager`/`ExtensionStoreInitializer`，改造 `PluginSpringContextFactory`/`PluginLifecycleHook`/`InstanceServiceImpl`/`PluginController`/`GlobalExceptionHandler`。
4. **M6**：清理 `GameEnhancementExtension.getDdlScript()/getDeclaredTables()`、`L4D2Extension` 覆写、`l4d2_tables.sql`、`plugin.properties` 旧键。
5. **M7**：L4D2 迁移 — 4 个 `Extension` 子类 + 4 个 Spec POJO，改造 `AdminController`/`MonitorController`。
6. **M8**：补回归测试，更新 `CODE_WIKI.md` 与 `backend/AGENTS.md`。

---

## 当前状态分析

### 已完成（M1/M2/M3/M4 主体代码已存在）

| 文件 | 状态 |
|------|------|
| `backend/api/.../api/extension/AbstractExtension.java` | ✅ 存在 |
| `backend/api/.../api/extension/ExtensionMetadata.java` | ✅ 存在 |
| `backend/plugin/.../plugin/extension/{ExtensionModel,Strategy,ExtensionClient,ListOptions,SpecFilter}.java` | ✅ 存在 |
| `backend/plugin/.../plugin/extension/exception/{ExtensionStoreException,DuplicateExtensionException,ExtensionNotFoundException,OptimisticLockException}.java` | ✅ 存在 |
| `backend/plugin/.../plugin/context/PluginContext.java` | ✅ 已改造（删除 `getDataAccess()`/`getDeclaredTables()`） |
| `backend/plugin/.../plugin/context/PluginContextHolder.java` | ✅ 已改造（注释清理） |
| `backend/core/.../plugin/extension/{ResolvedRoute,ExtensionRouter,DdlTemplate,ExtensionScanner,ExtensionQueryDialect,SqliteQueryDialect,ExtensionRowMapper,ExtensionClientImpl}.java` | ✅ 存在 |
| `backend/core/.../plugin/context/DefaultPluginContext.java` | ✅ 已改造（删除 `dataAccess`/`declaredTables` 字段） |
| `backend/plugin/.../context/IPluginDataAccess.java` | ✅ 已删除 |
| `backend/plugin/.../exception/PluginDataAccessException.java` | ✅ 已删除 |
| `backend/core/.../plugin/context/PluginDataAccessImpl.java` | ✅ 已删除 |

### 剩余阻塞（core 模块编译失败）

`PluginSpringContextFactory.java` 第 67/71/83/84/85/111/112/205 行仍引用 `IPluginDataAccess`/`PluginDataAccessImpl`/`extension.getDdlScript()`/`extension.getDeclaredTables()`。这些类/方法已被删除或将被删除。

### 关键 Bug（落地前必修）

`DdlTemplate.generate()` 与设计 spec §4.1 都把主键写成了单列：
```sql
name VARCHAR(64) PRIMARY KEY
```

但 spec §1 决策"身份与过滤"要求 `WHERE group_name=? AND kind=?` 强制过滤，意味着资源身份是 `(group_name, kind, name)`。单列主键会导致：
- SHARED 表中插件 A 创建 `name=default kind=Config` 后，插件 B 创建 `name=default kind=Config` 会因主键冲突误报 `DuplicateExtensionException`。
- PLUGIN_ISOLATED 表中同插件不同 kind 的同名资源也会误冲突。

修复方式：将主键改为 `PRIMARY KEY (name, group_name, kind)`。这同时与 `ExtensionClientImpl.update()` 的 `WHERE name=? AND group_name=? AND kind=? AND version=?` 语义一致。

### 剩余待创建/改造的文件清单

| # | 文件 | 操作 | 所属阶段 |
|---|------|------|---------|
| 1 | `backend/core/.../plugin/extension/DdlTemplate.java` | 改造（主键改复合） | M3 收尾 |
| 2 | `backend/core/.../plugin/extension/PluginSchemaManager.java` | 新建 | M5 |
| 3 | `backend/core/.../plugin/extension/ExtensionStoreInitializer.java` | 新建 | M5 |
| 4 | `backend/core/.../plugin/context/PluginSpringContextFactory.java` | 改造 | M5 |
| 5 | `backend/core/.../plugin/listener/PluginLifecycleHook.java` | 改造 | M5 |
| 6 | `backend/core/.../service/impl/InstanceServiceImpl.java` | 改造 | M5 |
| 7 | `backend/core/.../controller/PluginController.java` | 改造 | M5 |
| 8 | `backend/core/.../common/exception/GlobalExceptionHandler.java` | 改造 | M5 |
| 9 | `backend/plugin/.../extension/GameEnhancementExtension.java` | 改造（删 2 个 default 方法） | M6 |
| 10 | `backend/plugin-l4d2/.../L4D2Extension.java` | 改造（删 2 个覆写） | M6 |
| 11 | `backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql` | 删除 | M6 |
| 12 | `backend/plugin-l4d2/src/main/resources/plugin.properties` | 改造（删 2 行） | M6 |
| 13 | `backend/plugin-l4d2/.../l4d2/extension/{Admin,SystemMetric,DownloadTask,PluginConfig}.java` | 新建（4 个） | M7 |
| 14 | `backend/plugin-l4d2/.../l4d2/extension/spec/{AdminSpec,SystemMetricSpec,DownloadTaskSpec,PluginConfigSpec}.java` | 新建（4 个） | M7 |
| 15 | `backend/plugin-l4d2/.../l4d2/controller/AdminController.java` | 改造 | M7 |
| 16 | `backend/plugin-l4d2/.../l4d2/controller/MonitorController.java` | 改造 | M7 |
| 17 | `backend/core/src/test/java/.../plugin/extension/ExtensionRouterTest.java` | 新建 | M3 收尾 |
| 18 | `backend/core/src/test/java/.../plugin/extension/DdlTemplateTest.java` | 新建 | M3 收尾 |
| 19 | `backend/core/src/test/java/.../plugin/extension/ExtensionClientImplTest.java` | 新建 | M4 |
| 20 | `docs/CODE_WIKI.md` 第 5.2/11 节 | 改造 | M8 |
| 21 | `backend/AGENTS.md` 插件开发指南 | 改造 | M8 |

---

## 实施步骤（按编译绿色 + 依赖顺序）

### 阶段 A：M3 收尾 — 让 core 编译通过

**A1. 修复 `DdlTemplate.generate()` 主键**

文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/DdlTemplate.java`

把：
```java
+ "name VARCHAR(64) PRIMARY KEY, "
```
改为：
```java
+ "name VARCHAR(64) NOT NULL, "
+ "group_name VARCHAR(128) NOT NULL, "
+ "kind VARCHAR(128) NOT NULL, "
+ "version INT DEFAULT 1, "
+ "metadata TEXT NOT NULL, "
+ "spec TEXT NOT NULL, "
+ "status VARCHAR(32) DEFAULT 'ACTIVE', "
+ "creation_timestamp BIGINT, "
+ "update_timestamp BIGINT, "
+ "PRIMARY KEY (name, group_name, kind)"
```
（去掉原先重复的 group_name/kind/version/... 行，只保留主键变更）

**A2. 改造 `PluginSpringContextFactory.java`（提前完成 M5 的一部分以解除编译阻塞）**

> 由于 `PluginSchemaManager` 尚未创建，本步会临时把 schema 创建逻辑内联，等 A3 完成后再次重构为调用 `PluginSchemaManager`。

文件：`backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`

具体改造：
- **删除第 64-74 行**：DDL 执行块（`tablesStr`/`allowedTables`/`ddlPath`/`executePluginDDL`）
- **替换第 82-85 行**：删除 `IPluginDataAccess dataAccess = new PluginDataAccessImpl(...)` 与 `registerSingleton("pluginDataAccess", ...)`，改为：
  ```java
  // 4. 扫描 @ExtensionModel 类并创建/校验表 schema
  ExtensionScanner scanner = new ExtensionScanner();
  Set<Class<? extends AbstractExtension<?>>> modelClasses =
          scanner.scan(basePackage, wrapper.getPluginClassLoader());
  Set<String> ownedTables = new HashSet<>();
  ExtensionRouter router = new ExtensionRouter();
  for (Class<? extends AbstractExtension<?>> clazz : modelClasses) {
      ResolvedRoute route = router.resolve(clazz, pluginId);
      if (route.strategy() != Strategy.SHARED) {
          jdbcTemplate.execute(DdlTemplate.generate(route.table()));
          ownedTables.add(route.table());
      }
  }
  log.info("  已声明 {} 个 Extension 模型，独立表: {}", modelClasses.size(), ownedTables);

  // 5. 注册 ExtensionClient Bean
  ObjectMapper objectMapper = mainContext.getBean(ObjectMapper.class);
  ExtensionQueryDialect queryDialect = mainContext.getBean(ExtensionQueryDialect.class);
  ExtensionClientImpl extensionClient = new ExtensionClientImpl(
          jdbcTemplate, router, pluginId, queryDialect, objectMapper, ownedTables);
  childContext.getBeanFactory().registerSingleton("extensionClient", extensionClient);
  ```
- **替换第 106-114 行**：`DefaultPluginContext.builder()` 调用去掉 `.dataAccess(dataAccess)` 与 `.declaredTables(allowedTables)` 两行
- **删除第 196-211 行**：`executePluginDDL()` 方法整体删除
- **新增 import**：`com.gameplatform.api.extension.AbstractExtension`、`com.gameplatform.plugin.extension.*`、`com.fasterxml.jackson.databind.ObjectMapper`、`java.util.HashSet`

**A3. 创建 `PluginSchemaManager.java`**

文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/PluginSchemaManager.java`

```java
package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件 Extension 表 schema 管理器。
 * 负责插件加载时按 @ExtensionModel 注解建表，插件卸载时 DROP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginSchemaManager {

    private final JdbcTemplate jdbcTemplate;
    private final ExtensionRouter router;
    private final ExtensionScanner scanner;

    /** pluginId → 该插件拥有的物理表名集合 */
    private final ConcurrentHashMap<String, Set<String>> ownership = new ConcurrentHashMap<>();

    /**
     * 扫描插件 basePackage 下的 @ExtensionModel 类，对非 SHARED 策略建表。
     *
     * @return 该插件拥有的表名集合（不含 SHARED 全局表）
     */
    public Set<String> createSchemas(String pluginId, ClassLoader pluginClassLoader, String basePackage) {
        Set<Class<? extends AbstractExtension<?>>> classes = scanner.scan(basePackage, pluginClassLoader);
        Set<String> owned = new HashSet<>();
        for (Class<? extends AbstractExtension<?>> clazz : classes) {
            ResolvedRoute route = router.resolve(clazz, pluginId);
            if (route.strategy() == Strategy.SHARED) {
                continue;
            }
            jdbcTemplate.execute(DdlTemplate.generate(route.table()));
            owned.add(route.table());
            log.info("[PluginSchema] 插件 [{}] 建表: {} (kind={}, strategy={})",
                    pluginId, route.table(), route.kind(), route.strategy());
        }
        ownership.put(pluginId, owned);
        return owned;
    }

    /**
     * 删除插件所有专属表（purge API 调用）。
     */
    public void purge(String pluginId) {
        Set<String> tables = ownership.remove(pluginId);
        if (tables == null || tables.isEmpty()) {
            log.info("[PluginSchema] 插件 [{}] 无专属表可清理", pluginId);
            return;
        }
        for (String table : tables) {
            jdbcTemplate.execute(DdlTemplate.drop(table));
            log.info("[PluginSchema] 插件 [{}] 删表: {}", pluginId, table);
        }
    }

    /**
     * 获取插件拥有的表名集合（只读视图）。
     */
    public Set<String> getOwnedTables(String pluginId) {
        return Set.copyOf(ownership.getOrDefault(pluginId, Set.of()));
    }
}
```

**A4. 重构 A2 中的内联逻辑为调用 `PluginSchemaManager`**

回到 `PluginSpringContextFactory.java`，把 A2 中内联的扫描+建表代码替换为：
```java
PluginSchemaManager schemaManager = mainContext.getBean(PluginSchemaManager.class);
Set<String> ownedTables = schemaManager.createSchemas(pluginId, wrapper.getPluginClassLoader(), basePackage);

ObjectMapper objectMapper = mainContext.getBean(ObjectMapper.class);
ExtensionQueryDialect queryDialect = mainContext.getBean(ExtensionQueryDialect.class);
ExtensionRouter router = mainContext.getBean(ExtensionRouter.class);
ExtensionClientImpl extensionClient = new ExtensionClientImpl(
        jdbcTemplate, router, pluginId, queryDialect, objectMapper, ownedTables);
childContext.getBeanFactory().registerSingleton("extensionClient", extensionClient);
```
注入方式：在 `PluginSpringContextFactory` 字段加 `private final PluginSchemaManager schemaManager;`，由 `@RequiredArgsConstructor` 注入。同理加 `ExtensionRouter`/`ObjectMapper`/`ExtensionQueryDialect` 字段（也可继续用 `mainContext.getBean`，两者皆可，统一即可）。

**A5. 创建 `ExtensionStoreInitializer.java`**

文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionStoreInitializer.java`

```java
package com.gameplatform.plugin.extension;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时创建全局 SHARED 表 extensions。
 * 必须在插件加载之前完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionStoreInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(DdlTemplate.generate("extensions"));
        log.info("[ExtensionStore] 全局 extensions 表已就绪");
    }
}
```

**A6. 验证 M3 收尾**

```bash
cd backend
mvn -pl core -am compile
```
应当通过。如果失败，按错误信息修正 import / 字段注入。

**A7. 补 M3 单元测试（可与 A6 并行，但需 A6 通过后跑）**

文件：`backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionRouterTest.java`
- 测试三种策略的表名：SHARED→`extensions`、PLUGIN_ISOLATED→`ext_plugin_l4d2`、MODEL_ISOLATED→`ext_plugin_l4d2_admin`
- 测试 `sanitize("plugin-l4d2")` = `"plugin_l4d2"`
- 测试注解 `group()`/`kind()` 覆盖默认值

文件：`backend/core/src/test/java/com/gameplatform/plugin/extension/DdlTemplateTest.java`
- 断言 `generate("extensions")` 含 `PRIMARY KEY (name, group_name, kind)`
- 断言含 3 个 `CREATE INDEX`
- 断言 `drop("ext_x")` = `"DROP TABLE IF EXISTS ext_x"`

**验证**：`mvn -pl core -am test -Dtest=ExtensionRouterTest,DdlTemplateTest`

---

### 阶段 B：M4 — ExtensionClientImpl 单元测试

**B1. 创建 `ExtensionClientImplTest.java`**

文件：`backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionClientImplTest.java`

测试用 SQLite 内存库（`jdbc:sqlite::memory:`），通过 `DataSourceBuilder` 手动建 `JdbcTemplate`。每个测试方法 `@BeforeEach` 重建表。

测试用例：
1. `create_and_get_roundTrip` — 创建后能 get 到，字段值正确
2. `create_duplicate_throws` — 同名再创建抛 `DuplicateExtensionException`
3. `update_increments_version` — 更新后 version +1
4. `update_with_stale_version_throws_optimistic_lock` — 模拟旧版本号更新抛 `OptimisticLockException`
5. `update_nonexistent_throws_not_found`
6. `delete_removes_resource`
7. `cross_plugin_isolation` — 两个不同 pluginId 的 client，即使同名也不互相可见（用 SHARED 表+不同 group 验证）
8. `list_with_specFilter` — 内存过滤生效
9. `list_with_labelSelector` — label 过滤生效
10. `count_returns_correct_number`

测试用的 Spec POJO 内置为测试静态类（含 `String key`/`String value`）。

**验证**：`mvn -pl core -am test -Dtest=ExtensionClientImplTest`

---

### 阶段 C：M5 — 生命周期集成

> A2/A4 已完成 `PluginSpringContextFactory` 的 schema 创建与 `extensionClient` 注册。本阶段完成剩余 4 个文件。

**C1. 改造 `PluginLifecycleHook.java`**

文件：`backend/core/src/main/java/com/gameplatform/plugin/listener/PluginLifecycleHook.java`

- **第 66 行**：从 `onPluginStart` 删除 `executeExtensionStartHooks(pluginId)` 调用（语义 bug：实例钩子不应在插件启停时触发）
- **第 83 行**：从 `onPluginStop` 删除 `executeExtensionStopHooks(pluginId)` 调用
- **第 232-280 行**：删除 `executeExtensionStartHooks(String pluginId)` 与 `executeExtensionStopHooks(String pluginId)` 两个方法
- **新增 4 个实例钩子方法**（在文件末尾）：
  ```java
  /**
   * 实例创建后调用：遍历所有已启动插件的 GameEnhancementExtension 调 onInstanceCreate。
   */
  public void executeInstanceCreateHooks(Long instanceId, Map<String, Object> config) {
      if (pluginManager == null) return;
      for (PluginWrapper pw : pluginManager.getStartedPlugins()) {
          try {
              pluginManager.getExtensions(GameEnhancementExtension.class, pw.getPluginId())
                      .forEach(ext -> {
                          try { ext.onInstanceCreate(instanceId, config); }
                          catch (Exception e) {
                              log.error("onInstanceCreate 钩子失败: 插件={} 实例={}",
                                      pw.getPluginId(), instanceId, e);
                          }
                      });
          } catch (Exception e) {
              log.warn("获取插件 [{}] 扩展点失败", pw.getPluginId(), e);
          }
      }
  }

  /** 实例启动成功后调用。 */
  public void executeInstanceStartHooks(Long instanceId) { /* 类似，调 onInstanceStart(instanceId) */ }

  /** 实例停止成功后调用。 */
  public void executeInstanceStopHooks(Long instanceId) { /* 类似，调 onInstanceStop(instanceId) */ }

  /** 实例删除时调用（在 deleteInstance 删 DB 前/后均可，推荐删前调以保留 instanceId 上下文）。 */
  public void executeInstanceDeleteHooks(Long instanceId) { /* 类似，调 onInstanceDelete(instanceId) */ }
  ```
- **import**：`java.util.Map`、`org.pf4j.PluginWrapper`（已有）

**C2. 改造 `InstanceServiceImpl.java`**

文件：`backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java`

- **加字段**：在类字段区（第 43-48 行附近）加：
  ```java
  private final PluginLifecycleHook pluginLifecycleHook;
  ```
  由于 `@RequiredArgsConstructor` 已生成构造器，新字段会自动注入。
  若启动报循环依赖，改为 `@Lazy` setter 注入。
- **`createInstance` 方法（第 81-84 行 `instanceMapper.insert(instance)` 后、`logService.log` 后）**：
  ```java
  pluginLifecycleHook.executeInstanceCreateHooks(instance.getId(), instance.getConfigInfo());
  ```
- **`deleteInstance` 方法（第 127 行 `instanceMapper.deleteById(id)` 前）**：
  ```java
  pluginLifecycleHook.executeInstanceDeleteHooks(id);
  ```
- **`startInstance` 方法（第 208-211 行 success 块内，`logService.log` 后）**：
  ```java
  pluginLifecycleHook.executeInstanceStartHooks(id);
  ```
- **`stopInstance` 方法（第 261-265 行 success 块内，`logService.log` 后）**：
  ```java
  pluginLifecycleHook.executeInstanceStopHooks(id);
  ```
- **import**：`com.gameplatform.plugin.listener.PluginLifecycleHook`

**C3. 改造 `PluginController.java` — purge API**

文件：`backend/core/src/main/java/com/gameplatform/controller/PluginController.java`

- **加字段**：
  ```java
  private final PluginSchemaManager pluginSchemaManager;
  ```
- **加 import**：`com.gameplatform.plugin.extension.PluginSchemaManager`、`com.gameplatform.common.exception.BusinessException`、`com.gameplatform.entity.PluginInfo`（如需）、`org.pf4j.PluginManager`（如需运行时校验）
- **在第 117 行（`delete` 方法后）新增**：
  ```java
  /**
   * 清空插件数据（DROP 插件专属 Extension 表）。
   * 仅允许在插件非 STARTED 状态时调用。
   */
  @Operation(summary = "清空插件数据", description = "删除指定插件的所有 Extension 物理表")
  @DeleteMapping("/{pluginId}/data")
  @OperationLog(type = "PURGE", target = "PLUGIN", description = "清空插件数据")
  public Result<Void> purgePluginData(@Parameter(description = "插件唯一标识") @PathVariable String pluginId) {
      // 通过 pluginService 查状态；若状态为运行中则拒绝
      PluginVO plugin = pluginService.getPluginByPluginId(pluginId);
      if (plugin == null) {
          throw new BusinessException("插件不存在: " + pluginId);
      }
      if (plugin.getStatus() != null && plugin.getStatus() == 1) {
          throw new BusinessException("插件运行中，无法清空数据");
      }
      pluginSchemaManager.purge(pluginId);
      return Result.success();
  }
  ```
  注：需确认 `PluginVO.getStatus()` 字段语义（1=启用/运行中）。若与 PF4J 运行态不一致，加 `PluginManager` 注入并查 `pluginManager.getPlugin(pluginId).getPluginState()`。

**C4. 改造 `GlobalExceptionHandler.java` — 4 个新 handler**

文件：`backend/core/src/main/java/com/gameplatform/common/exception/GlobalExceptionHandler.java`

- **加 import**：
  ```java
  import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
  import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
  import com.gameplatform.plugin.extension.exception.ExtensionStoreException;
  import com.gameplatform.plugin.extension.exception.OptimisticLockException;
  import org.springframework.http.HttpStatus;
  ```
- **在最后一个 `@ExceptionHandler(Exception.class)` 之前新增 4 个 handler**：
  ```java
  /** 资源已存在（创建同名） */
  @ExceptionHandler(DuplicateExtensionException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Result<Void> handleDuplicateExtension(DuplicateExtensionException e) {
      log.warn("扩展资源已存在: {}", e.getMessage());
      return Result.fail(409, e.getMessage());
  }

  /** 资源不存在 */
  @ExceptionHandler(ExtensionNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Result<Void> handleExtensionNotFound(ExtensionNotFoundException e) {
      log.warn("扩展资源不存在: {}", e.getMessage());
      return Result.fail(ResultCode.NOT_FOUND.getCode(), e.getMessage());
  }

  /** 乐观锁冲突 */
  @ExceptionHandler(OptimisticLockException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Result<Void> handleOptimisticLock(OptimisticLockException e) {
      log.warn("扩展资源版本冲突: {}", e.getMessage());
      return Result.fail(409, e.getMessage());
  }

  /** 存储层通用错误 */
  @ExceptionHandler(ExtensionStoreException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Result<Void> handleExtensionStore(ExtensionStoreException e) {
      log.error("扩展存储错误: {}", e.getMessage(), e);
      return Result.fail(ResultCode.INTERNAL_SERVER_ERROR.getCode(), e.getMessage());
  }
  ```
  注：`ResultCode.NOT_FOUND` / `ResultCode.INTERNAL_SERVER_ERROR` 需确认存在；若不存在则用具体错误码常量（如 404/500）。

**C5. 验证 M5**

```bash
cd backend
mvn -pl core -am compile
mvn -pl core -am test -Dtest=PluginLifecycleHookTest  # 现有测试不应回归
```
手动启动主应用，观察日志含 `[ExtensionStore] 全局 extensions 表已就绪`。
加载 plugin-l4d2 时观察 `[PluginSchema] 插件 [plugin-l4d2] 建表: ext_plugin_l4d2_admin ...` 等建表日志。

---

### 阶段 D：M6 — 删除旧机制

> 与 M7 第一步交织：删 `getDdlScript`/`getDeclaredTables` 后 `L4D2Extension` 编译失败，必须同次提交补上新模型。

**D1. 删除 `GameEnhancementExtension` 的 2 个 default 方法**

文件：`backend/plugin/src/main/java/com/gameplatform/plugin/extension/GameEnhancementExtension.java`

删除第 190-206 行的 `getDdlScript()` 与 `getDeclaredTables()` 方法（含其上的注释块和分隔注释）。
同时删除文件顶部 `import java.util.Arrays;`（若已无其他用途）和 `import java.util.List;`（若 `getConfigFields()` 不再需要 — 检查后再决定，`getConfigFields()` 仍返回 `List<PluginConfigField>`，所以 `List` 保留）。

**D2. 删除 `L4D2Extension` 的 2 个覆写**

文件：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java`

删除第 109-120 行的 `getDdlScript()` 与 `getDeclaredTables()` 覆写。
删除 `import java.util.Arrays;`（仅这两个方法用到）。
保留 `import java.util.List;`、`import java.util.Map;`（其他方法用到）。

**D3. 删除 DDL 文件**

文件：`backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql` — **删除整个文件**。
可保留空 `ddl/` 目录或一并删除（推荐删除目录，避免无意义空目录）。

**D4. 改造 `plugin.properties`**

文件：`backend/plugin-l4d2/src/main/resources/plugin.properties`

删除第 8-9 行：
```
plugin.tables=l4d2_system_metric,l4d2_plugin_config,l4d2_download_task,l4d2_admin
plugin.ddl=ddl/l4d2_tables.sql
```
保留前 7 行（plugin.id/class/version/provider/description/gameCode/basePackage）。

**D5. grep 全项目清残留**

```
grep -rn "IPluginDataAccess\|PluginDataAccessImpl\|pluginDataAccess\|getDdlScript\|getDeclaredTables\|l4d2_tables.sql\|l4d2_system_metric\|l4d2_plugin_config\|l4d2_download_task\|l4d2_admin" backend/
```
预期：除 `InstanceServiceImpl`/`AdminController`/`MonitorController` 中尚未改造的引用外，应无其他残留。这些将在 M7 处理。

**D6. 验证 M6（plugin 模块编译）**

```bash
cd backend
mvn -pl plugin -am compile   # GameEnhancementExtension 改造后应通过
```
注：`plugin-l4d2` 此时仍编译失败（L4D2Extension 删了覆写后引用了已删的方法 — 不会，因为覆写删了反而不再引用；但 AdminController/MonitorController 仍引用即将废弃的 mock — 这两个 controller 当前不引用 `getDdlScript`/`getDeclaredTables`，所以应仍可编译）。
若 `plugin-l4d2` 编译失败，先跳过，进入 M7 后再整体验证。

---

### 阶段 E：M7 — L4D2 迁移

**E1. 新建 4 个 Extension 模型类 + 4 个 Spec POJO**

目录：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/`

| 文件 | 内容 |
|------|------|
| `Admin.java` | `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)` `extends AbstractExtension<AdminSpec>` |
| `spec/AdminSpec.java` | 字段：`Long instanceId`、`String steamId`、`String adminFlags`、`String remark`、`Boolean isActive`、`Long createTime`、`Long updateTime`（时间戳改 Long 与系统统一） |
| `SystemMetric.java` | `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)` `extends AbstractExtension<SystemMetricSpec>` |
| `spec/SystemMetricSpec.java` | 字段：`Long instanceId`、`Long timestamp`、`Double cpuPercent`、`Double cpuMaxCore`、`Double memUsed`、`Double memTotal`、`Double swapUsed`、`Double netUpSpeed`、`Double netDownSpeed`、`Double diskUsed`、`Double diskTotal` |
| `DownloadTask.java` | `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)` `extends AbstractExtension<DownloadTaskSpec>` |
| `spec/DownloadTaskSpec.java` | 字段：`Long instanceId`、`String url`、`Integer status`、`Double progress`、`String filename`、`Long fileSize`、`Long downloadedSize`、`Double downloadSpeed`、`String errorMessage`、`String fileType`、`String targetPath`、`Long startTime`、`Long completeTime`、`Integer retryCount`、`Integer maxRetry` |
| `PluginConfig.java` | `@ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)` `extends AbstractExtension<PluginConfigSpec>` |
| `spec/PluginConfigSpec.java` | 字段：`Long instanceId`、`String pluginName`、`String pluginStatus`、`String description`、`String fileList`、`String configFiles`、`String version`、`String author`、`Long enableTime` |

Spec POJO 规范：`public class XxxSpec { ... 字段 ... // getters/setters }`，可用 Lombok `@Data`。
Extension 类规范：
```java
package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;
import com.gameplatform.plugin.l4d2.extension.spec.AdminSpec;

@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class Admin extends AbstractExtension<AdminSpec> {
}
```

**命名约定**：Extension 资源 `name` 用什么？
- Admin：`"instance-{instanceId}-admin-{steamId}"`（保证全局唯一）
- SystemMetric：`"instance-{instanceId}-metric-{timestamp}"`（按时间戳天然唯一）
- DownloadTask：`"instance-{instanceId}-task-{uuid}"` 或自增 id 字符串
- PluginConfig：`"instance-{instanceId}-plugin-{pluginName}"`

**E2. 改造 `AdminController.java`**

文件：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`

- **删除字段**：`private final Map<Long, List<AdminVO>> adminCache`、`private final AtomicLong idGenerator`
- **新增字段**：
  ```java
  private final ExtensionClient extensionClient;
  ```
  （通过 `@RequiredArgsConstructor` 注入）
- **改造 `getAdminList`**：
  ```java
  ListOptions opts = ListOptions.builder()
          .specFilter(SpecFilter.of("instanceId", SpecFilter.Op.EQ, instanceId))
          .build();
  List<Admin> admins = extensionClient.list(Admin.class, opts);
  List<AdminVO> voList = admins.stream().map(this::toVO).collect(Collectors.toList());
  return Result.success(voList);
  ```
- **改造 `addAdmin`**：
  ```java
  Admin admin = new Admin();
  admin.setName("instance-" + dto.getInstanceId() + "-admin-" + dto.getSteamId());
  AdminSpec spec = new AdminSpec();
  spec.setInstanceId(dto.getInstanceId());
  spec.setSteamId(dto.getSteamId());
  spec.setAdminFlags(dto.getAdminFlags());
  spec.setRemark(dto.getRemark());
  spec.setIsActive(true);
  spec.setCreateTime(System.currentTimeMillis());
  spec.setUpdateTime(System.currentTimeMillis());
  admin.setSpec(spec);
  admin.setStatus("ACTIVE");
  extensionClient.create(admin);
  // 更新 admins.cfg 文件
  updateAdminsConfig(instance, voList_from_extensionClient);
  ```
- **改造 `deleteAdmin`**：
  ```java
  extensionClient.delete(Admin.class, "instance-" + instanceId + "-admin-" + steamId);
  ```
- **改造 `updateAdminFlags` / `toggleAdminActive`**：先 `get` 出来改 spec 字段再 `update`
- **删除私有方法**：`getAdminsFromDatabase`、`createAdminVO`
- **新增私有方法**：`toVO(Admin)` / `toEntity(AdminVO)`（双向转换）
- **import**：`com.gameplatform.plugin.extension.ExtensionClient`、`com.gameplatform.plugin.l4d2.extension.Admin`、`com.gameplatform.plugin.l4d2.extension.spec.AdminSpec`、`com.gameplatform.plugin.extension.ListOptions`、`com.gameplatform.plugin.extension.SpecFilter`

**E3. 改造 `MonitorController.java`**

文件：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java`

- **新增字段**：`private final ExtensionClient extensionClient;`
- **改造 `queryHistoryFromDatabase`**：删除 `Random random = new Random();` 与 mock 生成逻辑，改为：
  ```java
  long startMs = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  long endMs = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  ListOptions opts = ListOptions.builder()
          .specFilter(SpecFilter.of("instanceId", SpecFilter.Op.EQ, instanceId))
          .specFilter(SpecFilter.of("timestamp", SpecFilter.Op.GTE, startMs))
          .specFilter(SpecFilter.of("timestamp", SpecFilter.Op.LTE, endMs))
          .orderBy("timestamp")
          .build();
  List<SystemMetric> metrics = extensionClient.list(SystemMetric.class, opts);
  return metrics.stream().map(this::toVO).collect(Collectors.toList());
  ```
- **改造 `getStatus`**：成功获取主机资源后，**写一条 SystemMetric 记录**到 extensionClient（采样落库）：
  ```java
  SystemMetric metric = new SystemMetric();
  metric.setName("instance-" + instanceId + "-metric-" + System.currentTimeMillis());
  SystemMetricSpec spec = new SystemMetricSpec();
  // ... 填充 cpuPercent/memUsed 等 ...
  metric.setSpec(spec);
  metric.setStatus("ACTIVE");
  extensionClient.create(metric);
  ```
- **新增私有方法**：`toVO(SystemMetric)` 转 `MonitorHistoryVO`
- **删除 import**：`java.util.Random`
- **新增 import**：`ExtensionClient`、`SystemMetric`、`SystemMetricSpec`、`ListOptions`、`SpecFilter`

**E4. 验证 M7**

```bash
cd backend
mvn -pl plugin-l4d2 -am compile
```
启动主应用，加载 plugin-l4d2 后通过 API 验证：
```bash
curl "http://localhost:8080/api/plugin/l4d2/admins/list?instanceId=1"
curl -X POST "http://localhost:8080/api/plugin/l4d2/admins/add" -H "Content-Type: application/json" -d '{"instanceId":1,"steamId":"STEAM_0:0:123","adminFlags":"99:z"}'
curl "http://localhost:8080/api/plugin/l4d2/monitor/history?instanceId=1&timeRangeMinutes=30"
```

---

### 阶段 F：M8 — 测试收尾 + 文档更新

**F1. 补回归测试**

- `PluginLifecycleHookTest` — 现有测试需更新（删除了 `executeExtensionStart/StopHooks` 调用，新增 `executeInstance*Hooks`）。新增测试：mock 一个 `GameEnhancementExtension`，调用 `executeInstanceCreateHooks(1L, config)`，验证扩展点的 `onInstanceCreate` 被调用。
- `InstanceServiceImpl` 集成测试（可选）：mock `PluginLifecycleHook`，验证 createInstance/deleteInstance/startInstance/stopInstance 在正确时机调用了对应钩子。

**F2. 全量测试**

```bash
cd backend
mvn clean test
```
应全绿。

**F3. 更新 `docs/CODE_WIKI.md`**

第 5.2 节"插件数据访问"：删除 `IPluginDataAccess`/`PluginDataAccessImpl` 描述，改为 `ExtensionClient`/`ExtensionClientImpl` + 三层策略说明。
第 11 节"已知问题"：移除"插件存储沙箱未使用""生命周期钩子硬编码 0L"等已修复项。

**F4. 更新 `backend/AGENTS.md` 插件开发指南**

新增"Extension 模型开发"小节：
- 如何继承 `AbstractExtension<T>`、标注 `@ExtensionModel`
- 三层策略选择建议
- `ExtensionClient` 注入与使用示例
- 命名约定（`name` 字段全局唯一建议格式）

---

## 假设与决策

| # | 决策 | 依据 |
|---|------|------|
| 1 | **DDL 主键改为复合 `(name, group_name, kind)`** | 原设计 spec 与 `DdlTemplate` 用单列 `name` 主键，但 spec 又要求"所有查询强制 `WHERE group_name=? AND kind=?`"，说明身份是三元组。单列主键会导致 SHARED/PLUGIN_ISOLATED 下跨插件/跨 kind 同名资源误报冲突。这是落地前必修 bug。 |
| 2 | `PluginSchemaManager` 用 `@Component` 注入主容器，`ExtensionScanner`/`ExtensionRouter` 作为其依赖 | scanner/router 是无状态工具，但作为 Spring Bean 注入更易测试与未来扩展 |
| 3 | `ExtensionStoreInitializer` 用 `@PostConstruct` 而非 `ApplicationRunner` | `@PostConstruct` 在 Bean 初始化阶段即执行，确保任何插件加载前 extensions 表已存在 |
| 4 | `InstanceServiceImpl` 用 `@RequiredArgsConstructor` 直接加 `PluginLifecycleHook` 字段 | 探索确认 InstanceServiceImpl 的依赖链与 PluginLifecycleHook 无循环（PluginLifecycleHook 仅依赖 PluginInfoMapper + PluginManager(lazy)）。若启动报循环依赖，回退为 `@Lazy` setter 注入 |
| 5 | `purge` API 通过 `pluginService.getPluginByPluginId` 查状态而非 PF4J PluginManager | 减少耦合；DB 状态字段 `status=1` 表示启用。若与运行态不一致再加 PluginManager |
| 6 | L4D2 4 个 Extension 用 MODEL_ISOLATED（前 3 个）+ PLUGIN_ISOLATED（PluginConfig） | 与原 `l4d2_tables.sql` 4 张独立表的隔离粒度等价；PluginConfig 改用 PLUGIN_ISOLATED 因其跨实例共享 |
| 7 | Spec POJO 用 Lombok `@Data` | 项目其他 DTO/VO 普遍用 Lombok，保持一致 |
| 8 | 时间字段统一用 `Long`（毫秒时间戳）而非 `LocalDateTime` | 与 `AbstractExtension.metadata.creationTimestamp` 一致；序列化无时区歧义 |
| 9 | 不 DROP 旧 4 张 `l4d2_*` 表 | 新表名 `ext_plugin_l4d2_*` 与旧表不冲突；旧表残留无害。文档说明可手动清理 |

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| `ClassPathScanningCandidateComponentProvider` 在 PF4J 插件 ClassLoader 下扫不到类 | 已在 scanner 中 `setResourceLoader(new DefaultResourceLoader(pluginClassLoader))`；若 M5 启动时日志显示"0 个 Extension 模型"，退化为遍历插件 JAR entries |
| `InstanceServiceImpl` 注入 `PluginLifecycleHook` 启动报循环依赖 | 改为 `@Lazy` setter 注入 |
| `PluginVO.getStatus()` 语义与 PF4J 运行态不一致导致 purge 误判 | 在 purge API 中同时查 DB status 与 PluginManager.getPlugin(pluginId).getPluginState()，任一为运行态则拒绝 |
| 旧 `l4d2_*` 表数据丢失 | 开发期可接受；若有生产数据需迁移，单独写迁移脚本读旧表写新 Extension |
| `ExtensionClientImpl` 中 `toModelClass(extension)` 用 `extension.getClass()` 对 CGLIB 代理类失效 | 当前 Extension 子类无 `@Transactional`/AOP，不会被代理；若未来加代理，用 `ClassUtils.getUserClass()` |

---

## 验证步骤汇总

| 阶段 | 命令 | 预期 |
|------|------|------|
| A (M3 收尾) | `mvn -pl core -am compile` | 通过 |
| A (M3 测试) | `mvn -pl core -am test -Dtest=ExtensionRouterTest,DdlTemplateTest` | 绿色 |
| B (M4 测试) | `mvn -pl core -am test -Dtest=ExtensionClientImplTest` | 绿色 |
| C (M5 编译) | `mvn -pl core -am compile` | 通过 |
| C (M5 启动) | 启动主应用 | 日志含 `[ExtensionStore] 全局 extensions 表已就绪` |
| D (M6 编译) | `mvn -pl plugin -am compile` | 通过 |
| E (M7 编译) | `mvn -pl plugin-l4d2 -am compile` | 通过 |
| E (M7 运行) | 启动 + curl Admin/Monitor API | 增删查正常 |
| F (M8 全量) | `mvn clean test` | 全绿 |
| F (M8 全编译) | `mvn clean compile` | 全模块通过 |

---

## 提交建议

按里程碑分次提交，每次提交确保编译绿色：

1. `fix(extension): DdlTemplate 改复合主键 (name, group_name, kind)` — 阶段 A1
2. `feat(extension): 新增 PluginSchemaManager/ExtensionStoreInitializer，改造 PluginSpringContextFactory` — 阶段 A2-A5
3. `test(extension): 补 ExtensionRouter/DdlTemplate/ExtensionClientImpl 单元测试` — 阶段 A7 + B1
4. `feat(plugin): 集成实例生命周期钩子到 InstanceServiceImpl` — 阶段 C1-C2
5. `feat(plugin): 新增插件数据 purge API + 扩展异常处理器` — 阶段 C3-C4
6. `refactor(plugin): 删除 IPluginDataAccess 旧机制残余 (getDdlScript/getDeclaredTables/DDL 文件)` — 阶段 D1-D5
7. `refactor(plugin-l4d2): 迁移到 Extension 宽表模型，改造 Admin/Monitor Controller` — 阶段 E1-E3
8. `docs: 更新 CODE_WIKI 与 AGENTS 插件开发指南` — 阶段 F3-F4
