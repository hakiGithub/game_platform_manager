# 插件扩展存储重构 — 续接实现计划

> 本计划续接前次会话已批准的实现计划。前次会话完成了 M1-M5 框架代码（A1-A7）并通过 M3 单元测试，但被 B1（ExtensionClientImplTest 编译错误）阻塞。本计划从 B1 续接，完成剩余的测试修复、生命周期改造、旧机制清理、L4D2 插件迁移、回归测试与文档更新。

---

## 一、Summary（摘要）

将插件持久化机制从旧的 `IPluginDataAccess`/`PluginDataAccessImpl` + 各插件自写 DDL，彻底替换为 Halo 风格的统一 JSON 宽表 + 强类型 `AbstractExtension<T>` + `ExtensionClient` API。剩余工作包括：

1. **修复 B1**：`ExtensionClientImplTest` 编译错误（TestSpec 缺 3 参数构造器）
2. **C1-C4**：改造生命周期钩子、实例服务、Plugin purge API、全局异常处理
3. **D1-D5**：删除旧的 DDL 机制（接口方法、SQL 文件、properties 旧键）
4. **E1-E3**：L4D2 插件迁移到新存储（4 个 Extension 模型 + 改造 Admin/Monitor 控制器）
5. **F1-F4**：回归测试 + 全量测试 + 更新 CODE_WIKI.md 与 backend/AGENTS.md

---

## 二、Current State Analysis（当前状态分析）

### 已完成（A1-A7，前次会话）
- `backend/api/.../AbstractExtension.java`、`ExtensionMetadata.java`：强类型基类与元数据 ✅
- `backend/plugin/.../ExtensionModel.java`、`Strategy.java`、`ExtensionClient.java`、`ListOptions.java`、`SpecFilter.java`：SDK 接口与查询选项 ✅
- `backend/core/.../ExtensionRouter.java`、`DdlTemplate.java`（复合主键已修复）、`ExtensionScanner.java`、`ExtensionClientImpl.java`、`ExtensionRowMapper.java`、`SqliteQueryDialect.java`、`ExtensionQueryDialect.java`、`PluginSchemaManager.java`、`ExtensionStoreInitializer.java`、`ResolvedRoute.java`：核心实现 ✅
- `backend/plugin/.../exception/`：4 个异常类（Duplicate/NotFound/OptimisticLock/Store）✅
- `backend/core/.../PluginSpringContextFactory.java`：已重构，注册 ExtensionClient Bean，移除旧 IPluginDataAccess 引用 ✅
- `backend/core/src/test/.../ExtensionRouterTest.java`、`DdlTemplateTest.java`：M3 单元测试通过 ✅
- core 模块编译通过 ✅

### 当前阻塞（B1）
- `backend/core/src/test/.../ExtensionClientImplTest.java` 第 313、318 行调用 `new TestSpec("k", "from-a", 1L)` 三参数构造器，但 `TestSpec` 内部类（第 46-65 行）只定义了 `TestSpec()` 和 `TestSpec(String, String)` 两个构造器，导致编译失败。

### 待完成
- **C1**：`PluginLifecycleHook` 当前在 `onPluginStart/Stop` 中调用 `executeExtensionStart/StopHooks`（用 0L 假 instanceId），逻辑错误——实例钩子应由实例生命周期触发，而非插件启停触发。
- **C2**：`InstanceServiceImpl` 的 createInstance/deleteInstance/startInstance/stopInstance 未调用任何插件钩子。
- **C3**：`PluginController` 无 purge 数据 API；`PluginService`/`PluginServiceImpl` 无 purge 方法。
- **C4**：`GlobalExceptionHandler` 缺少 4 个 Extension 异常的 handler。
- **D1-D4**：`GameEnhancementExtension` 仍有 `getDdlScript()`/`getDeclaredTables()` default 方法（第 190-206 行）；`L4D2Extension` 仍有覆写（第 109-120 行）；`l4d2_tables.sql` 仍存在；`plugin.properties` 仍有 `plugin.tables`/`plugin.ddl` 键。
- **E1-E3**：L4D2 无 Extension 模型；`AdminController` 用 `ConcurrentHashMap` mock 数据；`MonitorController` 用 `new Random()` mock 历史数据。
- **F1-F4**：未做回归测试、全量测试、文档更新。

---

## 三、Proposed Changes（变更清单）

### B1 — 修复 ExtensionClientImplTest 编译错误
**文件**：`backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionClientImplTest.java`
**What**：给 `TestSpec` 内部类（第 46-65 行）添加 3 参数构造器 `TestSpec(String key, String value, Long instanceId)`。
**Why**：第 313、318 行 `modelIsolated_differentTables` 测试调用 3 参数构造器，当前缺失导致编译失败。
**How**：在现有 2 参数构造器后添加：
```java
public TestSpec(String key, String value, Long instanceId) {
    this.key = key;
    this.value = value;
    this.instanceId = instanceId;
}
```
**验证**：`mvn -pl core -am test "-Dtest=ExtensionClientImplTest" -q`

---

### C1 — 改造 PluginLifecycleHook
**文件**：`backend/core/src/main/java/com/gameplatform/plugin/listener/PluginLifecycleHook.java`
**What**：
1. 删除 `executeExtensionStartHooks(String pluginId)` 方法（第 235-255 行）和 `executeExtensionStopHooks(String pluginId)` 方法（第 260-280 行）。
2. 从 `onPluginStart`（第 55-72 行）移除 `executeExtensionStartHooks(pluginId);` 调用（第 66 行）。
3. 从 `onPluginStop`（第 77-93 行）移除 `executeExtensionStopHooks(pluginId);` 调用（第 83 行）。
4. 新增 4 个实例生命周期钩子执行方法：
   - `executeInstanceCreateHooks(Long instanceId, String gameCode, Map<String, Object> config)`
   - `executeInstanceStartHooks(Long instanceId, String gameCode)`
   - `executeInstanceStopHooks(Long instanceId, String gameCode)`
   - `executeInstanceDeleteHooks(Long instanceId, String gameCode)`
   每个方法遍历 `pluginManager.getExtensions(GameEnhancementExtension.class)`（所有插件），筛选 `getGameCode().equals(gameCode)` 的扩展点，调用对应的 `onInstance*` 方法。

**Why**：当前 `executeExtensionStart/StopHooks` 用 0L 假 instanceId 触发所有插件的实例钩子，语义错误。实例钩子应由实例生命周期事件触发，且只通知 gameCode 匹配的插件。
**How**：新增方法模板（以 Create 为例）：
```java
public void executeInstanceCreateHooks(Long instanceId, String gameCode, Map<String, Object> config) {
    if (pluginManager == null) return;
    try {
        pluginManager.getExtensions(GameEnhancementExtension.class).stream()
            .filter(ext -> gameCode.equals(ext.getGameCode()))
            .forEach(ext -> {
                try {
                    ext.onInstanceCreate(instanceId, config);
                } catch (Exception e) {
                    log.error("实例创建钩子执行失败: plugin={}, instance={}",
                        ext.getGameCode(), instanceId, e);
                }
            });
    } catch (Exception e) {
        log.error("获取扩展点失败", e);
    }
}
```
注意：需 `import java.util.Map;`。

---

### C2 — 改造 InstanceServiceImpl 注入钩子
**文件**：`backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java`
**What**：
1. 新增字段 `private final PluginLifecycleHook pluginLifecycleHook;`（已有 `@RequiredArgsConstructor`，自动构造注入）。
2. `createInstance`（第 52-87 行）：在 `instanceMapper.insert(instance)` 后、`return convertToVO(instance)` 前，调用 `pluginLifecycleHook.executeInstanceCreateHooks(instance.getId(), game.getGameCode(), dto.getConfigInfo() != null ? dto.getConfigInfo() : Map.of())`。
3. `deleteInstance`（第 116-131 行）：在 `instanceMapper.deleteById(id)` 前，调用 `pluginLifecycleHook.executeInstanceDeleteHooks(id, instance.getGameCode())`。
4. `startInstance`（第 178-227 行）：在 `instanceMapper.updateRunStatus(id, 1)` 后（成功分支），调用 `pluginLifecycleHook.executeInstanceStartHooks(id, instance.getGameCode())`。
5. `stopInstance`（第 231-281 行）：在 `instanceMapper.updateRunStatus(id, 0)` 后（成功分支），调用 `pluginLifecycleHook.executeInstanceStopHooks(id, instance.getGameCode())`。

**Why**：实例生命周期事件需通知匹配的插件扩展点，让插件感知实例变化并做相应处理。
**How**：钩子调用放在状态更新成功后，失败分支不触发。需 `import com.gameplatform.plugin.listener.PluginLifecycleHook;`。

---

### C3 — 新增 purge API
**文件1**：`backend/core/src/main/java/com/gameplatform/service/PluginService.java`
**What**：新增方法签名 `void purgePluginData(String pluginId);`
**Why**：为控制器提供 purge 能力的服务层入口。

**文件2**：`backend/core/src/main/java/com/gameplatform/service/impl/PluginServiceImpl.java`
**What**：
1. 注入 `private final PluginSchemaManager pluginSchemaManager;` 和 `private final JdbcTemplate jdbcTemplate;`（已有 `@RequiredArgsConstructor`）。
2. 实现 `purgePluginData`：
   - 先 `jdbcTemplate.update("DELETE FROM extensions WHERE group_name=?", pluginId)` 清理 SHARED 表数据。
   - 再 `pluginSchemaManager.purge(pluginId)` DROP 专属表。
   - 记录日志。

**Why**：purge 需同时清理 SHARED 表（按 group_name 过滤）和专属表（DROP）。`PluginSchemaManager.purge` 只删专属表，不处理 SHARED 表。
**How**：
```java
@Override
@Transactional(rollbackFor = Exception.class)
public void purgePluginData(String pluginId) {
    log.info("清空插件数据: {}", pluginId);
    // 1. 清理 SHARED 表中该插件的数据
    jdbcTemplate.update("DELETE FROM extensions WHERE group_name=?", pluginId);
    // 2. DROP 专属表
    pluginSchemaManager.purge(pluginId);
    logService.log(getCurrentUser(), "PURGE", "PLUGIN",
        "清空插件数据: " + pluginId, "success", null, null);
}
```
需 `import com.gameplatform.plugin.extension.PluginSchemaManager;` 和 `import org.springframework.jdbc.core.JdbcTemplate;`。

**文件3**：`backend/core/src/main/java/com/gameplatform/controller/PluginController.java`
**What**：新增端点
```java
@Operation(summary = "清空插件数据", description = "删除插件的所有扩展资源数据（含专属表）")
@DeleteMapping("/{pluginId}/data")
@OperationLog(type = "PURGE", target = "PLUGIN", description = "清空插件数据")
public Result<Void> purgeData(@Parameter(description = "插件ID") @PathVariable String pluginId) {
    pluginService.purgePluginData(pluginId);
    return Result.success();
}
```
**Why**：提供 REST API 供前端/运维清空插件数据。
**注意**：路径用 `{pluginId}`（String）而非 `{id}`（Long），与其他端点的 `{id}` 不冲突。

---

### C4 — GlobalExceptionHandler 新增 4 个异常 handler
**文件**：`backend/core/src/main/java/com/gameplatform/common/exception/GlobalExceptionHandler.java`
**What**：新增 4 个 `@ExceptionHandler` 方法：
- `DuplicateExtensionException` → `Result.fail(ResultCode.CONFLICT.getCode(), e.getMessage())`，HTTP 409
- `ExtensionNotFoundException` → `Result.fail(ResultCode.NOT_FOUND.getCode(), e.getMessage())`，HTTP 404
- `OptimisticLockException` → `Result.fail(ResultCode.CONFLICT.getCode(), e.getMessage())`，HTTP 409
- `ExtensionStoreException`（基类，兜底）→ `Result.fail(ResultCode.INTERNAL_SERVER_ERROR.getCode(), e.getMessage())`，HTTP 500

**Why**：ExtensionClient 抛出的异常需被全局处理器捕获并转为统一响应格式，否则前端收到 500 默认错误。
**How**：注意子类异常必须先于基类声明（Spring 按声明顺序匹配），所以顺序为 Duplicate → NotFound → OptimisticLock → Store（基类）。需 `import com.gameplatform.plugin.extension.exception.*;`。`ResultCode.CONFLICT`（409）已存在。

---

### D1 — 删除 GameEnhancementExtension 的旧 DDL 方法
**文件**：`backend/plugin/src/main/java/com/gameplatform/plugin/extension/GameEnhancementExtension.java`
**What**：删除第 190-206 行的 `getDdlScript()` 和 `getDeclaredTables()` default 方法，及其上方的注释块（第 190-206 行）。
**Why**：新机制由 `@ExtensionModel` 注解 + `PluginSchemaManager` 自动建表，旧 DDL 脚本声明机制已废弃。
**How**：删除第 190-206 行，保留 `getBasePackage()` 和 `getDependencies()` 之间的注释分隔符可调整。

---

### D2 — 删除 L4D2Extension 的旧 DDL 覆写
**文件**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java`
**What**：删除第 109-120 行的 `getDdlScript()` 和 `getDeclaredTables()` 覆写方法。
**Why**：D1 已删除接口中的 default 方法，覆写无意义。
**How**：删除第 108-120 行（含 `@Override`），并移除不再使用的 `import java.util.Arrays;` 和 `import java.util.List;`（若 List 在别处未用）。

---

### D3 — 删除 l4d2_tables.sql
**文件**：`backend/plugin-l4d2/src/main/resources/ddl/l4d2_tables.sql`
**What**：删除此文件。
**Why**：新机制由 `DdlTemplate` 自动生成宽表 DDL，无需插件提供 SQL 脚本。
**How**：用 DeleteFile 工具删除。同时删除 `target/classes/ddl/l4d2_tables.sql`（编译产物，下次 mvn clean 自动清理，可忽略）。

---

### D4 — 清理 plugin.properties 旧键
**文件**：`backend/plugin-l4d2/src/main/resources/plugin.properties`
**What**：删除第 8-9 行的 `plugin.tables=...` 和 `plugin.ddl=ddl/l4d2_tables.sql`。
**Why**：`PluginSpringContextFactory.extractCustomProperties` 已移除这两个标准键（前次会话已完成），保留它们会被当作自定义属性存入 customProperties，无意义。
**How**：删除这两行，保留前 7 行。

---

### D5 — 验证 PluginSpringContextFactory 已无旧引用
**文件**：`backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`
**What**：只读验证，不修改。确认已无 `IPluginDataAccess`、`PluginDataAccessImpl`、`getDdlScript()`、`getDeclaredTables()`、`executePluginDDL` 引用。
**Why**：前次会话已重构此文件，验证无遗漏。
**How**：Grep 搜索这些关键词，应无命中。编译通过即视为验证完成。

---

### E1 — 新建 L4D2 4 个 Extension 模型 + 4 个 Spec POJO
**目录**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/`（新建）
**What**：创建 8 个文件：

1. **`AdminResource.java`** + **`AdminSpec.java`**
   - `AdminResource extends AbstractExtension<AdminSpec>`，标注 `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)`
   - `AdminSpec` 字段：`Long instanceId; String steamId; String adminFlags; String remark; Boolean isActive;`

2. **`SystemMetricResource.java`** + **`SystemMetricSpec.java`**
   - `SystemMetricResource extends AbstractExtension<SystemMetricSpec>`，标注 `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)`
   - `SystemMetricSpec` 字段：`Long instanceId; Long timestamp; Double cpuPercent; Double cpuMaxCore; Double memUsed; Double memTotal; Double swapUsed; Double netUpSpeed; Double netDownSpeed; Double diskUsed; Double diskTotal;`

3. **`PluginConfigResource.java`** + **`PluginConfigSpec.java`**
   - `PluginConfigResource extends AbstractExtension<PluginConfigSpec>`，标注 `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)`
   - `PluginConfigSpec` 字段：`Long instanceId; String pluginName; String pluginStatus; String description; String fileList; String configFiles; String version; String author; String enableTime; Boolean isDeleted; String remark;`

4. **`DownloadTaskResource.java`** + **`DownloadTaskSpec.java`**
   - `DownloadTaskResource extends AbstractExtension<DownloadTaskSpec>`，标注 `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)`
   - `DownloadTaskSpec` 字段：`Long instanceId; String taskUrl; Integer taskStatus; Double progress; String filename; Long fileSize; Long downloadedSize; Double downloadSpeed; String errorMessage; String fileType; String targetPath; String startTime; String completeTime; Integer retryCount; Integer maxRetry; Boolean isDeleted; String remark;`

**Why**：L4D2 需要 4 个模型对应原 4 张表，用 MODEL_ISOLATED 策略使每个模型有独立物理表（`ext_plugin_l4d2_adminresource` 等），查询高效且 purge 干净。
**How**：所有 Spec POJO 用 `@Data`（Lombok）+ 无参构造 + 全参构造（或 builder）。Resource 类为空体（仅继承）。注意 `AbstractExtension` 在 `com.gameplatform.api.extension` 包，`ExtensionModel`/`Strategy` 在 `com.gameplatform.plugin.extension` 包（plugin 模块，plugin-l4d2 依赖它）。
**注意**：PluginConfigResource 和 DownloadTaskResource 本次仅建模（供未来控制器使用 + purge 完整清理），不立即改造对应控制器（无现有控制器直接用 DB）。

---

### E2 — 改造 AdminController 使用 ExtensionClient
**文件**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`
**What**：
1. 移除字段 `private final Map<Long, List<AdminVO>> adminCache` 和 `private final AtomicLong idGenerator`。
2. 新增字段 `private final ExtensionClient extensionClient;`（已有 `@RequiredArgsConstructor`，自动注入子容器中的 Bean）。
3. `getAdminList`：用 `extensionClient.list(AdminResource.class, ListOptions.builder().specFilter("$.instanceId", "=", instanceId).build())`，转为 `List<AdminVO>`。
4. `addAdmin`：创建 `AdminResource`，`name` = `instanceId + "-" + steamId`，`spec` 填 DTO 字段，`extensionClient.create(resource)`，转 VO 返回。捕获 `DuplicateExtensionException` 返回"该 SteamID 已存在"。
5. `deleteAdmin`：`extensionClient.delete(AdminResource.class, instanceId + "-" + steamId)`。捕获 `ExtensionNotFoundException` 返回"管理员不存在"。
6. `updateAdminFlags`：`extensionClient.get` → 修改 spec.adminFlags → `extensionClient.update`。捕获 NotFound。
7. `toggleAdminActive`：`extensionClient.get` → 修改 spec.isActive → `extensionClient.update`。
8. 私有方法 `getAdminsFromDatabase` 改为从 ExtensionClient 查询；`createAdminVO` 改为 `toVO(AdminResource)` 转换器；`updateAdminsConfig` 保留（写文件逻辑不变）。

**Why**：替换 mock 缓存为真实持久化，数据跨重启保留。
**How**：name 用 `{instanceId}-{steamId}` 保证同实例内 steamId 唯一、跨实例不冲突。VO 转换保留原 AdminVO 字段（id 用 metadata.creationTimestamp 代替或留 null，因新模型无自增 id）。需 `import com.gameplatform.plugin.extension.ExtensionClient;`、`import com.gameplatform.plugin.extension.ListOptions;`、`import com.gameplatform.plugin.l4d2.extension.*;`。

---

### E3 — 改造 MonitorController 使用 ExtensionClient
**文件**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java`
**What**：
1. 新增字段 `private final ExtensionClient extensionClient;`。
2. `getStatus`：获取主机资源后，创建 `SystemMetricResource`（name = `instanceId + "-" + timestamp`，spec 填监控数据），`extensionClient.create(resource)` 持久化，然后返回 `MonitorStatusVO`（原转换逻辑保留）。
3. `getHistory`/`getRealtime`/`getCpuTrend`/`getMemoryTrend`/`getNetworkTrend`：用 `extensionClient.list(SystemMetricResource.class, ListOptions.builder().specFilter("$.instanceId", "=", instanceId).createdAfter(startTimestamp).orderBy("creation_timestamp").limit(10000).build())` 查询，转为 `List<MonitorHistoryVO>`。
4. 私有方法 `queryHistoryFromDatabase` 改为从 ExtensionClient 查询并转 VO；移除 `new Random()` mock 逻辑。

**Why**：替换 mock 随机数据为真实持久化历史查询。
**How**：`createdAfter` 接收毫秒时间戳，需把 `LocalDateTime` 转为 epochMilli。name 用 `{instanceId}-{timestamp}` 保证唯一。需 `import com.gameplatform.plugin.extension.ExtensionClient;`、`import com.gameplatform.plugin.extension.ListOptions;`、`import com.gameplatform.plugin.l4d2.extension.*;`。

---

### F1 — 补回归测试
**What**：运行已有的 3 个 M3/M4 测试（ExtensionRouterTest、DdlTemplateTest、ExtensionClientImplTest），确保通过。
**验证**：`mvn -pl core -am test "-Dtest=ExtensionRouterTest,DdlTemplateTest,ExtensionClientImplTest" -q`

---

### F2 — 全量测试
**What**：运行全量 `mvn test`，确保无回归。
**验证**：`cd backend && mvn test`（若 plugin-l4d2 无测试，主要验证 core + api + plugin 编译与测试）

---

### F3 — 更新 CODE_WIKI.md
**文件**：`docs/CODE_WIKI.md`
**What**：更新"5.3.8 插件框架宿主实现"和"5.4.1 后端"章节，描述新的 Extension 存储机制：
- 替换旧的 IPluginDataAccess/PluginDataAccessImpl 描述
- 新增 ExtensionClient / ExtensionRouter / PluginSchemaManager / DdlTemplate / 三层策略说明
- 更新 L4D2 插件结构（新增 extension/ 目录）

**Why**：文档需反映架构变更。

---

### F4 — 更新 backend/AGENTS.md
**文件**：`backend/AGENTS.md`
**What**：更新"插件开发"章节（第 503 行起）：
- 替换"数据库表名必须以 {gameCode}_ 为前缀声明"为"使用 @ExtensionModel 注解声明存储策略"
- 移除 getDdlScript/getDeclaredTables 的说明
- 新增 ExtensionClient 使用示例（create/get/list/update/delete）
- 新增 @ExtensionModel 注解说明（三种策略）

**Why**：开发指南需与新机制一致。

---

## 四、Assumptions & Decisions（假设与决策）

### 已确认决策（前次会话）
1. **三层存储策略**：SHARED（全局 `extensions`）/ PLUGIN_ISOLATED（`ext_{pluginId}`）/ MODEL_ISOLATED（`ext_{pluginId}_{kind}`）
2. **复合主键**：`(name, group_name, kind)` — 已在 DdlTemplate 修复
3. **框架自动注入身份过滤**：`ExtensionRouter.resolve` 在 SQL 构造时确定表名和 group/kind，不可绕过
4. **强类型**：`AbstractExtension<T>` + Jackson 序列化 spec
5. **方案 A**：纯 JdbcTemplate + ObjectMapper，无 ORM
6. **乐观锁**：version 列，update 时 WHERE version=?
7. **SQLite 内存过滤**：spec/label 过滤在 SqliteQueryDialect 中拉行后内存过滤
8. **无数据迁移**：旧表数据不迁移（插件数据可清空）

### 本计划新增决策
9. **L4D2 4 个模型均用 MODEL_ISOLATED**：每个模型独立物理表，查询高效，purge 干净。
10. **AdminResource/SystemMetricResource 的 name 格式**：`{instanceId}-{业务键}`（如 `{instanceId}-{steamId}`、`{instanceId}-{timestamp}`），保证同实例内唯一、跨实例不冲突。
11. **purge API 同时清理 SHARED + 专属表**：`DELETE FROM extensions WHERE group_name=?` + `PluginSchemaManager.purge`。
12. **实例钩子由 InstanceServiceImpl 触发**：`PluginLifecycleHook` 不再在插件启停时触发实例钩子，改由实例生命周期方法触发，且只通知 gameCode 匹配的插件。
13. **PluginConfigResource/DownloadTaskResource 仅建模**：本次不改造对应控制器（无现有控制器直接用 DB），建模供未来使用 + purge 完整清理。

### 假设
- core 模块依赖 plugin 模块（已验证：PluginSpringContextFactory 导入 plugin 的类），故 GlobalExceptionHandler 可导入 `com.gameplatform.plugin.extension.exception.*`。
- plugin-l4d2 依赖 plugin 模块（已有 L4D2Extension implements GameEnhancementExtension），故可导入 `ExtensionClient`/`ExtensionModel`/`Strategy`。
- ExtensionClient 已在 PluginSpringContextFactory 第 78-81 行注册为子容器 Bean，AdminController/MonitorController 可直接构造注入。

---

## 五、Verification Steps（验证步骤）

### 阶段验证
1. **B1 后**：`mvn -pl core -am test "-Dtest=ExtensionClientImplTest" -q` 通过
2. **C1-C4 后**：`mvn -pl core -am compile -q` 通过
3. **D1-D4 后**：`mvn -pl plugin -am compile -q` 和 `mvn -pl plugin-l4d2 -am compile -q` 通过
4. **E1 后**：`mvn -pl plugin-l4d2 -am compile -q` 通过（4 个模型 + 4 个 Spec 编译）
5. **E2-E3 后**：`mvn -pl plugin-l4d2 -am compile -q` 通过（控制器改造编译）
6. **F1 后**：3 个单元测试全部通过
7. **F2 后**：`cd backend && mvn test` 全量通过

### 最终验证
- `cd backend && mvn clean compile` 全模块编译通过
- `cd backend && mvn test` 全量测试通过
- Grep 确认无残留旧引用：`IPluginDataAccess`、`PluginDataAccessImpl`、`getDdlScript`、`getDeclaredTables`、`plugin.tables`、`plugin.ddl`、`l4d2_tables.sql`（除 target/ 编译产物）

---

## 六、执行顺序

B1 → C1 → C2 → C3 → C4 → D1 → D2 → D3 → D4 → D5（验证）→ E1 → E2 → E3 → F1 → F2 → F3 → F4

每阶段完成后用 TodoWrite 更新进度，编译/测试失败则立即修复再继续。
