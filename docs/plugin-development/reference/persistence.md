# 扩展资源持久化（ExtensionClient + @ExtensionModel）

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

## 1. 声明资源模型

通过 `@ExtensionModel` 标注继承 `AbstractExtension<T>` 的资源类，框架自动建表：

```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)  // 可选 group="..." kind="..."
public class MyPlayerResource extends AbstractExtension<PlayerSpec> {}
```

| 策略 | 物理表名 | 隔离粒度 |
|---|---|---|
| `SHARED`（默认） | `extensions` | 逻辑隔离（group_name + kind 过滤） |
| `PLUGIN_ISOLATED` | `ext_{pluginId}` | 插件间物理隔离 |
| `MODEL_ISOLATED` | `ext_{pluginId}_{kind}` | 模型级物理隔离 |

`@ExtensionModel` 还支持 `group()`（空则用 pluginId）与 `kind()`（空则用类 simpleName）覆盖属性。

> 框架自动填充 `id`（雪花ID）、`groupName`（pluginId）、`kind`、`version`（乐观锁）、`metadata`（时间戳）。`name` 为业务标识，同类型内唯一。

**硬约束**：
- 扩展资源基类必须增加唯一 id，使用 Hutool 的雪花 id 生成方案
- 扩展资源 id 字段为 String 类型，作为 PRIMARY KEY
- 扩展资源 name 字段保留，设为 NOT NULL 且 UNIQUE，作为业务标识

## 2. ExtensionClient 全方法

`ExtensionClient` 由框架在插件子容器注册为单例，绑定 pluginId，所有方法自动带 `group_name`+`kind` 身份过滤：

| 方法 | 用途 | 抛出 |
|---|---|---|
| `create(T)` | 创建（自动填 group/kind/version=1） | `DuplicateExtensionException` |
| `update(T)` | 更新（乐观锁，需带 version） | `OptimisticLockException` / `ExtensionNotFoundException` |
| `get(Class<T>, name)` → `Optional<T>` | 按 name 查 | — |
| `getById(Class<T>, id)` → `Optional<T>` | 按雪花ID查 | — |
| `list(Class<T>, ListOptions)` → `List<T>` | 条件查询 | — |
| `listAll(Class<T>)` → `List<T>` | 当前类型全量 | — |
| `count(Class<?>, ListOptions)` → `long` | 计数 | — |
| `delete(Class<T>, name)` / `deleteById(Class<T>, id)` | 删除 | `ExtensionNotFoundException` |
| `updateStatus(Class<T>, name, status)` / `updateStatusById(...)` | 更新状态字段 | — |
| `getManagedTables()` → `Set<String>` | 当前插件物理表名（调试用） | — |

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/plugin/mygame/players")
public class PlayerController {
    private final ExtensionClient extensionClient;

    @PostMapping
    public Result<Void> add(@RequestBody PlayerSpec spec) {
        MyPlayerResource r = new MyPlayerResource();
        r.setName(spec.getInstanceId() + "-" + spec.getName());
        r.setSpec(spec);
        extensionClient.create(r);
        return Result.success();
    }

    @GetMapping
    public Result<List<PlayerSpec>> list(@RequestParam Long instanceId) {
        ListOptions opts = ListOptions.builder()
            .specFilter("$.instanceId", "=", instanceId)
            .orderBy("creation_timestamp").build();
        return Result.success(
            extensionClient.list(MyPlayerResource.class, opts).stream()
                .map(MyPlayerResource::getSpec).toList());
    }
}
```

## 3. ListOptions 查询选项

```java
ListOptions opts = ListOptions.builder()
    .status("ACTIVE")                                   // status 列过滤
    .label("instanceId", "55")                          // metadata.labels 过滤
    .specFilter("$.score", ">", 100)                    // spec JSON 路径过滤（参数化，防注入）
    .createdAfter(System.currentTimeMillis() - 86400000)
    .limit(20).offset(0)
    .orderBy("creation_timestamp")
    .build();
```

## 4. 安全约束

| 规则 | 说明 |
|---|---|
| 模型白名单 | 只能访问自身插件下已声明的 `@ExtensionModel` 资源类 |
| 身份隔离 | `ExtensionClient` 绑定 pluginId，SQL 自动注入 `group_name = pluginId` |
| SQL 注入防护 | `specFilter` 由框架构造参数化查询，禁止插件直接拼 SQL |
| 跨插件隔离 | 插件 A 无法通过任何方法访问插件 B 的数据 |
