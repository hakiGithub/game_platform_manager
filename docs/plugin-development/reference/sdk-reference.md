# SDK 接口签名速查

> 对齐版本：v3.12.0（2026-10-19）｜ 权威源：`backend/plugin/` 源码

本文件只回答“方法长什么样”。实现约束、生命周期、路径与异常语义见对应主题文档。

## 扩展点

### GameEnhancementExtension

```java
String getGameCode();
String getGameName();
String getVersion();
String getDescription();

default Map<String, Object> getManifest();
default List<PluginMenuDeclaration> getMenus();
default List<PluginConfigField> getConfigFields();
default List<DeployConfigDeclaration> getDeployConfigs();

default void onLoad(PluginContext context);
default void onUnload();
default void onInstanceCreate(Long instanceId, Map<String, Object> config);
default void onInstanceStart(Long instanceId);
default void onInstanceStop(Long instanceId);
default void onInstanceDelete(Long instanceId);
default void onInstanceUpdate(Long instanceId, Map<String, Object> config);
default void onLoadError(PluginContext context, Throwable error);

default String getIcon();
default String getFrontendEntry();
default String getBasePackage();
default List<String> getDependencies();
```

`getMenus()` 是唯一菜单来源；`getManifest().features` 已废弃。

### PluginMenuDeclaration

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PluginMenuDeclaration {
    String title;
    String path;
    String icon;
    Integer order;
    String parent;
    @Builder.Default Boolean requireInstance = Boolean.TRUE;
}
```

### TaskHandlerExtension / TaskHandler

```java
public interface TaskHandlerExtension {
    Map<String, TaskHandler> getTaskHandlers(); // key = taskType
}

public interface TaskHandler {
    String getType();
    String getDisplayName();
    boolean isRetryable();
    default int getMaxRetryCount();
    long getDefaultTimeoutMs();
    default String getMutexKey(TaskPayload payload);
    TaskResult execute(TaskContext context, TaskPayload payload) throws Exception;
    default String getResultSummary(TaskResult result);

    default void onSubmit(TaskSubmitContext context);
    default void onBeforeExecute(TaskContext context, TaskPayload payload);
    default void onAfterExecute(TaskContext context, TaskPayload payload, TaskResult result);
    default void onSuccess(TaskContext context, TaskPayload payload, TaskResult result);
    default void onFailure(TaskContext context, TaskPayload payload, Throwable error);
    default void onCancel(TaskContext context, TaskPayload payload);
    default void onRetry(TaskContext context, TaskPayload payload);
}
```

### TaskService

```java
String submit(TaskSubmitRequest request);
TaskVO getTask(String taskId);
PageResult<TaskVO> listTasks(TaskQuery query);
List<TaskLog> getTaskLogs(String taskId);
boolean cancelMyOwn(String taskId);
```

## 持久化

### ExtensionClient

```java
<T extends AbstractExtension<?>> void create(T extension);
<T extends AbstractExtension<?>> void update(T extension);
<T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name);
<T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id);
<T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name);
<T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id);
<T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions options);
<T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass);
<T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status);
<T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status);
long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions options);
Set<String> getManagedTables();
```

### ExtensionModel

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExtensionModel {
    Strategy strategy() default Strategy.SHARED; // SHARED / PLUGIN_ISOLATED / MODEL_ISOLATED
    String group() default "";                    // 空值使用 pluginId
    String kind() default "";                     // 空值使用类 simpleName
}
```

## 定时任务

### ScheduledTaskHandler

```java
public interface ScheduledTaskHandler {
    String getKey();
    String getDisplayName();
    default long getDefaultTimeoutMs();
    TaskResult execute(TaskContext context, TaskPayload payload) throws Exception;
}
```

### ScheduledTaskDeclarationExtension

```java
public interface ScheduledTaskDeclarationExtension {
    List<ScheduleDeclaration> getScheduleDeclarations();
}
```

### ScheduleService

```java
String create(ScheduleCreateRequest request);
void update(String id, ScheduleUpdateRequest request);
void enable(String id);
void disable(String id);
void delete(String id);
String trigger(String id);
ScheduleVO get(String id);
PageResult<ScheduleVO> list(ScheduleQuery query);
PageResult<ScheduleRunVO> listRuns(ScheduleRunQuery query);
List<TaskLog> getRunLogs(String runId);
```

## 实例动态信息

```java
@Component
public class MyGameInstanceInfoProvider implements InstanceInfoProvider {
    public InstanceDynamicInfo getInstanceInfo(long instanceId);
}

public record InstanceDynamicInfo(Integer playerCount, Integer maxPlayerCount,
                                  Map<String, Object> extras) {
    public static InstanceDynamicInfo ofPlayerCount(Integer playerCount);
    public static InstanceDynamicInfo ofPlayers(Integer playerCount, Integer maxPlayerCount);
}
```

普通 `@Component` 注册，不是 PF4J ExtensionPoint。返回 `null` 表示本次不可知，宿主执行降级且不落库。

## 宿主服务面

### HostQueryService

```java
HostResourceVO getHostResourceInfo(Long hostId);
HostVO getHostById(Long hostId);
```

### InstanceQueryService

```java
InstanceVO getInstanceById(Long id);
List<InstanceVO> getInstancesByHostId(Long hostId);
List<InstanceVO> getInstancesByGameId(Long gameId);
List<InstanceVO> listByGameCode(String gameCode);
InstanceVO getInstanceStatus(Long id);
boolean startInstance(Long id);
boolean stopInstance(Long id);
boolean restartInstance(Long id);
String getInstanceLogs(Long id, int lines);
String executeCommand(Long id, String command);
```

### InstanceFileService

```java
String readTextFile(long instanceId, String relativePath);
String readTextFile(long instanceId, String relativePath, Charset charset);
void writeTextFile(long instanceId, String relativePath, String content);
void writeTextFile(long instanceId, String relativePath, String content, Charset charset);
byte[] downloadFileToMemory(long instanceId, String relativePath);
byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);

default void uploadLocalFile(long instanceId, String relativePath, String localPath);
default void downloadFile(long instanceId, String relativePath, String localPath);
void uploadLocalFile(long instanceId, String relativePath, String localPath,
                     FileTransferProgressCallback callback);
void downloadFile(long instanceId, String relativePath, String localPath,
                  FileTransferProgressCallback callback);

void deleteFile(long instanceId, String relativePath);
void moveFile(long instanceId, String oldRelativePath, String newRelativePath);
void copyFile(long instanceId, String srcRelativePath, String dstRelativePath);
boolean exists(long instanceId, String relativePath);
FileInfo getFileInfo(long instanceId, String relativePath);
List<FileInfo> listFiles(long instanceId, String relativePath);
void createDirectory(long instanceId, String relativePath);
void deleteDirectory(long instanceId, String relativePath, boolean recursive);
void copyDirectory(long instanceId, String srcRelativePath, String dstRelativePath);
long tailFile(long instanceId, String relativePath, long offset,
              Charset charset, Consumer<String> lineConsumer);
String computeDigest(long instanceId, String relativePath, String algorithm);
default String md5(long instanceId, String relativePath);
```

### FileAccessService

```java
String readTextFile(Long hostId, String remotePath);
String readTextFile(Long hostId, String remotePath, Charset charset);
void writeTextFile(Long hostId, String remotePath, String content);
byte[] downloadFileToMemory(Long hostId, String remotePath);
byte[] getFileBytes(Long hostId, String remotePath, long offset, long length);
void uploadFile(Long hostId, String remotePath, MultipartFile file);
default void uploadLocalFile(Long hostId, String remotePath, String localPath);
default void downloadFile(Long hostId, String remotePath, String localPath);
void uploadLocalFile(Long hostId, String remotePath, String localPath,
                     FileTransferProgressCallback callback);
void downloadFile(Long hostId, String remotePath, String localPath,
                  FileTransferProgressCallback callback);
void deleteFile(Long hostId, String remotePath);
void moveFile(Long hostId, String oldPath, String newPath);
List<FileInfo> listFiles(Long hostId, String remotePath);
void createDirectory(Long hostId, String remotePath);
void deleteDirectory(Long hostId, String remotePath, boolean recursive);
boolean exists(Long hostId, String remotePath);
FileInfo getFileInfo(Long hostId, String remotePath);
long tailFile(Long hostId, String remotePath, long offset,
              Charset charset, Consumer<String> lineConsumer);
CommandResult executeCommand(Long hostId, String command, long timeoutMs);
default CommandResult executeCommand(Long hostId, String command);
```

### SshTunnelService

```java
TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);
TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort);
void close(TunnelHandle handle);

record SshEndpoint(String host, int port, String user, String password, String privateKey) {}
record TunnelHandle(String id, int localPort, String remoteHost, int remotePort,
                    String ownerPluginId) {}
```

### RconService

```java
String executeCommand(long instanceId, String command);
String executeCommand(long instanceId, String command, Duration timeout);
boolean testConnection(long instanceId);
```

### CloudDriveService

```java
List<CloudFileInfo> list(String accountName, String path, boolean refresh);
CloudLink link(String accountName, String path);
long download(String accountName, String path, OutputStream target);
List<String> transfer(String accountName, String shareUrl, String passcode,
                      String targetPath, Duration timeout);

record CloudFileInfo(String name, String path, boolean directory,
                     long size, Long modifiedAtMillis) {}
record CloudLink(String url, Map<String, String> headers, Long expiresAtMillis) {}
```

### HostToolingService

```java
List<String> extractArchive(Long hostId, String remoteArchive,
                            String destDir, String includePattern);
String fileDigest(Long instanceId, String relativePath);
String runToolScript(Long instanceId, String relativePath, String scriptResource);
```

### FileTransferProgressCallback

```java
void onStart(long totalBytes);
void onProgress(long bytesTransferred, long totalBytes);
void onComplete();
void onError(Throwable error);
```

## 常量

```java
public final class PluginConstants {
    public static final String FRAMEWORK_API_PREFIX = "/pf4j";
    public static final String PLUGIN_RESOURCE_URL_PREFIX = "/api/pf4j/plugin";
    public static final String PLUGIN_RESOURCE_MAPPING = "/pf4j/plugin";
    public static final String PLUGIN_API_BASE_TEMPLATE = "/api/plugin/{gameCode}";
    public static final String PLUGIN_FRONTEND_ENTRY_TEMPLATE =
            "/api/pf4j/plugin/{gameCode}/ui/{entry}";
    public static final String PROP_PLUGIN_ID = "plugin.id";
    public static final String PROP_PLUGIN_CLASS = "plugin.class";
    public static final String PROP_PLUGIN_VERSION = "plugin.version";
    public static final String PROP_GAME_CODE = "plugin.gameCode";
    public static final String PROP_BASE_PACKAGE = "plugin.basePackage";
    public static final String DEFAULT_FRONTEND_ENTRY = "index.html";
    public static final String DEFAULT_ICON = "assets/icon.png";
    public static final int STATIC_RESOURCE_CACHE_DAYS = 7;
}
```

## 异常

| 异常 | 含义 |
|---|---|
| `PluginException` | 插件异常基类 |
| `PluginLoadException` | 插件加载失败 |
| `PluginConfigException` | 插件配置缺失或无效 |
| `PluginPathConflictException` | 插件控制器路径冲突 |
| `ExtensionStoreException` | 扩展资源存储异常基类 |
| `DuplicateExtensionException` | 资源 name 已存在 |
| `OptimisticLockException` | 更新时版本冲突 |
| `ExtensionNotFoundException` | 目标资源不存在 |
| `BusinessException` | 宿主服务拒绝或执行失败 |
