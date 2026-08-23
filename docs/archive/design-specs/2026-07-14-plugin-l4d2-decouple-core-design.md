# plugin-l4d2 解耦 game-platform-core 设计

> 日期：2026-07-14
> 状态：已批准（待实现）
> 范围：plugin / core / plugin-l4d2 模块

---

## 1. 背景与目标

### 1.1 现状

`plugin-l4d2` 模块的 `pom.xml` 第 49-54 行依赖了 `game-platform-core`（scope=provided）。这违反了插件架构的隔离原则：插件应只依赖 `game-platform-plugin`（扩展点 SDK）和 `game-platform-api`（DTO/VO 契约），不应直接依赖宿主核心模块。

### 1.2 依赖 core 的原因

plugin-l4d2 的 6 个 Controller 使用了 core 模块的 3 个服务：

| core 中的类 | 类型 | plugin-l4d2 使用的方法 |
|------------|------|---------------------|
| `InstanceService` | 接口 | `getInstanceById` |
| `HostService` | 接口 | `getHostResourceInfo` |
| `FileService` | 具体类 | `writeTextFile`/`uploadFile`/`deleteFile`/`downloadFileToMemory` |

注：`Result`、`InstanceVO`、`HostResourceVO` 实际位于 **api 模块**，plugin-l4d2 通过 api 依赖已可用，无需处理。

### 1.3 目标

- 在 `plugin` 模块新增 3 个服务抽象接口（`InstanceQueryService`/`HostQueryService`/`FileAccessService`）
- `core` 模块实现这些接口，通过 `PluginSpringContextFactory` 注入插件子容器
- `plugin-l4d2` 改为依赖 `plugin` + `api`，移除 `core` 依赖

---

## 2. 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 服务接口归属模块 | **plugin 模块** | 插件可见的契约应在 SDK 层，隔离最彻底 |
| 接口粒度 | **符合实际业务的完整方法集** | 不仅是当前用到的方法，提供插件开发常用的合理能力 |
| 实现方式 | **委托模式** | core 实现类委托给现有 InstanceService/HostService/FileService，不重复逻辑 |
| 注入方式 | **子容器单例注册** | 参考 ExtensionClient 的注册方式 |

---

## 3. 新增服务接口（plugin 模块）

路径：`backend/plugin/src/main/java/com/gameplatform/plugin/service/`

### 3.1 InstanceQueryService — 实例查询与控制

不含 createInstance/updateInstance/deleteInstance（实例生命周期管理属宿主职责）。

```java
public interface InstanceQueryService {
    /** 根据ID查询实例详情 */
    InstanceVO getInstanceById(Long id);

    /** 根据主机ID查询实例列表 */
    List<InstanceVO> getInstancesByHostId(Long hostId);

    /** 根据游戏ID查询实例列表 */
    List<InstanceVO> getInstancesByGameId(Long gameId);

    /** 获取实例运行状态（刷新并返回最新状态） */
    InstanceVO getInstanceStatus(Long id);

    /** 启动实例 */
    boolean startInstance(Long id);

    /** 停止实例 */
    boolean stopInstance(Long id);

    /** 重启实例 */
    boolean restartInstance(Long id);

    /** 获取实例日志 */
    String getInstanceLogs(Long id, int lines);

    /** 执行控制台命令 */
    String executeCommand(Long id, String command);
}
```

### 3.2 HostQueryService — 主机信息查询

```java
public interface HostQueryService {
    /** 获取主机资源监控信息（CPU/内存/磁盘/网络） */
    HostResourceVO getHostResourceInfo(Long hostId);

    /** 获取主机详情（IP/端口/SSH等） */
    HostVO getHostById(Long hostId);
}
```

### 3.3 FileAccessService — 远程文件操作

`FileInfo` 作为接口内部类，包含 name/path/directory/size/lastModified/permissions/owner。

```java
public interface FileAccessService {

    // ===== 文件读写 =====
    /** 读取远程文本文件内容 */
    String readTextFile(Long hostId, String remotePath);

    /** 写入远程文本文件 */
    void writeTextFile(Long hostId, String remotePath, String content);

    /** 下载远程文件到内存（返回字节数组） */
    byte[] downloadFileToMemory(Long hostId, String remotePath);

    // ===== 文件上传/下载/删除/移动 =====
    /** 上传 MultipartFile 到远程路径 */
    void uploadFile(Long hostId, String remotePath, MultipartFile file);

    /** 上传本地文件到远程路径 */
    void uploadLocalFile(Long hostId, String remotePath, String localPath);

    /** 下载远程文件到本地路径 */
    void downloadFile(Long hostId, String remotePath, String localPath);

    /** 删除远程文件 */
    void deleteFile(Long hostId, String remotePath);

    /** 移动/重命名远程文件 */
    void moveFile(Long hostId, String oldPath, String newPath);

    // ===== 目录操作 =====
    /** 列出远程目录下的文件列表 */
    List<FileInfo> listFiles(Long hostId, String remotePath);

    /** 创建远程目录 */
    void createDirectory(Long hostId, String remotePath);

    /** 删除远程目录（recursive=true 递归删除） */
    void deleteDirectory(Long hostId, String remotePath, boolean recursive);

    // ===== 查询 =====
    /** 检查远程文件/目录是否存在 */
    boolean exists(Long hostId, String remotePath);

    /** 获取远程文件信息 */
    FileInfo getFileInfo(Long hostId, String remotePath);

    /** 文件信息（名称/路径/是否目录/大小/修改时间/权限/所有者） */
    @Data
    class FileInfo {
        private String name;
        private String path;
        private boolean directory;
        private long size;
        private long lastModified;
        private String permissions;
        private String owner;
    }
}
```

---

## 4. core 模块实现 + 子容器注册

### 4.1 新增 3 个实现类

路径：`backend/core/src/main/java/com/gameplatform/plugin/service/`

| 实现类 | 委托目标 | 说明 |
|--------|---------|------|
| `InstanceQueryServiceImpl` | `InstanceService` | 9 个方法委托转发 |
| `HostQueryServiceImpl` | `HostService` | 2 个方法委托转发 |
| `FileAccessServiceImpl` | `FileService` | 12 个方法委托转发，`FileService.FileInfo` 转换为 `FileAccessService.FileInfo` |

实现类用 `@Service` 注册到主容器，`@RequiredArgsConstructor` 注入现有服务。

### 4.2 PluginSpringContextFactory 注册到子容器

参考现有 `ExtensionClient` 注册方式（第 78-81 行），在 `createPluginContext` 方法中新增：

```java
// 注册插件可用的宿主服务
InstanceQueryService instanceQueryService = mainContext.getBean(InstanceQueryService.class);
HostQueryService hostQueryService = mainContext.getBean(HostQueryService.class);
FileAccessService fileAccessService = mainContext.getBean(FileAccessService.class);
childContext.getBeanFactory().registerSingleton("instanceQueryService", instanceQueryService);
childContext.getBeanFactory().registerSingleton("hostQueryService", hostQueryService);
childContext.getBeanFactory().registerSingleton("fileAccessService", fileAccessService);
```

### 4.3 设计要点

- 实现类不重复业务逻辑，纯委托转发
- `FileService` 是具体类，`FileInfo` 是私有内部类，`FileAccessServiceImpl` 需做类型转换
- 注册时机与 `ExtensionClient` 一致，在子容器创建后、插件 Bean 初始化前

---

## 5. plugin-l4d2 解耦改造

### 5.1 pom.xml 移除 core 依赖

删除 `plugin-l4d2/pom.xml` 第 49-54 行的 `game-platform-core` 依赖块。

### 5.2 Controller 改造（6 个文件）

所有 Controller 将 core 的服务类型替换为 plugin 的服务接口：

| 旧 import（core） | 新 import（plugin） |
|------------------|-------------------|
| `com.gameplatform.service.InstanceService` | `com.gameplatform.plugin.service.InstanceQueryService` |
| `com.gameplatform.service.FileService` | `com.gameplatform.plugin.service.FileAccessService` |
| `com.gameplatform.service.HostService` | `com.gameplatform.plugin.service.HostQueryService` |

字段类型与变量名同步修改：
- `private final InstanceService instanceService;` → `private final InstanceQueryService instanceQueryService;`
- `private final FileService fileService;` → `private final FileAccessService fileAccessService;`
- `private final HostService hostService;` → `private final HostQueryService hostQueryService;`

调用处方法名不变（委托实现保持同名）。

### 5.3 涉及的 6 个 Controller 文件

1. `AdminController.java` — instanceService + fileService 替换
2. `MonitorController.java` — instanceService + hostService 替换
3. `MapController.java` — instanceService + fileService 替换
4. `ServerConfigController.java` — instanceService + fileService 替换
5. `PluginManageController.java` — instanceService + fileService 替换
6. `RconController.java` — instanceService 替换

### 5.4 验证

plugin-l4d2 编译通过，且 `mvn dependency:tree -pl plugin-l4d2` 不再出现 `game-platform-core`。

---

## 6. 涉及文件清单

### 新增文件（6 个）

**plugin 模块（3 个接口）**：
- `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceQueryService.java`
- `backend/plugin/src/main/java/com/gameplatform/plugin/service/HostQueryService.java`
- `backend/plugin/src/main/java/com/gameplatform/plugin/service/FileAccessService.java`

**core 模块（3 个实现）**：
- `backend/core/src/main/java/com/gameplatform/plugin/service/InstanceQueryServiceImpl.java`
- `backend/core/src/main/java/com/gameplatform/plugin/service/HostQueryServiceImpl.java`
- `backend/core/src/main/java/com/gameplatform/plugin/service/FileAccessServiceImpl.java`

### 修改文件（8 个）

- `backend/plugin-l4d2/pom.xml` — 移除 core 依赖
- `backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java` — 注册 3 个服务到子容器
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/AdminController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/MapController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/ServerConfigController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`
- `backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/controller/RconController.java`

---

## 7. 验收标准

1. `mvn test` 全量通过（361+ 个测试，0 失败 0 错误）
2. `mvn dependency:tree -pl plugin-l4d2` 不再出现 `game-platform-core`
3. plugin-l4d2 的 6 个 Controller 均使用 `InstanceQueryService`/`HostQueryService`/`FileAccessService`
4. 3 个服务实现类正确委托给现有服务
5. `PluginSpringContextFactory` 成功注册 3 个服务到子容器
