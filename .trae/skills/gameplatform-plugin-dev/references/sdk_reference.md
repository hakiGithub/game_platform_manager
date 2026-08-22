# SDK 接口签名速查

> 权威来源：`backend/plugin/src/main/java/com/gameplatform/plugin/`。本文件为速查摘要，完整说明见本 SKILL 目录其他 `references/` 文件。
> 当前对齐版本：v3.8.0（ADR-0001 菜单归属权迁移 / ADR-0009 平台能力三项扩展 / ADR-0011 定时任务体系）

## 扩展点

### GameEnhancementExtension (extends ExtensionPoint)
```java
String getGameCode();                  // 全局唯一，小写英文+连字符
String getGameName();
String getVersion();                   // 语义化版本
String getDescription();
default Map<String,Object> getManifest();          // 元数据透传（features 已废弃，ADR-0001）
default List<PluginMenuDeclaration> getMenus();    // ★ 菜单声明（v3.1.0 ADR-0001，默认空列表）
default List<PluginConfigField> getConfigFields();
default void onLoad(PluginContext ctx);
default void onUnload();
default void onInstanceCreate(Long instanceId, Map<String,Object> config);
default void onInstanceStart(Long instanceId);
default void onInstanceStop(Long instanceId);
default void onInstanceDelete(Long instanceId);
default void onInstanceUpdate(Long instanceId, Map<String,Object> config);  // v3.7.0 ADR-0009：update 后完整新 configInfo，每次更新都触发
default void onLoadError(PluginContext ctx, Throwable error);
default String getIcon();              // 相对 ui/，默认 assets/icon.png
default String getFrontendEntry();     // 默认 index.html
default String getBasePackage();       // Spring 扫描包
default List<String> getDependencies();
```

### PluginMenuDeclaration (v3.1.0 ADR-0001)
```java
// 强类型菜单声明，插件通过 getMenus() 返回 List<PluginMenuDeclaration>
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PluginMenuDeclaration {
    String  title;             // 菜单标题（必填）
    String  path;              // 子应用前端路由 path（必填，同插件内唯一）
    String  icon;              // Element Plus 图标组件名（如 "Monitor"）
    Integer order;             // 排序值（升序）
    String  parent;            // 父菜单 path（用于二级菜单分组）
    @Builder.Default
    Boolean requireInstance = Boolean.TRUE;  // true=需 instanceId；false=纯资源页（如地图中心）
}
```

**宿主校验规则**（`PluginFrameworkServiceImpl.buildMenusFromDeclarations`）：
- `path` 为空或空白 → 抛 `IllegalStateException`（提示插件 id + 菜单 title）
- 同插件内 `path` 重复 → 抛 `IllegalStateException`（提示插件 id + 重复 path）
- `requireInstance == null` → 框架补全为 `Boolean.TRUE`
- 宿主**不预置任何默认菜单**，插件需显式声明完整菜单列表

### TaskHandlerExtension (extends ExtensionPoint)
```java
Map<String, TaskHandler> getTaskHandlers();   // key=taskType，构造时缓存
```

### TaskHandler
```java
String getType();
String getDisplayName();
boolean isRetryable();
int getMaxRetryCount();
long getDefaultTimeoutMs();
void onSubmit(TaskSubmitContext ctx);          // 抛异常阻止提交
TaskResult execute(TaskContext ctx, TaskPayload payload) throws Exception;
String getResultSummary(TaskResult result);
default String getMutexKey(TaskPayload payload);  // null=默认规则，""=不互斥
// 生命周期：onBeforeExecute / onAfterExecute / onSuccess / onFailure / onCancel / onRetry
```

## ExtensionClient（持久化唯一入口，绑定 pluginId）

```java
<T extends AbstractExtension<?>> void create(T ext);                    // → DuplicateExtensionException
<T extends AbstractExtension<?>> void update(T ext);                    // → OptimisticLockException / ExtensionNotFoundException
<T extends AbstractExtension<?>> void delete(Class<T> cls, String name);
<T extends AbstractExtension<?>> void deleteById(Class<T> cls, String id);
<T extends AbstractExtension<?>> Optional<T> get(Class<T> cls, String name);
<T extends AbstractExtension<?>> Optional<T> getById(Class<T> cls, String id);
<T extends AbstractExtension<?>> List<T> list(Class<T> cls, ListOptions opts);
<T extends AbstractExtension<?>> List<T> listAll(Class<T> cls);
long count(Class<? extends AbstractExtension<?>> cls, ListOptions opts);
<T extends AbstractExtension<?>> T updateStatus(Class<T> cls, String name, String status);
<T extends AbstractExtension<?>> T updateStatusById(Class<T> cls, String id, String status);
Set<String> getManagedTables();
```

## @ExtensionModel 注解
```java
Strategy strategy() default Strategy.SHARED;   // SHARED / PLUGIN_ISOLATED / MODEL_ISOLATED
String group() default "";                      // 空=pluginId
String kind() default "";                       // 空=类 simpleName
```
| 策略 | 表名 |
|---|---|
| SHARED | `extensions` |
| PLUGIN_ISOLATED | `ext_{pluginId}` |
| MODEL_ISOLATED | `ext_{pluginId}_{kind}` |

## 宿主服务面

### HostQueryService
```java
HostResourceVO getHostResourceInfo(Long hostId);   // CPU/内存/磁盘/网络
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

### InstanceFileService（实例感知，自动路由 SFTP/docker exec）
```java
String readTextFile(long instanceId, String relativePath);
String readTextFile(long instanceId, String relativePath, Charset charset);
void   writeTextFile(long instanceId, String relativePath, String content);
byte[] downloadFileToMemory(long instanceId, String relativePath);
byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);
void uploadLocalFile(long instanceId, String relativePath, String localPath);
void downloadFile(long instanceId, String relativePath, String localPath);
void deleteFile(long instanceId, String relativePath);
void moveFile(long instanceId, String oldRel, String newRel);
void copyFile(long instanceId, String srcRel, String dstRel);
boolean exists(long instanceId, String relativePath);
FileInfo getFileInfo(long instanceId, String relativePath);
List<FileInfo> listFiles(long instanceId, String relativePath);
void createDirectory(long instanceId, String relativePath);
void deleteDirectory(long instanceId, String relativePath, boolean recursive);
void copyDirectory(long instanceId, String srcRel, String dstRel);
long tailFile(long instanceId, String relativePath, long offset, Charset charset, Consumer<String> lineConsumer);
String computeDigest(long instanceId, String relativePath, String algorithm);
default String md5(long instanceId, String relativePath);
```

### FileAccessService（主机级 SFTP + 命令执行）
```java
String readTextFile(Long hostId, String remotePath);
String readTextFile(Long hostId, String remotePath, Charset charset);
void writeTextFile(Long hostId, String remotePath, String content);
byte[] downloadFileToMemory(Long hostId, String remotePath);
byte[] getFileBytes(Long hostId, String remotePath, long offset, long length);
void uploadFile(Long hostId, String remotePath, MultipartFile file);
void uploadLocalFile(Long hostId, String remotePath, String localPath);
void downloadFile(Long hostId, String remotePath, String localPath);
void deleteFile(Long hostId, String remotePath);
void moveFile(Long hostId, String oldPath, String newPath);
List<FileInfo> listFiles(Long hostId, String remotePath);
void createDirectory(Long hostId, String remotePath);
void deleteDirectory(Long hostId, String remotePath, boolean recursive);
boolean exists(Long hostId, String remotePath);
FileInfo getFileInfo(Long hostId, String remotePath);
long tailFile(Long hostId, String remotePath, long offset, Charset charset, Consumer<String> lineConsumer);
CommandResult executeCommand(Long hostId, String command, long timeoutMs);
default CommandResult executeCommand(Long hostId, String command);
// 内部类：FileInfo{name,path,directory,size,lastModified,permissions,owner}
//         CommandResult{success,exitCode,output,error}
```

### SshTunnelService（v3.7.0 ADR-0009，SSH 本地端口转发）
```java
TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);          // 平台主机凭据
TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort); // 插件自带凭据
void close(TunnelHandle handle);   // 幂等：引用计数减至 0 才真正关闭
// record SshEndpoint(String host, int port, String user, String password, String privateKey)
//     便捷构造 SshEndpoint(host, user, password) → port=22；toString 已脱敏
// record TunnelHandle(String id, int localPort, String remoteHost, int remotePort, String ownerPluginId)
//     连 127.0.0.1:localPort 即连 remoteHost:remotePort；本地端口仅绑回环、OS 随机分配
// 去重键 (ownerPluginId, 凭据来源, remoteHost, remotePort)：同插件同目标复用句柄+计数，跨插件不共享
// 三层兜底关闭：close 归零 → 插件卸载强制清理 → 宿主删主机联动（仅平台凭据隧道）
// 详见 references/host_services.md §5；配套 configInfo.database 组装见 §6
```

### ScheduleService（v3.8.0 ADR-0011，定时计划编程式服务，注入子容器）
```java
String create(ScheduleCreateRequest request);          // 返回计划ID，source/pluginId自动绑定本插件
void   update(String id, ScheduleUpdateRequest req);   // name/cron/payload
void   enable(String id);  void disable(String id);    // disable 只停未来触发，进行中的 run 跑完
void   delete(String id);                              // 逻辑删除（声明式计划重载后不复活）
String trigger(String id);                             // 立即触发一次 → MANUAL run，遇重叠记 SKIPPED
ScheduleVO                       get(String id);
PageResult<ScheduleVO>           list(ScheduleQuery query);
PageResult<ScheduleRunVO>        listRuns(ScheduleRunQuery query);
List<TaskLog>                    getRunLogs(String runId);   // 时间正序，最多 500 条
// 所有操作强制本插件来源隔离（无法操作其他来源计划）
```

### ScheduledTaskHandler（v3.8.0 ADR-0011，定时任务处理器，@Component 注册，独立于 TaskHandler）
```java
String getKey();                       // 同 source 内唯一，计划经此引用
String getDisplayName();
default long getDefaultTimeoutMs();    // 默认 30 分钟；0=不超时
TaskResult execute(TaskContext ctx, TaskPayload payload) throws Exception;
// 复用任务中心 TaskContext：ctx.log(...) 写 run 日志；循环中检 isCancelled()/isTimeout()；reportProgress 已节流
// 无 isRetryable/getMaxRetryCount/getMutexKey/onSubmit：不重试、不互斥、无生命周期钩子
```

### ScheduledTaskDeclarationExtension（v3.8.0 ADR-0011，声明式默认计划，@Component）
```java
List<ScheduleDeclaration> getScheduleDeclarations();   // 宿主按 pluginId:key upsert；同插件内 key 唯一
// ScheduleDeclaration{key,name,handlerKey,cron,payload,enabled(default true)}
// upsert：用户改过(userModified=1)跳过、删过(is_deleted 墓碑)不复活、未改过随声明演进
```

### 计划/触发记录 DTO
```java
// ScheduleCreateRequest{name, handlerKey, cron, payload(Map), enabled}
// ScheduleUpdateRequest{name, cron, payload}   // enabled 走启停接口，handlerKey 创建后不可变
// ScheduleQuery{source, handlerKey, keyword, enabled, page, size}
// ScheduleRunQuery{scheduleId(必填), status, page, size}
// ScheduleVO{id,name,handlerKey,handlerName,cron,payload,enabled,paused,pauseReason,source,pluginId,
//            declarationKey,nextFireTime,lastRunStatus,lastRunTime,userModified,createTime,updateTime}
// ScheduleRunVO{id,scheduleId,scheduleName,triggerType(CRON/MANUAL),status(RUNNING/SUCCEEDED/FAILED/CANCELLED/SKIPPED),
//               payload,result,errorMessage,progress,progressMessage,startedAt,completedAt,durationMs,createTime}
```

## PluginManifestVO
```
pluginId, gameCode, gameName, version, description, icon, frontendEntry
frontend : FrontendConfig{entry, routes[], menus[], assets[]}
api      : ApiConfig{basePath, endpoints[]}
extensions: Map<String,Object>   // getManifest() 原始数据（features 已废弃，ADR-0001）
MenuConfig{title, path, icon, parent, order, requireInstance}
// MenuConfig.requireInstance 由插件 PluginMenuDeclaration 声明，宿主仅补全 null→true
```

## PluginConstants
```
FRAMEWORK_API_PREFIX          = /pf4j
PLUGIN_RESOURCE_URL_PREFIX    = /api/pf4j/plugin
PLUGIN_API_BASE_TEMPLATE      = /api/plugin/{gameCode}
PLUGIN_FRONTEND_ENTRY_TEMPLATE= /api/pf4j/plugin/{gameCode}/ui/{entry}
DEFAULT_FRONTEND_ENTRY        = index.html
DEFAULT_ICON                  = assets/icon.png
STATIC_RESOURCE_CACHE_DAYS    = 7
plugin.properties keys: plugin.id / plugin.class / plugin.version / plugin.gameCode / plugin.basePackage
```

## 路径速查
| 用途 | 路径 |
|---|---|
| 插件静态资源 | `/api/pf4j/plugin/{gameCode}/ui/**` |
| 插件 API | `/api/plugin/{gameCode}/**` |
| 插件清单 | `/api/pf4j/plugin/{gameCode}/manifest` |
| 插件管理 | `/api/pf4j/plugins/**` |
