# 插件扩展存储（Extension 宽表）设计

> 主题：用 Halo 风格的统一宽表 + 三层路由策略，替换 Game Platform Manager 现有的插件数据访问层（`IPluginDataAccess` / 白名单沙箱 / `getDdlScript`）。
>
> 状态：设计已与用户分节确认通过，待实现。
>
> 日期：2026-07-14

---

## 0. 背景与问题

当前插件存储层存在严重问题（详见探索报告）：

1. `PluginDataAccessImpl` 沙箱**完全未被使用**，plugin-l4d2 退化为 `ConcurrentHashMap` 内存缓存 + `Random()` mock 数据。
2. `executeDDL()` **完全绕过白名单**，任意插件可 DROP 主表；白名单为空时反而"全放行"。
3. 无 `Extension` 基类 / `ExtensionClient` / `@ExtensionModel`，插件开发者只能手写 SQL 字符串。
4. 插件 Spring 子容器无 `MapperScan` / 事务管理，`@Transactional` 不生效。
5. `plugin_info` 表存在 schema 漂移，且无 schema 版本字段，无法迁移。
6. 生命周期钩子硬编码 `instanceId=0L`；`onInstanceCreate/Delete` 无人调用。
7. 插件 DDL 与基座表有物理外键耦合（`l4d2_*.instance_id` REFERENCES `game_instance(id)`）。
8. 卸载不清理物理表，插件表与主表混在同一 SQLite 文件，无隔离。

本设计用统一的 JSON 宽表 + 动态表名路由 + 框架强制的身份注入，一次性解决上述问题。

---

## 1. 关键决策（已确认）

| 决策点 | 选择 |
|--------|------|
| 旧机制去留 | **彻底替换**。删除 `IPluginDataAccess`/`PluginDataAccessImpl`/`getDdlScript()`/`getDeclaredTables()`；DROP plugin-l4d2 的 4 张遗留表并用新模型重建。`ExtensionClient` 成为插件唯一持久化入口。 |
| Schema 演进 | **仅乐观锁，无迁移**。`version` 列只做并发控制；插件升级改表结构时手动 DROP 重建（开发期可接受丢数据）。 |
| 策略层级 | **三层**：`SHARED`（全局 `extensions`）/ `PLUGIN_ISOLATED`（`ext_{pluginId}`）/ `MODEL_ISOLATED`（`ext_{pluginId}_{kind}`）。默认 SHARED。 |
| 身份与过滤 | **框架自动注入**。`group_name` 默认取 `pluginId`（可注解覆盖）；所有查询无条件强制 `WHERE group_name=? AND kind=?`，插件无法绕过。 |
| Extension 模型 | **强类型**。`AbstractExtension<T>` 泛型基类，`spec` 为 POJO，Jackson 序列化。 |
| 索引 | **不支持 DB 索引**（无 `@Indexed`）。三层策略只管物理隔离，不含"索引特权"。统一只建基础索引。 |
| 列表条件过滤 | **按数据库方言分策略**：SQLite 查询后内存过滤；其他库按特性决定（本期只实现 SQLite）。 |
| 实现技术栈 | **方案 A：纯 JdbcTemplate + Jackson**。路由解析器在源头确定表名，不事后拦截 SQL 字符串。 |

---

## 2. 整体架构与模块定位

### 2.1 代码落在哪些模块

复用现有 `api` / `plugin` / `core` 三层，不新增模块：

| 模块 | 新增/变更 |
|------|-----------|
| **api** | `AbstractExtension<T>`、`ExtensionMetadata`（纯 POJO，无 Spring 依赖，插件编译期可见） |
| **plugin**（SDK） | `@ExtensionModel`、`Strategy` 枚举、`ExtensionClient` 接口、`ListOptions`/`SpecFilter`、异常类 |
| **core** | `ExtensionClientImpl`、`ExtensionRouter`、`DdlTemplate`、`PluginSchemaManager`、`ExtensionScanner`、`ExtensionStoreInitializer`、`ExtensionQueryDialect` + `SqliteQueryDialect`。删除 `IPluginDataAccess`/`PluginDataAccessImpl`/`PluginDataAccessException`，从 `GameEnhancementExtension` 移除 `getDdlScript()`/`getDeclaredTables()` |
| **plugin-l4d2** | DROP 4 张遗留表，按新模型重建为 `Extension` 子类（迁移示范） |

### 2.2 删除清单

- `backend/plugin/.../context/IPluginDataAccess.java`
- `backend/core/.../plugin/context/PluginDataAccessImpl.java`、`PluginDataAccessException`
- `GameEnhancementExtension.getDdlScript()`、`getDeclaredTables()` 两个 default 方法
- `PluginSpringContextFactory.executePluginDDL()` 及其调用
- `backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql`
- `plugin.properties` 中的 `plugin.ddl` / `plugin.tables` 声明
- L4D2 的 4 张遗留物理表（`l4d2_system_metric` / `l4d2_plugin_config` / `l4d2_download_task` / `l4d2_admin`）

### 2.3 运行时拓扑

```
主应用 Spring 容器 (core)
  ├─ DataSource (SQLite 单文件)               ← 复用，不另建
  ├─ JdbcTemplate ──────────────────────┐
  ├─ 7 张基座表 (sys_user/.../backup_record)  ← 完全不动
  ├─ ExtensionClientImpl ────────────────┤
  ├─ ExtensionRouter                     │  动态选表 + 解析身份
  ├─ PluginSchemaManager                 │  启动建表，purge 删表
  ├─ ExtensionStoreInitializer           │  建全局 extensions 表
  └─ RequestMappingHandlerMapping        │
                                         │
  ┌──────────────────────────────────────┘
  │  每个插件独立子容器 (parent=主应用)
  │
  └── plugin-l4d2 子容器
        ├─ extensionClient: ExtensionClientImpl(绑定 pluginId="plugin-l4d2")
        ├─ controllers (注册到主 DispatcherServlet)
        └─ Extension 子类: Admin / SystemMetric / DownloadTask / PluginConfig
```

**关键**：`ExtensionClient` 实例在子容器创建时绑定 `pluginId`，所有 SQL 经 `ExtensionRouter` 选表并强制注入 `group_name`/`kind`，插件代码无法绕过、无需手动传身份。

---

## 3. 数据模型与注解

### 3.1 `AbstractExtension<T>`（api 模块）

```java
package com.gameplatform.api.extension;

public abstract class AbstractExtension<T> {
    private String name;                  // metadata.name，主键
    private String groupName;             // 框架填充 = pluginId（插件只读）
    private String kind;                  // 框架填充 = 类名（可注解覆盖）
    private Integer version;              // 乐观锁版本号，框架管理
    private ExtensionMetadata metadata;   // labels/annotations/时间戳 → metadata 列
    private T spec;                       // 业务数据 → spec 列
    private String status;                // 'ACTIVE' 等，高频过滤字段
    // getters/setters
}

public class ExtensionMetadata {
    private Map<String, String> labels;
    private Map<String, String> annotations;
    private Long creationTimestamp;       // BIGINT 毫秒
    private Long updateTimestamp;
}
```

- `name` 是业务主键（插件自定，同表内唯一）。
- `groupName`/`kind`/`version` 由框架管理（插件读得到，不应写）。
- `metadata`、`spec` 各自序列化为独立 TEXT 列。

### 3.2 `@ExtensionModel`（plugin SDK）

```java
package com.gameplatform.plugin.extension;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExtensionModel {
    Strategy strategy() default Strategy.SHARED;   // 默认全局共享
    String group() default "";                     // 空则用 pluginId
    String kind() default "";                      // 空则用 simpleClassName
}

public enum Strategy { SHARED, PLUGIN_ISOLATED, MODEL_ISOLATED }
```

**不提供 `@Indexed`**。三层策略只决定物理隔离粒度，不涉及索引特权。

### 3.3 插件写法

```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class Admin extends AbstractExtension<AdminSpec> { }

public class AdminSpec {
    private Long instanceId;
    private String steamId;
    private String name;
    private Integer level;
    // getters/setters
}
```

---

## 4. DDL 模板与三层路由

### 4.1 统一 DDL 模板（`DdlTemplate`）

所有宽表用同一模板，仅表名不同。SQLite 版：

```sql
CREATE TABLE IF NOT EXISTS {table_name} (
    name               VARCHAR(64) PRIMARY KEY,
    group_name         VARCHAR(128) NOT NULL,
    kind               VARCHAR(128) NOT NULL,
    version            INT DEFAULT 1,
    metadata           TEXT NOT NULL,
    spec               TEXT NOT NULL,
    status             VARCHAR(32) DEFAULT 'ACTIVE',
    creation_timestamp BIGINT,
    update_timestamp   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_{table_name}_group_kind ON {table_name}(group_name, kind);
CREATE INDEX IF NOT EXISTS idx_{table_name}_status     ON {table_name}(status);
CREATE INDEX IF NOT EXISTS idx_{table_name}_creation   ON {table_name}(creation_timestamp);
```

`DdlTemplate.generate(tableName)` 返回上述 SQL。多数据库支持只需切换模板（MySQL/PG 把 TEXT 换 JSON/JSONB），本期只实现 SQLite。

### 4.2 表名路由（`ExtensionRouter`）

> 命名说明：原提案叫 "Interceptor"，但因插件不再手写 SQL，`ExtensionClient` 是唯一入口，没有"原始 SQL"可供拦截后替换。这里是一个**路由解析器**——`ExtensionClient` 在拼参数化 SQL 前调用它得到表名，比字符串拦截更干净、不可绕过。

```java
public class ExtensionRouter {
    public ResolvedRoute resolve(Class<? extends AbstractExtension<?>> modelClass, String pluginId) {
        ExtensionModel meta = modelClass.getAnnotation(ExtensionModel.class);
        Strategy strategy = (meta != null) ? meta.strategy() : Strategy.SHARED;
        String group = (meta != null && !meta.group().isEmpty()) ? meta.group() : pluginId;
        String kind  = (meta != null && !meta.kind().isEmpty())  ? meta.kind()  : modelClass.getSimpleName();

        String table = switch (strategy) {
            case SHARED          -> "extensions";
            case PLUGIN_ISOLATED -> "ext_" + sanitize(pluginId);
            case MODEL_ISOLATED  -> "ext_" + sanitize(pluginId) + "_" + sanitizeLower(kind);
        };
        return new ResolvedRoute(table, group, kind, strategy);
    }
}
```

- `sanitize`：把 pluginId/kind 中非 `[a-z0-9_]` 的字符（如连字符 `-`）替换为下划线 `_`，并转小写。例如 pluginId `plugin-l4d2`、kind `Admin` → 表名 `ext_plugin_l4d2_admin`。既防 SQL 注入/非法表名，又保留可读性。`group_name` 列存原始 pluginId（不做 sanitize），仅表名做转换。
- `ResolvedRoute` 携带 `table` / `group` / `kind` / `strategy`。

### 4.3 三层策略表名与隔离

| 策略 | 表名 | 行内可见性 | 隔离粒度 |
|------|------|-----------|---------|
| SHARED（默认） | `extensions` | 全部插件全部模型混居 | 仅靠 `group_name+kind` 过滤 |
| PLUGIN_ISOLATED | `ext_{pluginId}` | 该插件所有模型混居 | 插件间物理隔离 |
| MODEL_ISOLATED | `ext_{pluginId}_{kind}` | 仅该插件该模型 | 插件间+模型间物理隔离，DROP 粒度最细 |

### 4.4 身份强制注入

无论哪种策略，`ExtensionClient` 的所有查询都无条件追加：

```sql
WHERE group_name = ? AND kind = ?
```

参数由 `ResolvedRoute` 提供（`group` = pluginId 或注解覆盖值，`kind` = 类名或注解覆盖值）。插件代码无法去掉该过滤（`ExtensionClient` 不暴露任何"裸 SQL"方法）。即便 MODEL_ISOLATED 表里只有一种身份，也照常过滤——保持代码路径统一 + 纵深防御。

---

## 5. ExtensionClient API

### 5.1 接口（plugin SDK）

```java
public interface ExtensionClient {
    /* 写 */
    <T extends AbstractExtension<?>> void create(T extension);
    <T extends AbstractExtension<?>> void update(T extension);            // 乐观锁
    <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name);
    <T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status);

    /* 读 */
    <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name);
    <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts);
    <T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass);
    long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions opts);

    /* 元信息 */
    Set<String> getManagedTables();   // 当前插件拥有的物理表名（运维/调试用）
}
```

不提供 `watch`/流式订阅——本项目无响应式需求，YAGNI。

### 5.2 `ListOptions` / `SpecFilter`

```java
public class ListOptions {
    private String status;                         // status 列过滤
    private Map<String, String> labelSelector;     // metadata.labels.x = y
    private List<SpecFilter> specFilters;
    private Long createdAfter;                     // creation_timestamp >
    private int limit = 100;
    private int offset = 0;
    private String orderBy = "creation_timestamp";
    // builder
}

public class SpecFilter {
    private String path;     // "$.userId"
    private String op;       // =  !=  >  <  >=  <=  like  in
    private Object value;
}
```

### 5.3 方言分策略的条件过滤（`ExtensionQueryDialect`）

```java
public interface ExtensionQueryDialect {
    /** 返回最终结果列表（已反序列化） */
    <T extends AbstractExtension<?>> List<T> list(
        ResolvedRoute route, Class<T> modelClass, ListOptions opts, ObjectMapper mapper);
}

/** SQLite 方言：按 group_name+kind+status 拉行，spec 条件在反序列化后内存过滤 */
public class SqliteQueryDialect implements ExtensionQueryDialect { ... }
```

- **SQLite**：`SELECT * FROM {table} WHERE group_name=? AND kind=? [AND status=?] ORDER BY ... LIMIT/OFFSET`，拉回后在内存对反序列化后的 spec 对象应用 `specFilters`/`labelSelector`。避免 SQLite JSON 函数开销（且无索引）。
- **未来 PG/MySQL 方言**：把 `specFilters` 翻译为 `json_extract` / `->` / `@>` 等 WHERE 子句下推。本期不实现。

### 5.4 乐观锁流程

- **create**：框架置 `version = 1`、`creation_timestamp = now`、`group_name = pluginId`、`kind = 类名`。
- **update**：插件把读到的对象（含 `version`）改完传回，框架执行
  `UPDATE ... SET spec=?, metadata=?, version=version+1, update_timestamp=? WHERE name=? AND group_name=? AND kind=? AND version=?`
  受影响行数 = 0 → 抛 `OptimisticLockException`。框架**不隐式重试**（避免掩盖 bug），插件自行决定是否重读重试。
- **delete**：`DELETE WHERE name=? AND group_name=? AND kind=?`。

### 5.5 异常体系

| 异常 | 触发 | HTTP 映射 |
|------|------|-----------|
| `DuplicateExtensionException` | create 时 name 已存在 | 409 |
| `ExtensionNotFoundException` | get/update/delete 目标不存在 | 404 |
| `OptimisticLockException` | update 版本冲突 | 409 |
| `ExtensionStoreException` | 包装 SQLException / 序列化失败 / DDL 失败 | 500 |

均继承 `RuntimeException`，由 `GlobalExceptionHandler` 统一映射为 `Result` 响应。

### 5.6 使用示范

```java
@Service
public class AdminService {
    @Autowired private ExtensionClient client;

    public Admin create(String name, String steamId, int level) {
        Admin a = new Admin();
        a.setName(name);
        AdminSpec spec = new AdminSpec();
        spec.setSteamId(steamId); spec.setLevel(level);
        a.setSpec(spec);
        client.create(a);
        return a;
    }

    public List<Admin> listByLevel(int level) {
        return client.list(Admin.class, ListOptions.builder()
            .specFilter("$.level", "=", level).build());
    }
}
```

---

## 6. 生命周期与 Schema 管理

### 6.1 建表/删表时机

| 事件 | 动作 | 执行者 |
|------|------|--------|
| 主应用启动 | 建全局 `extensions` 表 | `ExtensionStoreInitializer`（`@PostConstruct`） |
| 插件 STARTED（子容器 `refresh()` **之前**） | 扫描 `@ExtensionModel` 类，建 `ext_*` 表 | `PluginSchemaManager.createSchemas(pluginId, classLoader)` |
| 插件 STOPPED | **不删表**（保留数据以便重启） | — |
| 插件 DISABLED | **不删表** | — |
| 管理员"彻底卸载"（新 API） | DROP 该插件所有 `ext_*` 表 | `PluginSchemaManager.purge(pluginId)` |

**关键**：表在 Spring 子容器 `refresh()` 之前创建——插件 Bean 初始化时即可用 `ExtensionClient`。替换原 `PluginSpringContextFactory.executePluginDDL()` 的位置，但用结构化 SchemaManager 取代手写 DDL。

### 6.2 `PluginSchemaManager`

```java
public class PluginSchemaManager {
    /** 在 PluginSpringContextFactory.loadPluginSpringContext() 中、refresh 前调用 */
    public SchemaResult createSchemas(String pluginId, ClassLoader pluginCl) {
        Set<Class<? extends AbstractExtension<?>>> models =
            new ExtensionScanner(pluginCl).scan();              // 扫描 @ExtensionModel 类
        Set<String> ownedTables = new HashSet<>();
        for (Class<?> m : models) {
            ResolvedRoute r = router.resolve(m, pluginId);
            if (r.strategy() == Strategy.SHARED) continue;      // SHARED 用全局表，不建
            jdbcTemplate.execute(DdlTemplate.generate(r.table()));
            ownedTables.add(r.table());
        }
        ownership.register(pluginId, ownedTables);
        return new SchemaResult(ownedTables);
    }

    /** 管理员调用，DROP 该插件全部表 */
    public void purge(String pluginId) {
        for (String t : ownership.get(pluginId)) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + sanitize(t));
        }
        ownership.unregister(pluginId);
    }
}
```

`ExtensionScanner` 复用扩展点 `getBasePackage()` 限定的包路径，只扫到 `@ExtensionModel` 类，不扫框架类。

### 6.3 `PluginSpringContextFactory` 改造

原 `executePluginDDL()` 删除，替换为：

```java
// loadPluginSpringContext() 内
// 1. 解析扩展点、读 pluginId
// 2. 建表（新）
SchemaResult schema = pluginSchemaManager.createSchemas(pluginId, wrapper.getPluginClassLoader());
// 3. 创建子容器、注册 ExtensionClient Bean（绑定 pluginId + ownedTables）
ExtensionClient client = new ExtensionClientImpl(jdbcTemplate, router, pluginId, queryDialect, objectMapper);
childContext.getBeanFactory().registerSingleton("extensionClient", client);
// 4. scan + refresh
```

原注册 `pluginDataAccess` Bean 的代码删除。

### 6.4 顺手修两个相关 Bug

存储重设计天然暴露的两个生命周期问题一并修掉（否则插件在钩子里用 `ExtensionClient` 会出错）：

1. **`instanceId=0L` 硬编码**：`PluginLifecycleHook.executeExtensionStartHooks/StopHooks` 当前传 `0L`。改为从触发事件取真实 `instanceId`（实例启停入口 `InstanceService` 已知 instanceId，把它传入钩子调用链）。
2. **`onInstanceCreate`/`onInstanceDelete` 无人调用**：在 `InstanceService` 的 create/delete 流程里补上对已启动插件扩展点的调用，让插件能在实例创建时初始化 Extension 数据、删除时清理。

### 6.5 新增管理员 API

`PluginManageController` 增加：

```
DELETE /api/pf4j/plugins/{pluginId}/data   →  pluginSchemaManager.purge(pluginId)
```

用于彻底卸载插件时清理其物理表（前端二次确认）。

---

## 7. L4D2 迁移示范

### 7.1 四个模型的映射

| 旧表 | 新 Extension 类 | 策略 | 理由 |
|------|-----------------|------|------|
| `l4d2_admin` | `Admin extends AbstractExtension<AdminSpec>` | MODEL_ISOLATED | 按实例频繁查，独立表 |
| `l4d2_system_metric` | `SystemMetric extends AbstractExtension<SystemMetricSpec>` | MODEL_ISOLATED | 时序数据可能膨胀，独立隔离 |
| `l4d2_download_task` | `DownloadTask extends AbstractExtension<DownloadTaskSpec>` | MODEL_ISOLATED | 任务列表可能增长 |
| `l4d2_plugin_config` | `PluginConfig extends AbstractExtension<PluginConfigSpec>` | PLUGIN_ISOLATED | 单例轻量配置，与插件内其它轻量模型共享 `ext_plugin_l4d2` |

### 7.2 去除外键耦合

旧表用 `FOREIGN KEY (instance_id) REFERENCES game_instance(id)` 关联实例（删主表行会破坏插件表，是痛点）。新模型改为：

- `instanceId` 放进 `spec`（业务字段）或 `metadata.labels`（`labels.instanceId = "123"`）。
- 按实例查询用 `labelSelector` 或 `specFilter("$.instanceId","=",id)`。
- **无物理外键**——插件数据与基座表松耦合，删实例时由 `onInstanceDelete` 钩子通知插件自行清理其 Extension 数据。

### 7.3 Admin 迁移写法

```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class Admin extends AbstractExtension<AdminSpec> { }

public class AdminSpec {
    private Long instanceId;
    private String steamId;
    private String name;
    private Integer level;
    // getters/setters
}
```

`AdminController` 改造：删掉 `ConcurrentHashMap<Long, List<AdminVO>> adminCache` 和 `getAdminsFromDatabase()` TODO，改为：

```java
@GetMapping("/{instanceId}")
public Result<List<Admin>> list(@PathVariable Long instanceId) {
    return Result.success(client.list(Admin.class, ListOptions.builder()
        .specFilter("$.instanceId", "=", instanceId)
        .orderBy("creation_timestamp").build()));
}

@PostMapping
public Result<Admin> add(@RequestBody AdminDTO dto) {
    Admin a = new Admin();
    a.setName("admin-" + dto.getInstanceId() + "-" + dto.getSteamId());
    AdminSpec s = new AdminSpec();
    s.setInstanceId(dto.getInstanceId());
    s.setSteamId(dto.getSteamId());
    s.setName(dto.getName());
    s.setLevel(dto.getLevel());
    a.setSpec(s);
    client.create(a);
    return Result.success(a);
}
```

`MonitorController` 的 mock 随机数据同理替换为真实 `SystemMetric` 读写。

---

## 8. 测试策略

| 层级 | 测试内容 |
|------|---------|
| 单元 | `ExtensionRouter`：三种策略表名解析 + sanitize 防注入 + 默认 group/kind 推导 |
| 单元 | `DdlTemplate.generate()`：SQL 正确性、表名注入安全 |
| 单元 | `ExtensionScanner`：只扫到 `@ExtensionModel` 类，不扫框架类 |
| 单元 | `ExtensionClientImpl`（内存 SQLite）：CRUD、乐观锁冲突、强制 group_name 注入（**负向测试：插件 A 读不到插件 B 数据**）、spec 内存过滤（SQLite 方言） |
| 集成 | 完整链路：加载 plugin-l4d2 → 自动建表 → ExtensionClient 读写 → purge 删表 |
| 集成 | L4D2 `AdminController`：用真实 ExtensionClient 替换内存缓存 |
| 回归 | 主应用 7 张基座表读写不受影响；插件 STOP/重启数据保留 |

---

## 9. 范围与不在本次设计内

- **不做** schema 迁移/版本管理（升级改表手动 DROP 重建）。
- **不做** DB 级 JSON 索引（`@Indexed`）。
- **不做** `watch`/流式订阅。
- **不做** PG/MySQL 方言实现（接口预留，本期仅 SQLite）。
- **不动** 7 张基座表及其 Mapper/Service。
- **不引入** 给插件单独的 DataSource（复用主应用 JdbcTemplate）。

---

## 10. 风险与缓解

| 风险 | 缓解 |
|------|------|
| SQLite 内存过滤在数据量大时慢 | 本项目插件数据规模小；预留方言接口，未来 PG/MySQL 下推 |
| 插件间通过 SHARED 表共居，一插件膨胀拖慢他人 | 文档引导：数据量大的模型选 MODEL_ISOLATED；SHARED 仅用于轻量配置 |
| `purge` API 误删数据 | 前端二次确认 + 后端校验插件处于 STOPPED/DISABLED 才允许 purge |
| 强类型 spec 字段重命名导致反序列化失败 | 插件升级改 spec 结构属"改表"，按本设计需 DROP 重建（已明确接受丢数据） |
| 乐观锁不重试导致用户体验差 | 文档说明需插件自行重试；高频冲突场景少见 |

---

*设计基于源码分析与 6 轮分节确认，2026-07-14。*
