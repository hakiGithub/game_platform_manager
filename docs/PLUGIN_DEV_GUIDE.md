# GamePlatform 插件开发规范

> 版本: 2.1.0 | 更新日期: 2026-08-01

本文档定义了 GamePlatform 插件框架的开发规范和约束，所有插件开发者必须遵循。

---

## 1. 项目结构规范

### 1.1 模块命名

```
plugin-{gameCode}/
├── pom.xml
├── src/main/java/
│   └── com/gameplatform/plugin/{gameCode}/
│       ├── {GameCode}Plugin.java          ← PF4J Plugin 入口类
│       ├── {GameCode}Extension.java        ← 扩展点实现
│       ├── controller/                     ← REST 控制器
│       ├── service/                        ← 业务逻辑
│       ├── mapper/                         ← 数据访问（可选）
│       ├── config/                         ← 插件配置类
│       └── util/                           ← 工具类
├── src/main/resources/
│   ├── plugin.properties                   ← 插件配置文件（必须）
│   └── ui/                                 ← 前端资源目录
│       ├── index.html
│       ├── assets/
│       └── ...
└── src/test/java/                          ← 单元测试
```

### 1.2 命名约定

| 元素 | 规范 | 示例 |
|------|------|------|
| Maven artifactId | `plugin-{gameCode}` | `plugin-l4d2` |
| Java 包名 | `com.gameplatform.plugin.{gameCode}` | `com.gameplatform.plugin.l4d2` |
| Plugin 入口类 | `{GameCode}Plugin` | `L4D2Plugin` |
| Extension 实现类 | `{GameCode}Extension` | `L4D2Extension` |
| 扩展资源类 | `{GameCode}{Purpose}Resource` | `AdminResource` |
| 业务 Spec 类 | `{GameCode}{Purpose}Spec` | `AdminSpec` |
| API 路径前缀 | `/api/plugin/{gameCode}/` | `/api/plugin/l4d2/` |

---

## 2. plugin.properties 配置规范

每个插件 JAR 包根目录必须包含 `plugin.properties` 文件：

```properties
# === 必填项 ===
plugin.id=plugin-l4d2
plugin.class=com.gameplatform.plugin.l4d2.L4D2Plugin

# === 可选项（可由 Extension 实现替代） ===
plugin.gameCode=l4d2
plugin.basePackage=com.gameplatform.plugin.l4d2

# === 自定义属性（通过 PluginContext.getCustomProperties() 获取） ===
rcon.defaultPort=27015
rcon.timeout=5000
```

### 配置优先级

当 `plugin.properties` 和 `GameEnhancementExtension` 方法同时定义时：

1. `plugin.basePackage` > `extension.getBasePackage()`

---

## 3. Extension 实现规范

### 3.1 基本实现

```java
@Extension
public class MyGameExtension implements GameEnhancementExtension {

    @Override
    public String getGameCode() {
        return "mygame";  // 全局唯一，小写英文+连字符
    }

    @Override
    public String getGameName() {
        return "我的游戏";
    }

    @Override
    public String getVersion() {
        return "1.0.0";  // 语义化版本
    }

    @Override
    public String getDescription() {
        return "游戏描述信息";
    }
}
```

### 3.2 声明扩展资源模型

插件通过 `@ExtensionModel` 注解声明需要持久化的资源类，框架自动扫描并建表。

```java
import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class MyPlayerResource extends AbstractExtension<PlayerSpec> {
}
```

**存储策略说明**：

| 策略 | 物理表名 | 隔离粒度 | 适用场景 |
|------|---------|---------|---------|
| `SHARED` | `extensions` | 逻辑隔离（按 group_name + kind 过滤） | 数据量小、无高频写入 |
| `PLUGIN_ISOLATED` | `ext_{pluginId}` | 插件间物理隔离 | 插件内多模型混居 |
| `MODEL_ISOLATED` | `ext_{pluginId}_{kind}` | 模型级物理隔离 | 高频写入/大量数据 |

> **约束**：
> - 资源类必须继承 `AbstractExtension<T>`，`T` 为业务 Spec POJO。
> - 业务标识通过 `name` 字段设置，同类型资源内唯一。
> - 框架自动填充 `id`（雪花ID）、`groupName`（pluginId）、`kind`（类名）、`version`（乐观锁）和 `metadata`（时间戳）。
> - 插件只能访问自身 groupName 下的资源，框架在 SQL 层强制过滤。

### 3.3 声明配置字段

```java
@Override
public List<PluginConfigField> getConfigFields() {
    return List.of(
        PluginConfigField.builder()
            .key("rcon_port")
            .label("RCON 端口")
            .type(PluginConfigField.FieldType.NUMBER)
            .defaultValue("27015")
            .required(true)
            .description("游戏服务器的 RCON 端口号")
            .build(),
        PluginConfigField.builder()
            .key("rcon_password")
            .label("RCON 密码")
            .type(PluginConfigField.FieldType.PASSWORD)
            .required(true)
            .description("RCON 认证密码")
            .build(),
        PluginConfigField.builder()
            .key("game_mode")
            .label("游戏模式")
            .type(PluginConfigField.FieldType.SELECT)
            .options(List.of("coop", "versus", "survival"))
            .defaultValue("coop")
            .build()
    );
}
```

### 3.4 生命周期钩子

```java
@Override
public void onLoad(PluginContext context) {
    // 插件加载后初始化（Spring 子容器已就绪）
    log.info("插件 {} 已加载，版本: {}", context.getPluginId(), context.getVersion());
}

@Override
public void onUnload() {
    // 插件卸载前清理资源
    log.info("插件正在卸载，清理资源...");
}

@Override
public void onInstanceStart(Long instanceId) {
    // 游戏实例启动
    log.info("实例 {} 启动", instanceId);
}

@Override
public void onLoadError(PluginContext context, Throwable error) {
    // 加载失败处理
    log.error("插件加载失败", error);
}
```

### 3.5 声明插件依赖

```java
@Override
public List<String> getDependencies() {
    return List.of("common-lib");  // 依赖的 gameCode 列表
}
```

---

## 4. 扩展资源持久化规范

### 4.1 定义资源模型与 Spec

```java
// 业务 Spec：纯 POJO，存放业务字段
@Data
public class PlayerSpec implements Serializable {
    private Long instanceId;
    private String name;
    private Integer score;
}

// 资源类：继承 AbstractExtension<T> 并标注 @ExtensionModel
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class MyPlayerResource extends AbstractExtension<PlayerSpec> {
}
```

### 4.2 注入 ExtensionClient

`ExtensionClient` 由框架在创建插件 Spring 子容器时注册为单例，插件通过 `@Autowired` 注入：

```java
@RestController
@RequestMapping("/api/plugin/mygame/players")
@RequiredArgsConstructor
public class PlayerController {

    private final ExtensionClient extensionClient;

    // 创建资源
    @PostMapping
    public Result<Void> addPlayer(@RequestBody PlayerSpec spec) {
        MyPlayerResource resource = new MyPlayerResource();
        resource.setName(spec.getInstanceId() + "-" + spec.getName());
        resource.setSpec(spec);
        extensionClient.create(resource);
        return Result.success();
    }

    // 按 name 查询
    @GetMapping("/{name}")
    public Result<PlayerSpec> getPlayer(@PathVariable String name) {
        return extensionClient.get(MyPlayerResource.class, name)
            .map(r -> Result.success(r.getSpec()))
            .orElse(Result.fail("玩家不存在"));
    }

    // 列表查询（按 instanceId 过滤）
    @GetMapping
    public Result<List<PlayerSpec>> listPlayers(@RequestParam Long instanceId) {
        ListOptions opts = ListOptions.builder()
            .specFilter("$.instanceId", "=", instanceId)
            .orderBy("creation_timestamp")
            .build();
        List<MyPlayerResource> list = extensionClient.list(MyPlayerResource.class, opts);
        return Result.success(list.stream().map(MyPlayerResource::getSpec).toList());
    }

    // 更新（需传入读到的完整对象，含 version）
    @PutMapping("/{name}")
    public Result<Void> updateScore(@PathVariable String name, @RequestParam Integer score) {
        MyPlayerResource resource = extensionClient.get(MyPlayerResource.class, name)
            .orElseThrow(() -> new ExtensionNotFoundException("plugin-mygame", "玩家不存在"));
        resource.getSpec().setScore(score);
        extensionClient.update(resource);
        return Result.success();
    }

    // 删除
    @DeleteMapping("/{name}")
    public Result<Void> deletePlayer(@PathVariable String name) {
        extensionClient.delete(MyPlayerResource.class, name);
        return Result.success();
    }
}
```

### 4.3 查询选项

`ListOptions` 支持状态、标签、Spec JSON 路径、时间范围、分页与排序：

```java
ListOptions opts = ListOptions.builder()
    .status("ACTIVE")                                       // status 列过滤
    .label("instanceId", "55")                              // metadata.labels 过滤
    .specFilter("$.score", ">", 100)                        // spec.score > 100
    .specFilter("$.instanceId", "=", instanceId)            // spec.instanceId = ?
    .createdAfter(System.currentTimeMillis() - 86400000)    // 最近 24 小时
    .limit(20)
    .offset(0)
    .orderBy("creation_timestamp")
    .build();

List<MyPlayerResource> list = extensionClient.list(MyPlayerResource.class, opts);
long total = extensionClient.count(MyPlayerResource.class, opts);
```

### 4.4 安全约束

| 规则 | 说明 |
|------|------|
| 模型白名单 | 只能访问自身插件下已声明的 `@ExtensionModel` 资源类 |
| 身份隔离 | `ExtensionClient` 绑定 pluginId，SQL 自动注入 `group_name = pluginId` 过滤 |
| SQL 注入防护 | `specFilter` 由框架构造参数化查询，禁止插件直接拼 SQL |
| 跨插件隔离 | 插件 A 无法通过任何 `ExtensionClient` 方法访问插件 B 的数据 |

---

## 5. 控制器开发规范

### 5.1 路径规范

```java
@RestController
@RequestMapping("/api/plugin/mygame/rcon")  // 必须以 /api/plugin/{gameCode}/ 开头
public class RconController {
    // ...
}
```

> **路径前缀约束**：所有控制器路径必须以 `/api/plugin/{gameCode}/` 开头。框架在注册时自动去掉 `/api` 前缀（因为主应用 context-path 为 `/api`）。

### 5.2 路径冲突检测

框架在注册控制器时自动检测路径冲突。如果两个插件注册了相同的 URL 路径，会抛出 `PluginPathConflictException` 并阻止插件加载。

### 5.3 统一响应格式

建议所有 API 返回统一格式：

```java
public class ApiResponse<T> {
    private int code;        // 0=成功, 非0=失败
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

---

## 6. 前端资源规范

### 6.1 目录结构

```
src/main/resources/ui/
├── index.html              ← 入口文件（必须）
├── assets/
│   ├── icon.png            ← 插件图标
│   └── ...
├── css/
├── js/
└── manifest.json           ← 可选：前端清单文件
```

### 6.2 manifest.json（可选）

当存在 `ui/manifest.json` 时，框架优先使用文件中的清单而非从 Extension 构建：

```json
{
  "pluginId": "plugin-l4d2",
  "gameCode": "l4d2",
  "gameName": "求生之路2",
  "version": "1.0.0",
  "frontendEntry": "/api/pf4j/plugin/l4d2/ui/index.html",
  "frontend": {
    "entry": "/api/pf4j/plugin/l4d2/ui/index.html",
    "routes": [
      {"path": "/dashboard", "name": "dashboard", "component": "Dashboard"}
    ],
    "menus": [
      {"title": "仪表盘", "path": "/dashboard", "icon": "Monitor", "order": 1}
    ]
  },
  "api": {
    "basePath": "/api/plugin/l4d2",
    "endpoints": [
      {"path": "/rcon/status", "method": "GET", "description": "获取服务器状态"}
    ]
  }
}
```

### 6.3 Wujie 子应用集成

插件前端作为 Wujie 子应用加载，入口 URL 格式：

```
/api/pf4j/plugin/{gameCode}/ui/index.html
```

---

## 7. Plugin 入口类规范

```java
public class MyGamePlugin extends Plugin {

    @Override
    public void start() {
        log.info("MyGame 插件启动");
    }

    @Override
    public void stop() {
        log.info("MyGame 插件停止");
    }

    @Override
    public void delete() {
        log.info("MyGame 插件删除");
    }
}
```

> Plugin 类是 PF4J 的生命周期入口，实际的业务逻辑应放在 Extension 实现中。

---

## 8. pom.xml 规范

```xml
<project>
    <parent>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-parent</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>plugin-mygame</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- 插件 API 模块（provided scope） -->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-plugin</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>
        <!-- Spring 相关依赖全部使用 provided scope -->
    </dependencies>

    <build>
        <plugins>
            <!-- 打包插件 JAR，排除 provided 依赖 -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <classifier>standalone</classifier>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 关键约束

| 规则 | 说明 |
|------|------|
| 依赖 scope | 所有框架级依赖必须为 `provided`，避免打包到插件 JAR 中 |
| 插件 JAR | 最终产物为单个 JAR 文件，放置到主应用 `plugins/` 目录 |
| Spring Boot repackage | 使用 `standalone` classifier 区分原始 JAR 和重打包 JAR |

---

## 9. 异常处理规范

### 9.1 使用框架异常

```java
// 数据访问异常
throw new PluginDataAccessException(pluginId, "无权访问表: " + tableName);

// 配置异常
throw new PluginConfigException(pluginId, "缺少必填配置: rcon_port");

// 加载异常
throw new PluginLoadException(pluginId, "DDL 执行失败", cause);
```

### 9.2 控制器异常处理

建议在插件控制器中使用 `@ExceptionHandler`：

```java
@RestControllerAdvice
public class PluginExceptionHandler {

    @ExceptionHandler(PluginDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(PluginDataAccessException e) {
        return ResponseEntity.status(403)
            .body(ApiResponse.error(403, e.getMessage()));
    }
}
```

---

## 10. 运行时上下文访问

插件代码可通过 `PluginContextHolder` 在任意位置获取自身上下文：

```java
// 通过插件ID获取上下文
Optional<PluginContext> ctx = PluginContextHolder.getContext("plugin-mygame");
ctx.ifPresent(context -> {
    log.info("插件ID: {}, 版本: {}", context.getPluginId(), context.getVersion());
});

// 通过游戏编码查找
PluginContextHolder.getByGameCode("mygame").ifPresent(context -> {
    log.info("插件版本: {}", context.getVersion());
});
```

> **注意**：v2.1 起 `PluginContext` 不再持有数据访问接口。持久化请通过 Spring 子容器注入的 `ExtensionClient` Bean 完成。

---

## 11. 路径常量速查

| 用途 | 路径/常量 | 说明 |
|------|----------|------|
| 插件静态资源 URL | `/api/pf4j/plugin/{gameCode}/ui/**` | 前端资源访问 |
| 插件 API 基础路径 | `/api/plugin/{gameCode}/**` | 控制器请求路径 |
| 插件清单 API | `/api/pf4j/plugin/{gameCode}/manifest` | 获取清单信息 |
| 插件框架管理 API | `/api/pf4j/plugins/**` | 插件管理操作 |
| 插件 Thymeleaf 模板 | `plugin/{gameCode}/{templatePath}` | 模板渲染 |

---

## 12. 检查清单

插件发布前请逐项检查：

- [ ] `plugin.properties` 配置完整且正确
- [ ] `getGameCode()` 返回值全局唯一
- [ ] `getVersion()` 遵循语义化版本规范
- [ ] 持久化资源类标注 `@ExtensionModel` 并继承 `AbstractExtension<T>`
- [ ] 控制器路径以 `/api/plugin/{gameCode}/` 开头
- [ ] `specFilter` 等查询条件不直接拼 SQL
- [ ] 所有框架依赖为 `provided` scope
- [ ] 前端入口文件 `ui/index.html` 存在
- [ ] `onLoad()` / `onUnload()` 钩子正确实现
- [ ] 异常使用 `PluginException` 体系
