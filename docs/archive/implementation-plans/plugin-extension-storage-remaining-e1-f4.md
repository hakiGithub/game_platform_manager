# 插件扩展存储重构 — 剩余实现计划 (E1-F4)

> 本计划续接前次会话。B1（测试修复）、C1-C4（生命周期/purge/异常）、D1-D5（旧 DDL 清理）均已**验证完成**。本计划仅覆盖剩余的 E1-F4 阶段。

---

## 一、Summary（摘要）

将 L4D2 插件的 AdminController 与 MonitorController 从 mock 数据迁移到新的 `ExtensionClient` 持久化机制，新建 4 个 Extension 模型，运行回归测试，并更新两份文档。

剩余工作：
1. **E1**：新建 L4D2 4 个 Extension 模型 + 4 个 Spec POJO（8 个文件）
2. **E2**：改造 AdminController 使用 ExtensionClient（替换 ConcurrentHashMap mock）
3. **E3**：改造 MonitorController 使用 ExtensionClient（替换 Random mock）
4. **F1**：运行 3 个已有单元测试
5. **F2**：全量 `mvn test`
6. **F3**：更新 `docs/CODE_WIKI.md`（5 处旧引用）
7. **F4**：更新 `backend/AGENTS.md`（插件开发章节 + 数据库表章节）

---

## 二、Current State Analysis（当前状态分析）

### 已验证完成（B1-D5）
- `ExtensionClientImpl.java`：已含 `DataAccessException` 分支识别 SQLite PRIMARY KEY 冲突 ✅
- `ExtensionClientImplTest.java`：14 个测试，含 `SingleConnectionDataSource` 修复 + 3 参数 TestSpec 构造器 ✅
- `ExtensionRouterTest.java`（8 测试）、`DdlTemplateTest.java`（6 测试）：已存在 ✅
- `PluginLifecycleHook.java`：已改为实例生命周期触发，含 4 个 `executeInstance*Hooks` 方法 ✅
- `InstanceServiceImpl.java`：4 处生命周期方法已注入钩子调用 ✅
- `PluginServiceImpl.java`：已实现 `purgePluginData`（DELETE SHARED + DROP 专属表）✅
- `PluginController.java`：已新增 `DELETE /{pluginId}/data` 端点 ✅
- `GlobalExceptionHandler.java`：已新增 4 个 Extension 异常 handler ✅
- `GameEnhancementExtension.java`：已删除 `getDdlScript()`/`getDeclaredTables()` default 方法 ✅
- `L4D2Extension.java`：已删除覆写，无 `Arrays`/`List` 残留 import ✅
- `l4d2_tables.sql`：已删除 ✅
- `plugin.properties`：仅剩 7 行标准键，无 `plugin.tables`/`plugin.ddl` ✅
- `PluginConstants.java`：已删除 `PROP_TABLES`/`PROP_DDL` 常量 ✅
- `PluginSpringContextFactory.java`：第 78-81 行注册 `ExtensionClient` 单例到子容器 ✅

### 关键机制确认
- `ExtensionClient` 在 `PluginSpringContextFactory` 第 78-81 行注册为子容器单例，AdminController/MonitorController 可直接构造注入
- `PluginSchemaManager.createSchemas` 扫描插件 basePackage 下的 `@ExtensionModel` 类，为非 SHARED 策略建专属表
- L4D2 basePackage = `com.gameplatform.plugin.l4d2`（plugin.properties 第 7 行），新建的 `extension/` 子包会被扫描
- `ListOptions.builder().specFilter("$.instanceId", "=", value)` 支持 spec JSON 路径过滤（SQLite 内存过滤）
- `ListOptions.builder().createdAfter(epochMilli)` 支持时间范围过滤
- `AbstractExtension<T>` 字段：name/groupName/kind/version/metadata/spec/status
- 复合主键 `(name, group_name, kind)` → 同实例内 name 唯一即可

### 待完成
- L4D2 无任何 Extension 模型文件
- `AdminController` 用 `ConcurrentHashMap<Long, List<AdminVO>> adminCache` + `AtomicLong idGenerator` mock，`getAdminsFromDatabase` 返回写死的示例数据（第 277-278 行）
- `MonitorController` 的 `queryHistoryFromDatabase` 用 `new Random()` 生成 mock 历史数据（第 229-250 行）
- `docs/CODE_WIKI.md` 有 5 处旧引用（第 231/240/435/436/457/851 行）
- `backend/AGENTS.md` 第 503-565 行插件模块结构 + 第 827-856 行插件数据库表章节需更新

---

## 三、Proposed Changes（变更清单）

### E1 — 新建 L4D2 4 个 Extension 模型 + 4 个 Spec POJO

**目录**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/`（新建）

创建 8 个文件：

#### 1. `AdminSpec.java`
```java
package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;
import java.io.Serializable;

@Data
public class AdminSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long instanceId;
    private String steamId;
    private String adminFlags;
    private String remark;
    private Boolean isActive;
}
```

#### 2. `AdminResource.java`
```java
package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class AdminResource extends AbstractExtension<AdminSpec> {
}
```

#### 3. `SystemMetricSpec.java`
字段对应 `MonitorHistoryVO` 的 11 个监控字段：
```java
@Data
public class SystemMetricSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long instanceId;
    private Long timestamp;
    private Double cpuPercent;
    private Double cpuMaxCore;
    private Double memUsed;
    private Double memTotal;
    private Double swapUsed;
    private Double netUpSpeed;
    private Double netDownSpeed;
    private Double diskUsed;
    private Double diskTotal;
}
```

#### 4. `SystemMetricResource.java`
```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class SystemMetricResource extends AbstractExtension<SystemMetricSpec> {
}
```

#### 5. `PluginConfigSpec.java`
字段对应原 `l4d2_plugin_config` 表 + `PluginListVO`：
```java
@Data
public class PluginConfigSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long instanceId;
    private String pluginName;
    private String pluginStatus;
    private String description;
    private String version;
    private String author;
    private String enableTime;
    private Boolean isDeleted;
    private String remark;
}
```

#### 6. `PluginConfigResource.java`
```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class PluginConfigResource extends AbstractExtension<PluginConfigSpec> {
}
```

#### 7. `DownloadTaskSpec.java`
字段对应原 `l4d2_download_task` 表：
```java
@Data
public class DownloadTaskSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long instanceId;
    private String taskUrl;
    private Integer taskStatus;
    private Double progress;
    private String filename;
    private Long fileSize;
    private Long downloadedSize;
    private Double downloadSpeed;
    private String errorMessage;
    private String fileType;
    private String targetPath;
    private String startTime;
    private String completeTime;
    private Integer retryCount;
    private Integer maxRetry;
    private Boolean isDeleted;
    private String remark;
}
```

#### 8. `DownloadTaskResource.java`
```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class DownloadTaskResource extends AbstractExtension<DownloadTaskSpec> {
}
```

**Why**：L4D2 需要 4 个模型对应原 4 张表，MODEL_ISOLATED 策略使每个模型有独立物理表（`ext_plugin_l4d2_adminresource` 等），查询高效且 purge 干净。

**注意**：`PluginConfigResource` 和 `DownloadTaskResource` 本次仅建模（供未来控制器使用 + purge 完整清理），不立即改造对应控制器（`PluginManageController` 保持现有 mock 不变）。

**验证**：`mvn -pl plugin-l4d2 -am compile -q` 通过

---

### E2 — 改造 AdminController 使用 ExtensionClient

**文件**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`

**What**：
1. 移除字段 `private final Map<Long, List<AdminVO>> adminCache` 和 `private final AtomicLong idGenerator`（第 45-46 行）
2. 新增字段 `private final ExtensionClient extensionClient;`（`@RequiredArgsConstructor` 自动注入子容器 Bean）
3. 移除 import `java.util.concurrent.ConcurrentHashMap`、`java.util.concurrent.atomic.AtomicLong`
4. 新增 import：
   - `com.gameplatform.plugin.extension.ExtensionClient`
   - `com.gameplatform.plugin.extension.ListOptions`
   - `com.gameplatform.plugin.extension.exception.DuplicateExtensionException`
   - `com.gameplatform.plugin.extension.exception.ExtensionNotFoundException`
   - `com.gameplatform.plugin.l4d2.extension.AdminResource`
   - `com.gameplatform.plugin.l4d2.extension.AdminSpec`
5. `getAdminList`（第 53-65 行）：用 `extensionClient.list(AdminResource.class, ListOptions.builder().specFilter("$.instanceId", "=", instanceId).build())`，转 `List<AdminVO>`
6. `addAdmin`（第 72-108 行）：
   - 移除内存去重检查（第 80-87 行）
   - 创建 `AdminResource`，name = `instanceId + "-" + steamId`
   - `AdminSpec` 填 DTO 字段，isActive = true
   - `extensionClient.create(resource)`
   - 捕获 `DuplicateExtensionException` 返回 `Result.fail("该 SteamID 已存在")`
   - 转 VO 返回
7. `deleteAdmin`（第 115-140 行）：`extensionClient.delete(AdminResource.class, instanceId + "-" + steamId)`，捕获 `ExtensionNotFoundException` 返回"管理员不存在"
8. `updateAdminFlags`（第 147-179 行）：`extensionClient.get` → 修改 spec.adminFlags → `extensionClient.update`，捕获 NotFound
9. `toggleAdminActive`（第 186-218 行）：`extensionClient.get` → 修改 spec.isActive → `extensionClient.update`，捕获 NotFound
10. `syncAdmins`（第 245-260 行）：从 ExtensionClient 查询后调 `updateAdminsConfig`
11. 私有方法改造：
    - `getAdminsFromDatabase(Long instanceId)` → 从 ExtensionClient 查询并转 VO
    - `createAdminVO` → 改为 `toVO(AdminResource)` 转换器
    - `updateAdminsConfig` 保留（写文件逻辑不变）

**name 格式**：`{instanceId}-{steamId}` 保证同实例内 steamId 唯一、跨实例不冲突

**VO 转换**：`AdminVO.id` 用 `metadata.creationTimestamp` 代替（新模型无自增 id），`createTime`/`updateTime` 用 `LocalDateTime.ofEpochSecond(metadata.creationTimestamp/1000, 0, ZoneOffset.UTC)` 转换或直接用 `LocalDateTime.now()`（简化）

**Why**：替换 mock 缓存为真实持久化，数据跨重启保留。

**验证**：`mvn -pl plugin-l4d2 -am compile -q` 通过

---

### E3 — 改造 MonitorController 使用 ExtensionClient

**文件**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java`

**What**：
1. 新增字段 `private final ExtensionClient extensionClient;`
2. 移除 import `java.util.Random`
3. 新增 import：
   - `com.gameplatform.plugin.extension.ExtensionClient`
   - `com.gameplatform.plugin.extension.ListOptions`
   - `com.gameplatform.plugin.l4d2.extension.SystemMetricResource`
   - `com.gameplatform.plugin.l4d2.extension.SystemMetricSpec`
   - `java.time.ZoneId`
   - `java.time.ZoneOffset`
4. `getStatus`（第 48-63 行）：获取主机资源后，创建 `SystemMetricResource`（name = `instanceId + "-" + timestamp`，spec 填监控数据），`extensionClient.create(resource)` 持久化，然后返回 `MonitorStatusVO`（原转换逻辑保留）
5. `getHistory`/`getRealtime`/`getCpuTrend`/`getMemoryTrend`/`getNetworkTrend`：调用改造后的 `queryHistoryFromDatabase`
6. `queryHistoryFromDatabase`（第 223-253 行）：
   - 移除 `new Random()` mock 逻辑
   - 用 `extensionClient.list(SystemMetricResource.class, ListOptions.builder().specFilter("$.instanceId", "=", instanceId).createdAfter(startEpochMilli).limit(10000).orderBy("creation_timestamp").build())` 查询
   - 转 `List<MonitorHistoryVO>`
   - 注意：`createdAfter` 只过滤开始时间，结束时间在内存过滤（或不过滤，由 limit 控制）

**name 格式**：`{instanceId}-{timestamp}` 保证唯一

**时间转换**：`LocalDateTime` → `epochMilli` 用 `startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()`

**Why**：替换 mock 随机数据为真实持久化历史查询。`getStatus` 每次调用既返回当前状态又持久化一条历史记录。

**验证**：`mvn -pl plugin-l4d2 -am compile -q` 通过

---

### F1 — 运行 3 个已有单元测试

**What**：运行已有的 3 个测试，确保通过：
- `ExtensionRouterTest`（8 测试）
- `DdlTemplateTest`（6 测试）
- `ExtensionClientImplTest`（14 测试）

**验证**：
```bash
cd backend && mvn -pl core -am test "-Dtest=ExtensionRouterTest,DdlTemplateTest,ExtensionClientImplTest" -q
```

---

### F2 — 全量测试

**What**：运行全量测试，确保无回归

**验证**：
```bash
cd backend && mvn test
```
（plugin-l4d2 无测试，主要验证 core + api + plugin 编译与测试）

---

### F3 — 更新 CODE_WIKI.md

**文件**：`docs/CODE_WIKI.md`

**What**：修正 5 处旧引用：

1. **第 231 行**：`getBasePackage() / getDdlScript() / getDeclaredTables()` → `getBasePackage()`（删除 DDL 相关）
2. **第 238 行**：`PluginContext` 描述中的 `dataAccess/declaredTables` → 移除
3. **第 240 行**：`IPluginDataAccess`（接口）行 → 替换为 `ExtensionClient`（接口）描述：插件持久化入口，CRUD/list/count，自动注入身份过滤
4. **第 435 行**：`PluginSpringContextFactory` 描述中的"注册 `IPluginDataAccess` 单例" → "注册 `ExtensionClient` 单例（绑定 pluginId）"
5. **第 436 行**：`PluginDataAccessImpl` 行 → 替换为 `ExtensionClientImpl`：基于 JdbcTemplate + Jackson，经 `ExtensionRouter` 选表并强制注入 group_name/kind 过滤
6. **第 457 行**：`注册 IPluginDataAccess 单例（含表名白名单）` → `注册 ExtensionClient 单例（绑定 pluginId，含专属表清单）`
7. **第 851 行**：`表名白名单沙箱` 描述 → 替换为 `身份隔离`：`ExtensionRouter` 在 SQL 构造时确定表名和 group/kind，插件无法访问其他插件数据

新增内容：
- 在插件框架章节新增"扩展资源存储"小节，描述三层策略（SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED）、`@ExtensionModel` 注解、`AbstractExtension<T>` 强类型基类、乐观锁、`PluginSchemaManager` 自动建表
- 更新 L4D2 插件结构（新增 `extension/` 目录）

**Why**：文档需反映架构变更。

---

### F4 — 更新 backend/AGENTS.md

**文件**：`backend/AGENTS.md`

**What**：

1. **第 503-565 行"插件模块结构"**：
   - 在 `plugin-l4d2` 的目录树中新增 `extension/` 目录（含 4 个 Resource + 4 个 Spec）
   - 在 `plugin/` 模块树中新增 `extension/` 目录说明（含 `ExtensionModel`/`Strategy`/`ExtensionClient`/`AbstractExtension` 等）

2. **第 827-856 行"插件数据库表"章节**：
   - 删除原 `l4d2_system_metric`/`l4d2_plugin_config`/`l4d2_download_task`/`l4d2_admin` 表清单
   - 删除 `l4d2_system_metric` 的 DDL 示例
   - 替换为"扩展资源存储"说明：
     - 三层策略表格（SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED）
     - `@ExtensionModel` 注解使用示例
     - `ExtensionClient` CRUD 示例代码（create/get/list/update/delete）
     - 命名规范：name 在同表内唯一，group_name=pluginId，kind=类名
     - 表名规则：`extensions` / `ext_{pluginId}` / `ext_{pluginId}_{kind}`

3. **插件开发规范补充**（第 503 行附近）：
   - 新增"持久化数据"小节：使用 `@ExtensionModel` 注解声明存储策略，通过 `ExtensionClient` 访问

**Why**：开发指南需与新机制一致。

---

## 四、Assumptions & Decisions（假设与决策）

### 已确认决策（继承自前次会话）
1. L4D2 4 个模型均用 `MODEL_ISOLATED`：每个模型独立物理表
2. `AdminResource` 的 name = `{instanceId}-{steamId}`；`SystemMetricResource` 的 name = `{instanceId}-{timestamp}`
3. `PluginConfigResource`/`DownloadTaskResource` 仅建模，不改造 `PluginManageController`（保持现有 mock）
4. `getStatus` 每次调用既返回当前状态又持久化一条历史记录
5. `AdminVO.id` 用 `metadata.creationTimestamp` 代替（新模型无自增 id）

### 假设
- `ExtensionClient` 已在 `PluginSpringContextFactory` 注册为子容器单例（已验证第 78-81 行）
- L4D2 basePackage `com.gameplatform.plugin.l4d2` 会扫描到新的 `extension/` 子包（已验证 plugin.properties 第 7 行）
- `PluginSchemaManager.createSchemas` 会在插件加载时为 MODEL_ISOLATED 模型建专属表（已验证 PluginSpringContextFactory 第 68-69 行调用）
- core 模块依赖 plugin 模块，GlobalExceptionHandler 可导入异常类（前次会话已验证）
- plugin-l4d2 依赖 plugin 模块，可导入 `ExtensionClient`/`ExtensionModel`/`Strategy`（已验证 L4D2Extension implements GameEnhancementExtension）

---

## 五、Verification Steps（验证步骤）

### 阶段验证
1. **E1 后**：`cd backend && mvn -pl plugin-l4d2 -am compile -q` 通过（8 个新文件编译）
2. **E2 后**：`cd backend && mvn -pl plugin-l4d2 -am compile -q` 通过（AdminController 改造编译）
3. **E3 后**：`cd backend && mvn -pl plugin-l4d2 -am compile -q` 通过（MonitorController 改造编译）
4. **F1 后**：3 个单元测试全部通过（共 28 个测试）
5. **F2 后**：`cd backend && mvn test` 全量通过

### 最终验证
- `cd backend && mvn clean compile` 全模块编译通过
- `cd backend && mvn test` 全量测试通过
- Grep 确认无残留旧引用：
  - `IPluginDataAccess`、`PluginDataAccessImpl`、`getDdlScript`、`getDeclaredTables`、`plugin.tables`、`plugin.ddl`、`l4d2_tables.sql`（除 target/ 编译产物）
  - `AdminController` 中无 `adminCache`、`idGenerator`、`ConcurrentHashMap`、`AtomicLong`
  - `MonitorController` 中无 `new Random`

---

## 六、执行顺序

E1 → E2 → E3 → F1 → F2 → F3 → F4

每阶段完成后用 TodoWrite 更新进度，编译/测试失败则立即修复再继续。

---

## 七、关键文件路径速查

### 新建（E1）
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/AdminResource.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/AdminSpec.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/SystemMetricResource.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/SystemMetricSpec.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigResource.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigSpec.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/DownloadTaskResource.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/extension/DownloadTaskSpec.java`

### 修改（E2-E3）
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java`

### 修改（F3-F4）
- `docs/CODE_WIKI.md`
- `backend/AGENTS.md`

### 已有测试（F1）
- `backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionRouterTest.java`
- `backend/core/src/test/java/com/gameplatform/plugin/extension/DdlTemplateTest.java`
- `backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionClientImplTest.java`

### 参考（不修改）
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionClient.java`（接口）
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionModel.java`（注解）
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/Strategy.java`（枚举）
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/ListOptions.java`（查询选项）
- `backend/api/src/main/java/com/gameplatform/api/extension/AbstractExtension.java`（基类）
- `backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionClientImpl.java`（实现）
- `backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`（子容器工厂，第 78-81 行注册 ExtensionClient）
