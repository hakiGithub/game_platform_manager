# SDK 接口签名速查

> **权威来源**：`backend/plugin/src/main/java/com/gameplatform/plugin/`。在平台仓库内编码时以源码为准，本文件是跨项目场景的离线快照，签名有疑义先对源码。本文件只回答"方法长什么样"；实现约束、语义与示例见各主题文件。
> 当前对齐版本：v3.11.0（ADR-0001 菜单归属权迁移 / ADR-0009 平台能力三项扩展 / ADR-0011 定时任务体系 / ADR-0029 部署扩展声明）

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
default List<DeployVersionDeclaration> getDeployVersions(String deployType);          // v3.11.0 ADR-0029：静态版本目录（默认空列表）
default List<DeployExtensionStepDeclaration> getDeployExtensionSteps(DeployExtensionContext ctx); // v3.11.0：按实例动态算步骤集（默认空列表）
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

**宿主校验**：`path` 非空且同插件内唯一（违者抛 `IllegalStateException`）、`requireInstance` null 补全为 true；宿主**不预置任何默认菜单**。字段语义与完整加载链路见 `references/extension-and-menus.md` §6/§8。

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
实现约束（无状态、取消/超时检查、互斥键语义、maxRetryCount 选取）见 `references/async-tasks.md`。

### 部署扩展声明类型（v3.11.0 ADR-0029，包 `com.gameplatform.plugin.extension.deploy`）

```java
// 版本目录条目（静态，无实例上下文也可读）
public record DeployVersionDeclaration(String versionId, String displayName, String imageTag,
                                       Boolean defaultEntry,
                                       List<PatchStepDeclaration> patches,
                                       List<ScriptStepDeclaration> scripts) {}

// 步骤集公共上界：sealed，两个 permitted 实现就是下面那两个 record（故必须同包）
public sealed interface DeployExtensionStepDeclaration
        permits PatchStepDeclaration, ScriptStepDeclaration {
    String label();      // 展示位
    boolean fatal();     // 致命性；PRD 口径缺省致命，但原始 boolean 的 Java 缺省是 false ⇒ 声明须显式写 true
    StepKind kind();     // 执行器分派位（Java 17 无 pattern-matching switch）
}

public record PatchStepDeclaration(String label, String url, String targetPath, String sha256,
                                   String includePattern, String format, boolean fatal)
        implements DeployExtensionStepDeclaration {}

public record ScriptStepDeclaration(String label, String content, String url, String sha256,
                                    ScriptPosition position, boolean fatal, Long timeoutMs)
        implements DeployExtensionStepDeclaration {}

public enum StepKind { PATCH, SCRIPT }
public enum ScriptPosition { HOST, CONTAINER }   // CONTAINER 本期由主应用校验期判不合法

// 动态步骤集入口的上下文
public record DeployExtensionContext(Long instanceId, String gameCode, String deployType,
                                     String selectedVersionId, Map<String, Object> configInfo) {}
```

**要点**（语义权威是 `docs/design/MERC-3/design.md` §16.2 / PRD §8.1～§8.3，此处只记形状与坑）：
- 步骤集解析只有一条顺序：`getDeployExtensionSteps(ctx)` 非空 → 用它；否则取所选目录条目的 `patches` 拼接 `scripts`；都空 → 不进入扩展阶段。
- 拼接**必须带显式类型见证**：`Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList()`。不带见证在 javac 17 下编译失败（`List<INT#1>无法转换为List<DeployExtensionStepDeclaration>`），因为泛型不变、两个 `? extends T` 各自推导后取成交类型。
- 版本目录**不挂进 `getDeployConfigs()`**：那条整节替换通道只作用于 VO 读取路径，不作用于部署执行路径，挂上去会得到「向导看得见、部署看不见」的目录（design.md §16.1）。
- 校验全在主应用侧、声明读取期逐条做，任一不合规即**整目录**判不可用（禁止部分采纳）；SDK 层不做校验、不含游戏语义。
- `imageTag` 的落位机制是「该 deployType 声明了保留变量 `PLATFORM_IMAGE_TAG` ⇒ 值由平台写入 `.env`」，没有第二种落位方式；模板缺 `${PLATFORM_IMAGE_TAG` 占位而条目声明了 `imageTag` ⇒ 不合法。
- 本期只有 `docker-compose` / `linuxgsm-docker` 两类 deployType 允许声明扩展步骤，集合外带步骤或 `imageTag` 即不合法。

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
void uploadLocalFile(long instanceId, String relativePath, String localPath);   // default，无进度回调
void downloadFile(long instanceId, String relativePath, String localPath);      // default
void uploadLocalFile(long instanceId, String relativePath, String localPath, FileTransferProgressCallback callback); // v3.10.0
void downloadFile(long instanceId, String relativePath, String localPath, FileTransferProgressCallback callback);   // v3.10.0
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
void uploadLocalFile(Long hostId, String remotePath, String localPath);   // default
void downloadFile(Long hostId, String remotePath, String localPath);      // default
void uploadLocalFile(Long hostId, String remotePath, String localPath, FileTransferProgressCallback callback); // v3.10.0
void downloadFile(Long hostId, String remotePath, String localPath, FileTransferProgressCallback callback);     // v3.10.0
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

### PatchInstallService（ADR-0006；v3.11.0 ADR-0029 新增 `installSync`）
```java
String install(PatchInstallRequest request);                       // 异步提交任务中心，返回 taskId
default void installSync(PatchInstallRequest request,
                         PatchInstallProgressListener listener);   // v3.11.0：调用方线程内阻塞
HostCapabilities probeHost(Long hostId);
```
```java
// v3.11.0 新接口，形状照宿主执行器既有回调（三个方法）
public interface PatchInstallProgressListener {
    void onProgress(int percent, String message);
    void onLog(String message);
    boolean isCancelled();
}
```

- `install()` 的签名与行为**未变**；但它是异步提交路径，载荷只透传 `instanceId/url/targetPath/format/sha256`
  ⇒ 请求对象上的 `includePattern` / `headers` 经这条路**不生效**。需要 `includePattern` 用 `installSync`
  （对象引用直传执行器，不经 JSON 往返）。该丢字段缺陷本期不修（受影响方只有既有 `install()` 调用方）。
- `installSync` 与 `install` 走**同一个**宿主执行器、同一份代码路径（不是第二套补丁实现）：
  全局并发闸 3、可重试错误自动重试 2 次（5s/20s 退避）、SSH 600 s 一并继承；
  **每宿主机互斥**由宿主实现承任务中心同一个内存键 `PATCH_INSTALL:<hostId>`（等待预算 600 s、`finally` 释放）
  ⇒ 调用方不得在外面再套一层重试。
- 默认实现抛 `UnsupportedOperationException("该宿主未提供同步补丁通道")`，不静默成功；
  宿主未实现时属实现缺失，调用方按致命失败处置。
- `isCancelled()` 在部署扩展阶段固定传 `() -> false`（部署主流程不经任务中心，没有取消入口）。

### SshTunnelService（v3.7.0 ADR-0009，SSH 本地端口转发）
```java
TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);          // 平台主机凭据
TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort); // 插件自带凭据
void close(TunnelHandle handle);   // 幂等：引用计数减至 0 才真正关闭
// record SshEndpoint(String host, int port, String user, String password, String privateKey)
//     便捷构造 SshEndpoint(host, user, password) → port=22；toString 已脱敏
// record TunnelHandle(String id, int localPort, String remoteHost, int remotePort, String ownerPluginId)
//     连 127.0.0.1:localPort 即连 remoteHost:remotePort；本地端口仅绑回环、OS 随机分配
```
去重键 / 引用计数 / 三层兜底关闭 / 会话钉住等生命周期规则与 configInfo.database 组装见 `references/host-services.md` §5-6。

### RconService（v3.10.0 ADR-0016，RCON 宿主能力）
```java
String  executeCommand(long instanceId, String command);                    // 默认读超时
String  executeCommand(long instanceId, String command, Duration timeout);  // null = 默认超时
boolean testConnection(long instanceId);                                   // 建连 + 认证
// 端点解析只认标准键 configInfo.rconPort(缺省27015)/rconPassword；密码不可由插件指定
// 每次执行自动携带插件 ID 写审计日志；命令语义（status 解析等）插件自理
// 实例不存在/端点不可达/通信失败抛 BusinessException
```

### FileTransferProgressCallback（v3.10.0，文件传输进度回调）
```java
void onStart(long totalBytes);                            // totalBytes 未知为 -1
void onProgress(long bytesTransferred, long totalBytes);  // 频率不保证（可能被节流）
void onComplete();                                        // 成功，保证最终一次
void onError(Throwable error);                            // 失败，保证最终一次
// 同步回调勿做耗时操作；回调抛异常即中止传输（可作取消）；Docker 部署仅覆盖 SFTP 段
```

### InstanceInfoProvider / InstanceDynamicInfo（v3.10.0 ADR-0017，@Component 注册非 ExtensionPoint）
```java
InstanceDynamicInfo getInstanceInfo(long instanceId);   // null = 本次不可知（主应用降级，不落库）
// record InstanceDynamicInfo(Integer playerCount, Integer maxPlayerCount, Map<String,Object> extras)
// 便捷构造：ofPlayerCount(count) / ofPlayers(count, max)
// 主应用负责 15s TTL 缓存 + 列表 3s 查询预算；extras 仅透传到实例详情 VO
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
重叠 SKIPPED / 停机不补跑 / 插件生命周期联动语义见 `references/scheduled-tasks.md` §5-7。

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

常用三条：插件 API `/api/plugin/{gameCode}/**`；插件静态资源 `/api/pf4j/plugin/{gameCode}/ui/**`；清单 `/api/pf4j/plugin/{gameCode}/manifest`。完整路径常量表与来源（`PluginConstants` 各字段）见 `references/checklist.md` §1。
