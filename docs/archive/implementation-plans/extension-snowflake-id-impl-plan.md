# 扩展资源雪花 ID 实现计划

> 关联设计：`../design-specs/2026-07-14-extension-snowflake-id-design.md`（已归档）
> 执行顺序：S1 → S7，每步完成后立即验证编译/测试

---

## 摘要

为扩展资源基类 `AbstractExtension` 新增 String 类型雪花 ID 作为 PRIMARY KEY，原复合主键 `(name, group_name, kind)` 降为 UNIQUE 约束。`ExtensionClient` 接口保留原 name 方法并新增 by-id 方法。ID 生成通过 Hutool 雪花算法，封装为 `ExtensionIdGenerator` 接口 + `SnowflakeIdGenerator` 实现。

## 当前状态分析

- `AbstractExtension<T>`：无数值 id，业务主键为字符串 `name`，字段 name/groupName/kind/version/metadata/spec/status
- `DdlTemplate.generate`：DDL 含 `PRIMARY KEY (name, group_name, kind)`，无 id 列
- `ExtensionClient` 接口：`create`/`update`/`delete(name)`/`updateStatus(name)`/`get(name)`/`list`/`listAll`/`count`/`getManagedTables`
- `ExtensionClientImpl`：构造器 6 参数（jdbcTemplate, router, pluginId, queryDialect, objectMapper, ownedTables），所有 SQL WHERE 用 `(name, group_name, kind)`
- `ExtensionRowMapper`：mapRow 未读取 id 列
- `PluginSpringContextFactory` 第 78-80 行构造 `ExtensionClientImpl`
- `ExtensionRouterTest`：只测路由解析，不涉及 DDL，无需改动
- `DdlTemplateTest`：第 24 行断言 `PRIMARY KEY (name, group_name, kind)`
- `ExtensionClientImplTest`：第 103-106 行构造 client，`newResource` 辅助方法（第 338 行）未设 id
- Hutool 5.8.26 已是 core 和 plugin-l4d2 的依赖
- `AdminVO.id` 为 `Long` 类型，`AdminController.toVO` 用 `metadata.creationTimestamp` 代替 id

## 拟定变更

### S1：ID 生成器（接口 + 实现）

#### S1.1 新建 `ExtensionIdGenerator` 接口
- 文件：`backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionIdGenerator.java`（新建）
- 内容：单方法接口 `String nextId();`

#### S1.2 新建 `SnowflakeIdGenerator` 实现
- 文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/SnowflakeIdGenerator.java`（新建）
- 内容：`@Component`，调用 `cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr()`

#### S1.3 验证
```bash
cd backend && mvn -pl plugin,core -am compile -q
```

---

### S2：数据模型与 DDL

#### S2.1 `AbstractExtension` 新增 id 字段
- 文件：`backend/api/src/main/java/com/gameplatform/api/extension/AbstractExtension.java`
- 改动：
  1. 在 `name` 字段前新增 `private String id;` + getter/setter
  2. 更新类注释，补充 `id → id 列（雪花ID，PRIMARY KEY）`

#### S2.2 `DdlTemplate.generate` 更新 DDL
- 文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/DdlTemplate.java`
- 改动 `generate` 方法返回的 SQL：
  - 列定义首行加 `"id VARCHAR(20) NOT NULL, "`
  - `"PRIMARY KEY (name, group_name, kind)"` → `"PRIMARY KEY (id), UNIQUE (name, group_name, kind)"`

#### S2.3 验证
```bash
cd backend && mvn -pl api,core -am compile -q
```

---

### S3：ExtensionClient 接口与 RowMapper

#### S3.1 `ExtensionClient` 接口新增 by-id 方法
- 文件：`backend/plugin/src/main/java/com/gameplatform/plugin/extension/ExtensionClient.java`
- 在 `get` 方法后新增 3 个方法（保留原方法）：
  - `<T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id);`
  - `<T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status);`
  - `<T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id);`

#### S3.2 `ExtensionRowMapper` 读取 id 列
- 文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionRowMapper.java`
- 在 `mapRow` 方法 `setName` 前新增：`extension.setId(rs.getString("id"));`

#### S3.3 验证
```bash
cd backend && mvn -pl plugin,core -am compile -q
```

---

### S4：ExtensionClientImpl 实现

#### S4.1 构造器新增 `ExtensionIdGenerator` 参数
- 文件：`backend/core/src/main/java/com/gameplatform/plugin/extension/ExtensionClientImpl.java`
- 改动：
  1. 新增字段 `private final ExtensionIdGenerator idGenerator;`
  2. 构造器末尾新增参数 `ExtensionIdGenerator idGenerator`，赋值给字段

#### S4.2 `create` 方法改造
- 在 `setGroupName` 之前新增 `extension.setId(idGenerator.nextId());`
- INSERT SQL 列从 9 列改为 10 列：新增首列 `id`
- VALUES 对应新增 `?`，参数列表首位新增 `extension.getId()`

#### S4.3 `update` 方法改造
- WHERE 从 `name=? AND group_name=? AND kind=? AND version=?` 改为 `id=? AND version=?`
- 参数从 `(spec, metadata, status, now, name, group, kind, version)` 改为 `(spec, metadata, status, now, id, version)`

#### S4.4 新增 `deleteById` 方法
- SQL：`DELETE FROM {table} WHERE id=?`
- 参数：`(id)`
- affected=0 抛 `ExtensionNotFoundException`

#### S4.5 新增 `updateStatusById` 方法
- SQL：`UPDATE {table} SET status=?, update_timestamp=? WHERE id=?`
- 参数：`(status, now, id)`
- affected=0 抛 `ExtensionNotFoundException`
- 返回 `getById(modelClass, id)` 结果

#### S4.6 新增 `getById` 方法
- SQL：`SELECT * FROM {table} WHERE id=?`
- 参数：`(id)`
- 用 `ExtensionRowMapper` 映射

#### S4.7 `PluginSpringContextFactory` 注入 idGenerator
- 文件：`backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`
- 第 78-80 行构造 `ExtensionClientImpl` 处：
  1. 在构造前获取：`ExtensionIdGenerator idGenerator = mainContext.getBean(ExtensionIdGenerator.class);`
  2. 构造器调用末尾新增 `idGenerator` 参数

#### S4.8 验证
```bash
cd backend && mvn -pl plugin,core -am compile -q
```

---

### S5：测试改造

#### S5.1 `DdlTemplateTest` 适配新 DDL
- 文件：`backend/core/src/test/java/com/gameplatform/plugin/extension/DdlTemplateTest.java`
- 改动：
  1. 第 20 行 `generate_containsCompositePrimaryKey` 测试改名 `generate_containsIdPrimaryKey`，断言改为 `PRIMARY KEY (id)` + `UNIQUE (name, group_name, kind)`
  2. `generate_containsAllRequiredColumns` 测试新增 `assertTrue(sql.contains("id VARCHAR(20) NOT NULL"), "应包含 id 列");`

#### S5.2 `ExtensionClientImplTest` 适配
- 文件：`backend/core/src/test/java/com/gameplatform/plugin/extension/ExtensionClientImplTest.java`
- 改动：
  1. 第 103-106 行：构造 `ExtensionClientImpl` 时新增 `idGenerator` 参数。用 `() -> "id-" + System.nanoTime()` 作为测试 idGenerator（保证唯一）
  2. `createAndGet_roundTrip` 测试新增 `assertNotNull(got.getId(), "id 应被回填");`
  3. 新增测试 `getById_returnsResource`：create 后用 getById 查询，断言返回且 id 匹配
  4. 新增测试 `deleteById_removesResource`：create 后 deleteById，再 getById 断言 empty
  5. 新增测试 `updateStatusById_updatesStatus`：create 后 updateStatusById，断言 status 变化

#### S5.3 `ExtensionRouterTest` 无需改动
- 确认：该测试只测路由解析（表名/group/kind/sanitize），不涉及 DDL

#### S5.4 验证
```bash
cd backend && mvn -pl core -am test "-Dtest=DdlTemplateTest,ExtensionClientImplTest,ExtensionRouterTest" -q
```

---

### S6：控制器迁移（AdminController）

#### S6.1 `AdminVO` id 类型改 String
- 文件：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/vo/AdminVO.java`
- `private Long id;` → `private String id;`

#### S6.2 `AdminController.toVO` 透传 id
- 文件：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`
- 第 277 行 `vo.setId(resource.getMetadata().getCreationTimestamp());` → `vo.setId(resource.getId());`
- 保留 createTime/updateTime 逻辑（仍用 metadata 时间戳）

#### S6.3 `AdminController` 新增 by-id 端点
- 新增方法：
  - `@GetMapping("/by-id/{id}") getAdminById(@PathVariable String id)` — 调用 `extensionClient.getById`，返回 VO
  - `@DeleteMapping("/by-id/{id}") deleteAdminById(@PathVariable String id, @RequestParam Long instanceId)` — 调用 `extensionClient.deleteById`，删除后更新 admins.cfg

#### S6.4 验证编译
```bash
cd backend && mvn -pl plugin-l4d2 -am compile -q
```

---

### S7：全量测试与验收

#### S7.1 全量测试
```bash
cd backend && mvn test
```
- 预期：358+ 个测试全部通过，0 失败 0 错误

#### S7.2 验收清单
- [ ] `mvn test` 全量通过
- [ ] `DdlTemplate.generate` 含 `id VARCHAR(20) NOT NULL` + `PRIMARY KEY (id)` + `UNIQUE (name, group_name, kind)`
- [ ] `ExtensionClientImpl.create` 后对象 id 非空
- [ ] `getById`/`deleteById`/`updateStatusById` 按 id 操作
- [ ] 原 `get`/`delete`/`updateStatus`（by name）仍正常
- [ ] `AdminController` by-id 端点编译通过
- [ ] `AdminVO.id` 为 String 类型

---

## 假设与决策

1. **旧表处理**：开发环境若存在旧表（无 id 列），`CREATE TABLE IF NOT EXISTS` 不会加列。本期无生产数据，需手动 DROP 旧表重建。测试用内存库无此问题。
2. **update(T) 兼容性**：现有控制器都是先 `get` 再 `update`（读出的对象已有 id），改造后用 id+version 定位兼容。`update_nonExistent_throwsNotFound` 测试中 ghost 对象 id 为 null，WHERE id=null → affected=0 → 抛 ExtensionNotFoundException，兼容。
3. **AdminVO.id 类型变化**：从 Long 改 String 是破坏性变更，但当前无前端消费此字段，风险低。
4. **ExtensionRouterTest 不改动**：探索确认该测试不涉及 DDL 断言。
5. **测试 idGenerator**：用 `() -> "id-" + System.nanoTime()` 保证唯一且可预测非空，避免引入 Hutool 依赖到测试。

## 验证步骤

每步完成后运行对应 `mvn compile` 或 `mvn test` 命令（见各小节）。S7 全量测试为最终验收。
