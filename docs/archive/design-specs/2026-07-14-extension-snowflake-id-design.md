# 扩展资源雪花 ID 设计

> 日期：2026-07-14
> 状态：已批准（待实现）
> 范围：plugin / api / core / plugin-l4d2 模块

---

## 1. 背景与目标

### 1.1 现状

当前扩展资源存储（Halo 风格统一宽表）使用复合主键 `(name, group_name, kind)` 定位资源：

- `AbstractExtension<T>` 无数值型 id 字段，业务主键是字符串 `name`（插件自定，如 `1-76561198000000001`）
- `DdlTemplate` 宽表 DDL 的 `PRIMARY KEY (name, group_name, kind)`
- `ExtensionClientImpl` 所有 SQL 的 WHERE 条件都用 `(name, group_name, kind)` 三元组

### 1.2 问题

- 无全局唯一数值标识，后续业务处理（如外部引用、日志关联、API 路径）缺少稳定主键
- 复合键作为操作句柄冗长，不利于"按 id 处理"的通用模式

### 1.3 目标

为 `AbstractExtension` 新增雪花 ID（String 类型）作为 PRIMARY KEY，原有复合键降为 UNIQUE 约束；`ExtensionClient` 接口保留原 name 方法并新增 by-id 方法；ID 生成通过 Hutool 雪花算法，封装为可替换接口。

---

## 2. 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| ID 与复合主键关系 | **替换主键**：id 为新 PRIMARY KEY，`(name, group_name, kind)` 降为 UNIQUE | 后续处理使用 id 作主键 |
| ID 数据类型 | **String**（VARCHAR(20)） | 避免 JS 大整数精度问题，与 K8s uid 风格一致 |
| 接口签名策略 | **保留 name 方法 + 新增 id 方法** | 向后兼容，既有控制器无需改动 |
| name 字段去留 | **保留 + NOT NULL + UNIQUE** | 作人类可读业务标识，防止业务重复创建 |
| ID 生成封装 | **方案 B：接口 + 实现**（`ExtensionIdGenerator` + `SnowflakeIdGenerator`） | 可测试、可替换，符合项目现有风格 |
| 改造范围 | **框架 + 控制器 + 测试** | 一次到位 |

---

## 3. 数据模型与 DDL 变化

### 3.1 AbstractExtension 新增字段

```java
public abstract class AbstractExtension<T> {
    /** 资源全局唯一标识（雪花ID），框架生成，PRIMARY KEY */
    private String id;

    /** 业务标识（插件自定，如 instanceId-steamId），同表内 UNIQUE */
    private String name;

    // 其余字段不变：groupName / kind / version / metadata / spec / status
}
```

- `id`：String，`create` 时框架用雪花算法生成并回填，插件只读
- `name`：保留，插件 `create` 时仍需设置，作人类可读业务标识

### 3.2 DDL 模板变化（DdlTemplate.generate）

```sql
-- 新 DDL
CREATE TABLE IF NOT EXISTS {表名} (
    id VARCHAR(20) NOT NULL,
    name VARCHAR(64) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    kind VARCHAR(128) NOT NULL,
    version INT DEFAULT 1,
    metadata TEXT NOT NULL,
    spec TEXT NOT NULL,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    creation_timestamp BIGINT,
    update_timestamp BIGINT,
    PRIMARY KEY (id),
    UNIQUE (name, group_name, kind)
);
CREATE INDEX IF NOT EXISTS idx_{表名}_group_kind ON {表名}(group_name, kind);
CREATE INDEX IF NOT EXISTS idx_{表名}_status ON {表名}(status);
CREATE INDEX IF NOT EXISTS idx_{表名}_creation ON {表名}(creation_timestamp);
```

变化点：
- 新增 `id VARCHAR(20) NOT NULL` 列（首列）
- `PRIMARY KEY (name, group_name, kind)` → `PRIMARY KEY (id)`
- 新增 `UNIQUE (name, group_name, kind)` 约束
- 保留原有 3 个索引

---

## 4. ID 生成器（方案 B）

### 4.1 接口 ExtensionIdGenerator（plugin 模块）

```java
package com.gameplatform.plugin.extension;

/** 扩展资源 ID 生成器接口 */
public interface ExtensionIdGenerator {
    /** 生成全局唯一 String 类型 ID */
    String nextId();
}
```

### 4.2 默认实现 SnowflakeIdGenerator（core 模块）

```java
package com.gameplatform.plugin.extension;

import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator implements ExtensionIdGenerator {
    @Override
    public String nextId() {
        return IdUtil.getSnowflakeNextIdStr();
    }
}
```

- 使用 Hutool `IdUtil.getSnowflakeNextIdStr()`（返回 String，避免 long 精度问题）
- 单机部署用默认 workerId=0/datacenterId=0；未来多机可通过 `new Snowflake(workerId, datacenterId)` 配置化
- `@Component` 由主容器管理

### 4.3 注入路径

`PluginSpringContextFactory` 构造 `ExtensionClientImpl` 时从主容器获取 `ExtensionIdGenerator` 并注入：

```java
ExtensionIdGenerator idGenerator = mainContext.getBean(ExtensionIdGenerator.class);
ExtensionClientImpl extensionClient = new ExtensionClientImpl(
    jdbcTemplate, extensionRouter, pluginId,
    extensionQueryDialect, objectMapper, ownedTables, idGenerator);  // 新增参数
```

### 4.4 测试可控性

测试时可注入返回固定值的 mock：
```java
ExtensionIdGenerator mockGen = () -> "fixed-test-id";
```

---

## 5. ExtensionClient 接口与实现改造

### 5.1 接口签名（保留 + 新增）

```java
public interface ExtensionClient {
    // create：框架生成 id 回填（签名不变，内部变化）
    <T extends AbstractExtension<?>> void create(T extension);

    // update：改为用对象内 id + version 定位（主键定位）
    <T extends AbstractExtension<?>> void update(T extension);

    // === 保留原 name 方法（向后兼容） ===
    <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name);
    <T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status);
    <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name);

    // === 新增 id 方法 ===
    <T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id);
    <T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status);
    <T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id);

    // list/listAll/count/getManagedTables 不变
    <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts);
    <T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass);
    long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions opts);
    Set<String> getManagedTables();
}
```

### 5.2 ExtensionClientImpl SQL 改造

| 方法 | WHERE 条件 | 说明 |
|------|-----------|------|
| create | INSERT 含 `id` 列 | 框架生成 id 回填 |
| update | `id=? AND version=?` | 改用主键定位 |
| delete (name) | `name=? AND group_name=? AND kind=?` | 保留 |
| deleteById | `id=?` | 新增 |
| updateStatus (name) | `name=? AND group_name=? AND kind=?` | 保留 |
| updateStatusById | `id=?` | 新增 |
| get (name) | `name=? AND group_name=? AND kind=?` | 保留 |
| getById | `id=?` | 新增 |

### 5.3 create 方法变化

```java
public <T extends AbstractExtension<?>> void create(T extension) {
    ResolvedRoute route = router.resolve(toModelClass(extension), pluginId);
    long now = System.currentTimeMillis();

    extension.setId(idGenerator.nextId());  // 新增
    extension.setGroupName(route.group());
    extension.setKind(route.kind());
    extension.setVersion(1);
    // ... metadata/status 不变

    String sql = "INSERT INTO " + route.table()
        + " (id, name, group_name, kind, version, metadata, spec, status, creation_timestamp, update_timestamp)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    // 异常处理不变（DuplicateKeyException 兼容 PRIMARY KEY 和 UNIQUE 冲突）
}
```

### 5.4 ExtensionRowMapper 改造

新增读取 `id` 列：`extension.setId(rs.getString("id"))`。

### 5.5 向后兼容性

- `AdminController`/`MonitorController` 现有代码用 name 做 CRUD，无需改动即可编译
- `update(T)` 改用 id 定位：update 语义是"传入读到的对象"，读出的对象已有 id，兼容
- 新功能/新控制器可用 `getById`/`deleteById` 等 id 方法

---

## 6. 控制器与测试迁移

### 6.1 控制器迁移（AdminController / MonitorController）

**VO 新增 id 字段**：
```java
// AdminVO 新增
private String id;  // 从 AdminResource.getId() 透传
```

**新增 by-id 端点**（保留原 name 端点）：

| 控制器 | 原端点（保留） | 新增端点 |
|--------|--------------|---------|
| AdminController | `GET/DELETE /admins/{name}` | `GET/DELETE /admins/by-id/{id}` |
| MonitorController | `GET /monitor/history` | 不变（按 instanceId 查询） |

- `AdminController.toVO` 新增 `vo.setId(resource.getId())`
- 新增 `getAdminById`/`deleteAdminById` 方法调用 `extensionClient.getById`/`deleteById`
- MonitorController 历史查询按 spec.instanceId 过滤，无需 by-id 端点

### 6.2 测试迁移

| 测试类 | 改动内容 |
|--------|---------|
| `DdlTemplateTest` | 断言新 DDL 含 `id VARCHAR(20) NOT NULL` + `PRIMARY KEY (id)` + `UNIQUE (name, group_name, kind)` |
| `ExtensionClientImplTest` | create 断言 id 被回填且非空；新增 deleteById/getById/updateStatusById 测试；注入 mock `ExtensionIdGenerator` |
| `ExtensionRouterTest` | 适配新 DDL（建表 SQL 变化） |

### 6.3 不改动的部分

- `AdminController`/`MonitorController` 现有 name 端点逻辑保留
- `PluginSchemaManager` 建表逻辑不变（调用 `DdlTemplate.generate`）
- `ExtensionRouter` 路由逻辑不变

---

## 7. 数据迁移

本期无存量数据（插件存储刚重构，无生产数据），无需迁移脚本。旧表（如 `ext_plugin-l4d2_AdminResource`）会在插件重新加载时由 `PluginSchemaManager.createSchemas` 执行 `CREATE TABLE IF NOT EXISTS`：

- 若旧表已存在（无 id 列），`CREATE TABLE IF NOT EXISTS` 不会修改表结构，导致 id 列缺失
- **处理策略**：开发环境下删除旧表重新建表；`DdlTemplate.generate` 用 `IF NOT EXISTS` 保证幂等
- 若需兼容旧表，可在 `PluginSchemaManager` 增加 `ALTER TABLE ADD COLUMN id` 逻辑（本期不实现，记为后续改进点）

---

## 8. 涉及文件清单

### 新增文件
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionIdGenerator.java`
- `backend/core/src/main/java/com/gameplatform/plugin/extension/SnowflakeIdGenerator.java`

### 修改文件
- `backend/api/src/main/java/com/gameplatform/api/extension/AbstractExtension.java` — 新增 id 字段
- `backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionClient.java` — 新增 by-id 方法
- `backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionClientImpl.java` — SQL 改造 + 注入 idGenerator
- `backend/core/src/main/java/com/gameplatform/plugin/extension/DdlTemplate.java` — DDL 模板更新
- `backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionRowMapper.java` — 读取 id 列
- `backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java` — 注入 idGenerator
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java` — VO 透传 id + 新增 by-id 端点
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/vo/AdminVO.java` — 新增 id 字段
- `backend/core/src/test/java/com/gameplatform/plugin/extension/DdlTemplateTest.java` — 断言新 DDL
- `backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionClientImplTest.java` — id 回填 + by-id 测试
- `backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionRouterTest.java` — 适配新 DDL

### 文档更新（可选，后续）
- `docs/CODE_WIKI.md` — 5.2.4 扩展资源存储小节补充 id 字段
- `backend/AGENTS.md` — 宽表 DDL 补充 id 列

---

## 9. 验收标准

1. `mvn test` 全量通过（358+ 个测试，0 失败 0 错误）
2. `DdlTemplate.generate` 输出含 `id VARCHAR(20) NOT NULL` + `PRIMARY KEY (id)` + `UNIQUE (name, group_name, kind)`
3. `ExtensionClientImpl.create` 生成的资源对象 `id` 非空
4. `getById`/`deleteById`/`updateStatusById` 方法按 id 正确操作
5. 原 `get`/`delete`/`updateStatus`（by name）方法仍正常工作
6. `AdminController` 的 `by-id` 端点可正常获取/删除资源
