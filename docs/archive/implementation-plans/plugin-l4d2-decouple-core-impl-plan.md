# plugin-l4d2 解耦 game-platform-core 实现计划

> 关联设计：`../design-specs/2026-07-14-plugin-l4d2-decouple-core-design.md`（已归档，已批准）
> 范围：backend/plugin、backend/core、backend/plugin-l4d2
> 目标：plugin-l4d2 不再依赖 game-platform-core，改为通过 plugin 模块新增的 3 个服务接口与子容器注入获得宿主能力

---

## 一、现状分析（基于 Phase 1 实际代码核对）

### 1.1 当前依赖关系
- `backend/plugin-l4d2/pom.xml` 第 49-54 行依赖 `game-platform-core`（scope=provided），需移除
- `backend/plugin/pom.xml` 当前依赖：`game-platform-api`、`pf4j`、`jackson-databind`，**缺少 `spring-web`**（FileAccessService 需要的 `MultipartFile` 来自该包）

### 1.2 plugin-l4d2 Controller 实际 import 情况（经 Grep 验证）
| Controller | 引用的 core 服务 |
|------------|----------------|
| `AdminController.java` | InstanceService + FileService |
| `MonitorController.java` | InstanceService + HostService |
| `MapController.java` | InstanceService + FileService |
| `ServerConfigController.java` | InstanceService + FileService |
| `PluginManageController.java` | InstanceService + FileService |
| `RconController.java` | InstanceService |

共 6 个 Controller，11 处 import 需替换。

### 1.3 PluginSpringContextFactory 当前注册逻辑
`PluginSpringContextFactory.loadPluginSpringContext` 第 78-83 行已通过 `mainContext.getBean(ExtensionIdGenerator.class)` + `registerSingleton` 注册 `ExtensionClient`。新增 3 个服务沿用相同模式，插入位置在第 83 行（ExtensionClient 注册完成）之后、第 86 行 `childContext.scan(basePackage)` 之前。

### 1.4 core 现有服务签名（已核对源码）
- `InstanceService`：`getInstanceById`/`getInstancesByHostId`/`getInstancesByGameId`/`getInstanceStatus`/`startInstance`/`stopInstance`/`restartInstance`/`getInstanceLogs`/`executeCommand` 共 9 个可委托方法
- `HostService`：`getHostResourceInfo`/`getHostById` 共 2 个方法
- `FileService`：`readTextFile`/`writeTextFile`/`downloadFileToMemory`/`uploadFile`/`uploadLocalFile`/`downloadFile`/`deleteFile`/`moveFile`/`listFiles`/`createDirectory`/`deleteDirectory`/`exists`/`getFileInfo` 共 13 个方法 + `FileInfo` 内部静态类（7 字段）

---

## 二、实现步骤（S1-S5）

### S1：plugin 模块新增 3 个服务接口 + 添加 spring-web 依赖

**S1.1 修改 `backend/plugin/pom.xml`** — 在 `<dependencies>` 中新增 spring-web provided 依赖：
```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-web</artifactId>
    <scope>provided</scope>
</dependency>
```
（仅引入 MultipartFile 接口，不引入 spring-webmvc 等运行时栈，保持插件 SDK 轻量）

**S1.2 新增 3 个接口文件**（路径 `backend/plugin/src/main/java/com/gameplatform/plugin/service/`）：
- `InstanceQueryService.java` — 9 方法（与 InstanceService 对应方法同名）
- `HostQueryService.java` — 2 方法（getHostResourceInfo / getHostById）
- `FileAccessService.java` — 13 方法 + 内部类 FileInfo（7 字段：name/path/directory/size/lastModified/permissions/owner）

**关键点**：
- 接口完整 JavaDoc 注释（用户要求"补充完整的注释"）
- `FileAccessService.FileInfo` 使用 `@Data` 注解，作为接口内嵌静态类
- `uploadFile` 方法签名包含 `MultipartFile`（来自 spring-web）

### S2：core 模块新增 3 个实现类（委托模式）

**新增文件路径**：`backend/core/src/main/java/com/gameplatform/plugin/service/`

| 实现类 | 委托目标 | 注入方式 |
|--------|---------|---------|
| `InstanceQueryServiceImpl` | InstanceService | `@Service` + `@RequiredArgsConstructor` |
| `HostQueryServiceImpl` | HostService | `@Service` + `@RequiredArgsConstructor` |
| `FileAccessServiceImpl` | FileService | `@Service` + `@RequiredArgsConstructor` |

**实现要点**：
- 每个方法为纯委托转发，方法体形如 `return delegate.xxx(args);`
- `FileAccessServiceImpl` 需实现私有静态方法 `convertFileInfo(FileService.FileInfo src)` 将 core 的 FileInfo 转换为 plugin 的 `FileAccessService.FileInfo`
- 所有方法补充完整 JavaDoc

### S3：PluginSpringContextFactory 注册 3 个服务到子容器

**修改文件**：`backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`

在第 83 行（`log.info("  已注册 ExtensionClient...")`）之后插入：

```java
// 4. 注册插件可用的宿主服务（解耦插件对 core 的直接依赖）
InstanceQueryService instanceQueryService = mainContext.getBean(InstanceQueryService.class);
HostQueryService hostQueryService = mainContext.getBean(HostQueryService.class);
FileAccessService fileAccessService = mainContext.getBean(FileAccessService.class);
childContext.getBeanFactory().registerSingleton("instanceQueryService", instanceQueryService);
childContext.getBeanFactory().registerSingleton("hostQueryService", hostQueryService);
childContext.getBeanFactory().registerSingleton("fileAccessService", fileAccessService);
log.info("  已注册插件可用服务: InstanceQueryService, HostQueryService, FileAccessService");
```

**新增 import**：
- `com.gameplatform.plugin.service.InstanceQueryService`
- `com.gameplatform.plugin.service.HostQueryService`
- `com.gameplatform.plugin.service.FileAccessService`

### S4：plugin-l4d2 解耦改造

**S4.1 修改 `backend/plugin-l4d2/pom.xml`** — 删除第 49-54 行 `game-platform-core` 依赖块

**S4.2 修改 6 个 Controller**（路径 `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/`）：

每个 Controller 的统一改造规则：
1. **import 替换**：
   - `com.gameplatform.service.InstanceService` → `com.gameplatform.plugin.service.InstanceQueryService`
   - `com.gameplatform.service.HostService` → `com.gameplatform.plugin.service.HostQueryService`
   - `com.gameplatform.service.FileService` → `com.gameplatform.plugin.service.FileAccessService`
2. **字段类型与变量名替换**：
   - `private final InstanceService instanceService;` → `private final InstanceQueryService instanceQueryService;`
   - `private final HostService hostService;` → `private final HostQueryService hostQueryService;`
   - `private final FileService fileService;` → `private final FileAccessService fileAccessService;`
3. **方法体内调用处变量名同步替换**（方法名不变，因为委托实现保持同名）：
   - `instanceService.` → `instanceQueryService.`
   - `hostService.` → `hostQueryService.`
   - `fileService.` → `fileAccessService.`

| Controller | 变更范围 |
|------------|---------|
| AdminController | 2 个字段 + 调用替换 |
| MonitorController | 2 个字段 + 调用替换 |
| MapController | 2 个字段 + 调用替换 |
| ServerConfigController | 2 个字段 + 调用替换 |
| PluginManageController | 2 个字段 + 调用替换 |
| RconController | 1 个字段 + 调用替换 |

### S5：全量测试与验收

**S5.1 编译验证**：
- `cd backend && mvn clean compile -pl plugin,core,plugin-l4d2 -am`

**S5.2 依赖隔离验证**：
- `mvn dependency:tree -pl plugin-l4d2 | findstr game-platform-core` 应无输出

**S5.3 全量测试**：
- `cd backend && mvn test`
- 期望：361+ 个测试通过，0 失败 0 错误

**S5.4 验收检查清单**：
- [ ] plugin 模块 pom.xml 含 spring-web provided 依赖
- [ ] plugin 模块 3 个服务接口文件存在且方法完整
- [ ] core 模块 3 个实现类用 @Service 注册并纯委托转发
- [ ] PluginSpringContextFactory 在子容器注册了 3 个服务单例
- [ ] plugin-l4d2/pom.xml 不再依赖 game-platform-core
- [ ] 6 个 Controller 均使用 plugin 模块的服务接口
- [ ] `mvn dependency:tree -pl plugin-l4d2` 不含 game-platform-core
- [ ] `mvn test` 全量通过

---

## 三、假设与决策

| 项 | 假设/决策 | 依据 |
|----|----------|------|
| 接口归属 | plugin 模块 | 设计文档 §2 已批准 |
| 实现方式 | 委托模式（core 实现转发给现有服务） | 设计文档 §2 已批准，避免重复业务逻辑 |
| 注入方式 | 子容器单例注册（参考 ExtensionClient） | 与现有 ExtensionClient 注册方式一致 |
| spring-web scope | provided | 仅编译期需要 MultipartFile 接口，运行时由宿主提供 |
| FileInfo 转换 | FileAccessServiceImpl 内私有静态方法 | FileService.FileInfo 是 core 私有类，需手动映射 7 字段 |
| 方法命名 | 与原服务保持同名 | 减少调用处变更面，仅变量名替换 |
| 不修改 api 模块 | InstanceVO/HostResourceVO/Result 已在 api 模块 | 设计文档 §1.2 已说明 |

---

## 四、风险与回滚

**风险**：S4 改造后若子容器未正确注入 3 个服务，Controller 启动时 NoSuchBeanDefinitionException
**缓解**：S3 注册逻辑与 ExtensionClient 同位置同模式，且 mainContext 中已存在 3 个 @Service 实现类
**回滚**：如出现问题，git revert S1-S4 全部提交，恢复 core 依赖

---

## 五、执行顺序与依赖

```
S1 (plugin 接口 + pom) ──┐
                         ├─→ S2 (core 实现) ──→ S3 (注册) ──→ S4 (plugin-l4d2 解耦) ──→ S5 (测试验收)
                         │
              （S1.1 pom 必须先于 S1.2，因为接口编译需要 MultipartFile）
```

S1-S3 完成后，core 模块本身编译通过（plugin 模块的接口被 core 引用，core 实现类注册到主容器）。
S4 完成后，plugin-l4d2 编译通过且不再依赖 core。
S5 验证全部约束。
