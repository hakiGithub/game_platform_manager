# Phase 4: L4D2 下载体系实施计划

> **主 plan**：`docs/superpowers/plans/2026-07-19-l4d2-server-next-port-plan.md`
> **设计文档**：`docs/superpowers/specs/2026-07-19-l4d2-server-next-port-design.md` §4 模块 10 + §3.6
> **当前阶段**：Phase 4（Download System）— 覆盖 spec §4 模块 10（URL 下载器 + Workshop 下载器）

---

## 1. 概述

Phase 4 实现 L4D2 下载体系的 2 个核心模块：

| Task | 模块 | 后端 | 前端 |
|------|------|------|------|
| 4.1 | URL 下载器 | `DownloadController` + `DownloadService` + `DownloadTaskResource` 扩展 | - |
| 4.2 | Workshop 下载器 | `WorkshopDownloadService` + `SteamApiClient` + 链接解析 | - |
| 4.3 | 前端 Download.vue | - | URL/Workshop Tab + 任务列表 + 实时进度 |
| 4.4 | 集成验证 | 后端编译 + 测试 | 前端构建 + 工作树干净 |

**源项目对齐**：`backend/controller/download.go` + `backend/logic/link_parser.go` + `backend/logic/workshop.go`

---

## 2. 关键架构约束

> **必读**：以下约束来自 Phase 0-3 经验。

### 2.1 模块依赖

- `plugin-l4d2-core` **禁止依赖** `core` 模块
- 通过以下接口获取宿主能力：
  - `InstanceQueryService.getInstanceById(Long)` → `InstanceVO`
  - `FileAccessService`：`uploadLocalFile / downloadFile / deleteFile / listFiles`
  - `ExtensionClient`：扩展资源 CRUD
  - `ExternalHttpClient`（已有，含 `download(url, filename, referer, callback, cancelToken)`）

### 2.2 ExtensionClient API（与 Phase 3 一致）

```java
extensionClient.create(resource);                  // 返回 void，框架填充 id
extensionClient.getById(Klass.class, id);          // 返回 Optional<T>
extensionClient.update(resource);                  // 乐观锁更新
extensionClient.deleteById(Klass.class, id);
ListOptions opts = ListOptions.builder()
    .specFilter("$.instanceId", "=", instanceId)
    .build();
extensionClient.list(Klass.class, opts);
```

### 2.3 DownloadTaskResource 持久化策略

**双层存储**：
1. **内存**：`ConcurrentHashMap<String taskId, DownloadTaskRuntime>`，保存活跃任务的 `Future`、`CancelToken`、最近一次 VO 快照
2. **DB**：`DownloadTaskResource`（已存在），通过 `ExtensionClient` 持久化任务记录，提供历史查询

**生命周期**：
- 创建任务 → 写入 DB（status=PENDING） + 写入内存
- 异步下载 → 定期更新 DB（progress/status）
- 完成或失败 → 更新 DB（final status + completeTime）
- 取消 → 设置内存 cancel 标志 + 更新 DB（status=CANCELLED）
- 启动时清理：所有 IN_PROGRESS 状态记录标记为 FAILED（"服务重启中断"）

### 2.4 前端约束

- **所有前端代码必须放在** `backend/plugin-l4d2/frontend/` 内
- 构建产物通过 `vite.config.ts` 的 `outDir` 打入 core JAR

### 2.5 前端 API 调用模式

```typescript
import { get, post, del } from './request'
export const downloadApi = {
  listTasks: (instanceId: number) => get<DownloadTaskVO[]>('/download/tasks', { instanceId }),
  createUrlTask: (data: UrlDownloadDTO) => post<void>('/download/url', data),
}
```

### 2.6 Store 字段名

```typescript
const instanceId = computed(() => store.instanceInfo?.instanceId)  // 不是 .id
```

---

## 3. 关键设计决策

### 3.1 Workshop 解析策略

源项目 `logic/workshop.go` 使用私有解析服务 `l4d2-workshop-parse.laoyutang.cn`，**我们改为直接调用 Steam Web API**（按 spec 设计）：

- 端点：`https://api.steampowered.com/IPublishedFileService/GetDetails/v1/`
- 参数：`key={api_key}&appid=550&publishedfileids[0]={id}&includechildren=true`
- 响应字段：`publishedfiledetails[].file_url`、`title`、`filename`、`file_size`、`preview_url`、`children`

**降级策略**：
1. 优先用 Steam API 返回的 `file_url`（若有）
2. 若 `file_url` 为空：任务状态置为 `PENDING_MANUAL`，记录元信息到 `DownloadTaskSpec.remark`，提示用户配置代理 URL（`L4D2Config.workshop.proxyUrl`）
3. 若 `L4D2Config.workshop.proxyUrl` 已配置，作为 referer 传给 URL 下载

### 3.2 下载并发控制

- 全局 `Semaphore(3)` 限制并发下载数（与 PluginStoreService 一致）
- 任务列表线程安全：`ConcurrentHashMap` + VO 不可变快照

### 3.3 磁盘空间检查

- 下载前检查临时目录可用空间
- 阈值 90%（与 ChunkUploadService 一致）：`new File(System.getProperty("java.io.tmpdir")).getUsableSpace() / getTotalSpace() < 0.1` 时拒绝

### 3.4 VPK Magic 检测（对齐源项目 download.go:312-335）

下载完成后检测前 4 字节是否为 `0x34 0x12 0xaa 0x55`（小端 `0x55AA1234`）：
- 若是 VPK 且文件名未以 `.vpk` 结尾 → 重命名加 `.vpk` 后缀
- 若是 VPK 且 `targetPath` 未指定 → 自动设置 `targetPath` 为 `addons/`

### 3.5 文件名清洗（对齐源项目 link_parser.go:126-151）

`FilenameSanitizeUtil.sanitize(filename)`：
- 去除 `\0`、替换 `\` 为 `/`、取 basename
- 替换非法字符 `< > : " / \ | ? *` 为 `_`
- 限长 180 字符（保留扩展名）
- 空或 `.`/`/` 返回 `null`

### 3.6 URL 切分（对齐源项目 download.go:447-463）

支持在单个 URL 输入框中粘贴多个 URL（空白/换行分隔）：
- 正则 `(https?://[^\s\n\r]+)` 匹配所有 http(s) URL
- 每个 URL 创建独立任务

### 3.7 速度计算（对齐源项目 download.go:164-185）

- 5 秒滑动窗口：`speed = (currentBytes - lastSecondBytes) / 5`
- 通过 `ExternalHttpClient.ProgressCallback` 累计 `downloadedBytes`
- 用 `ScheduledExecutorService` 每 5 秒计算一次速度，更新内存 VO + DB（合并写入）

### 3.8 清理任务

`@Scheduled(fixedRate = 3600_000)`：每小时扫描 DB 中所有 `IN_PROGRESS` 状态超过 6 小时的记录，标记为 `FAILED`（remark="任务超时未完成"）。

---

## 4. 后端实施任务

### Task 4.1: URL 下载器（DownloadController + DownloadService + DownloadTaskResource 扩展）

#### 4.1.1 扩展 DownloadTaskSpec

**文件**：`extension/DownloadTaskSpec.java`（已存在）

**新增字段**：
```java
/** 任务 ID（雪花 ID，作为 Resource name） */
private String taskId;

/** 任务类型：URL / WORKSHOP */
private String taskType;

/** Referer 头 */
private String referer;

/** Workshop ID（仅 Workshop 任务） */
private String workshopId;

/** Workshop 标题（仅 Workshop 任务） */
private String workshopTitle;

/** 预览图 URL */
private String previewUrl;
```

**注意**：原 `taskUrl` 字段保留（URL 任务的下载链接）；原 `taskStatus` 字段改用字符串状态码（与 PluginStoreService 一致）：`PENDING / DOWNLOADING / COMPLETED / FAILED / CANCELLED / PENDING_MANUAL`。

#### 4.1.2 新建 DownloadTaskVO

**文件**：`vo/DownloadTaskVO.java`

字段：taskId / instanceId / taskType / taskUrl / filename / fileSize / downloadedSize / progress / downloadSpeed / status / errorMessage / targetPath / workshopId / workshopTitle / previewUrl / startTime / completeTime / formattedSpeed / formattedSize

**`formattedSpeed` 与 `formattedSize`** 由后端计算（对齐源项目 download.go:417-445）：
- speed < 1024 → `B/s`
- speed < 1024*1024 → `KB/s`
- speed < 1024*1024*1024 → `MB/s`
- 否则 → `GB/s`

#### 4.1.3 新建 DownloadTaskRuntime（内部类）

**文件**：`service/DownloadService.java` 内部静态类

```java
static class DownloadTaskRuntime {
    DownloadTaskVO vo;                          // 内存快照
    CompletableFuture<Void> future;             // 异步任务
    volatile boolean cancelled;                 // 取消标志
    volatile long lastSecondBytes;              // 速度计算
    volatile long lastSpeedUpdate;              // 上次速度更新时间戳
    ScheduledFuture<?> speedTimer;              // 速度定时器
}
```

#### 4.1.4 新建 FilenameSanitizeUtil

**文件**：`util/FilenameSanitizeUtil.java`

```java
public static String sanitize(String filename);        // 清洗文件名
public static String sanitizePath(String path);        // 清洗相对路径
public static boolean isSupportedExtension(String fn); // .vpk/.zip/.rar/.7z
```

#### 4.1.5 实现 DownloadService

**文件**：`service/DownloadService.java`

**核心方法**：
```java
// 创建 URL 下载任务（支持多 URL 切分）
public List<String> createUrlTasks(UrlDownloadDTO dto);

// 创建 Workshop 下载任务（由 WorkshopDownloadService 调用）
public String createWorkshopTask(WorkshopDownloadDTO dto);

// 异步执行下载（私有）
private void runDownload(DownloadTaskRuntime runtime);

// 任务列表
public List<DownloadTaskVO> listTasks(Long instanceId, String status);

// 任务详情
public DownloadTaskVO getTask(String taskId);

// 取消任务
public void cancel(String taskId);

// 删除任务记录（仅终态）
public void delete(String taskId);

// 启动时清理（@PostConstruct）
private void cleanupOnStartup();

// 定时清理超时任务（@Scheduled）
@Scheduled(fixedRate = 3600_000)
private void cleanupTimeoutTasks();
```

**`createUrlTasks` 流程**：
1. 校验 instanceId 存在
2. 磁盘空间检查（< 10% 可用 → 抛 `L4D2PluginException`）
3. 用 `splitURLString(dto.getUrl())` 切分多 URL
4. 对每个 URL：
   - 生成 `taskId = IdUtil.getSnowflakeNextIdStr()`
   - 构造 `DownloadTaskResource`（spec 含 instanceId/taskId/taskType=URL/taskUrl/filename/referer/targetPath/status=PENDING/startTime=now/maxRetry=3）
   - `extensionClient.create(resource)`
   - 构造 `DownloadTaskRuntime` + 放入内存 Map
   - `CompletableFuture.runAsync(() -> runDownload(runtime))`
5. 返回 taskId 列表

**`runDownload` 流程**（对齐源项目 download.go:188-344）：
1. `semaphore.acquire()` → try-finally release
2. 检查取消标志
3. 更新 status=DOWNLOADING
4. 启动速度计算定时器（ScheduledExecutorService，5s 间隔）
5. 调用 `ExternalHttpClient.download(url, filename, referer, callback, cancelToken)`：
   - callback：更新 VO.downloadedBytes + progress + 每 100ms 同步到 DB
   - cancelToken：返回 `runtime.cancelled`
6. 检查取消标志（下载完成后）
7. VPK magic 检测：若是 VPK 且未以 .vpk 结尾 → 重命名临时文件
8. 推断 targetPath：
   - 若是 VPK 且 targetPath 为空 → `addons/`
   - 若是 VPK 且 targetPath 已指定 → 校验合法性
   - 否则用原始 targetPath
9. `fileAccessService.uploadLocalFile(hostId, targetPath + filename, tempFile.getAbsolutePath())`
10. 删除临时文件
11. 更新 status=COMPLETED + progress=100 + completeTime=now
12. 异常时：更新 status=FAILED + errorMessage + completeTime
13. 取消时：删除临时文件 + 更新 status=CANCELLED

**`runDownload` 中的 DB 同步策略**：
- 创建时：`extensionClient.create(resource)` 一次
- 状态变更时：`extensionClient.update(resource)`（每次重新读取以保证乐观锁）
- 速度/进度：合并写入（每 5s 一次，避免 DB 压力）

#### 4.1.6 实现 DownloadController

**文件**：`controller/DownloadController.java`

**端点**：
```java
@PostMapping("/url")                                                          // 创建 URL 下载任务
public Result<List<String>> createUrlTask(@Valid @RequestBody UrlDownloadDTO dto);

@PostMapping("/workshop")                                                     // 创建 Workshop 下载任务
public Result<List<String>> createWorkshopTask(@Valid @RequestBody WorkshopDownloadDTO dto);  // 委托 WorkshopDownloadService

@PostMapping("/parse-link")                                                   // 解析下载链接（预览）
public Result<LinkParseResultVO> parseLink(@Valid @RequestBody ParseLinkDTO dto);

@PostMapping("/parse-workshop")                                               // 解析 Workshop 链接（预览）
public Result<WorkshopParseResultVO> parseWorkshop(@Valid @RequestBody ParseWorkshopDTO dto);

@GetMapping("/tasks")                                                         // 任务列表
public Result<List<DownloadTaskVO>> listTasks(
        @RequestParam Long instanceId,
        @RequestParam(required = false) String status);

@GetMapping("/tasks/{taskId}")                                                // 任务详情
public Result<DownloadTaskVO> getTask(@PathVariable String taskId);

@PostMapping("/tasks/{taskId}/cancel")                                        // 取消任务
public Result<Void> cancelTask(@PathVariable String taskId);

@DeleteMapping("/tasks/{taskId}")                                             // 删除任务记录
public Result<Void> deleteTask(@PathVariable String taskId);
```

#### 4.1.7 DTO 文件

**`dto/UrlDownloadDTO`**：instanceId / url / filename? / referer? / targetPath?
**`dto/WorkshopDownloadDTO`**：instanceId / workshopUrlOrId
**`dto/ParseLinkDTO`**：url
**`dto/ParseWorkshopDTO`**：url

#### 4.1.8 测试用例

**`DownloadServiceTest`**（重点覆盖并发安全）：
- `create_url_task_single` — 单 URL 任务创建
- `create_url_task_multiple` — 多 URL 切分创建
- `create_url_task_disk_full` — 磁盘空间不足拒绝
- `cancel_task_while_pending` — 取消 PENDING 任务
- `cancel_task_while_downloading` — 取消下载中任务（mock ExternalHttpClient）
- `list_tasks_filter_by_instance` — 按实例过滤
- `list_tasks_filter_by_status` — 按状态过滤
- `cleanup_on_startup` — 启动时清理 IN_PROGRESS 记录
- `cleanup_timeout_tasks` — 超时任务清理
- `concurrent_task_list_access` — 并发安全（参考源项目 download_concurrency_test.go）

**`FilenameSanitizeUtilTest`**：
- `sanitize_normal` — 普通文件名
- `sanitize_with_path_traversal` — `../etc/passwd` → `passwd`
- `sanitize_with_invalid_chars` — `a<b>c:d"e/f\g|h?i*j` → `a_b_c_d_e_f_g_h_i_j`
- `sanitize_too_long` — 200 字符截断到 180
- `sanitize_empty` — 空字符串返回 null
- `is_supported_extension` — .vpk/.zip/.rar/.7z

---

### Task 4.2: Workshop 下载器（WorkshopDownloadService + SteamApiClient + 链接解析）

#### 4.2.1 新建 SteamApiClient

**文件**：`util/SteamApiClient.java`

**职责**：封装 Steam Web API 调用

**核心方法**：
```java
// IPublishedFileService/GetDetails
public List<WorkshopDetail> getPublishedFileDetails(List<String> publishedFileIds);

// IPlayerService/GetOwnedGames（Phase 5 用，本 Task 仅声明）
public PlaytimeInfo getOwnedGames(String steamId, int appid);
```

**`WorkshopDetail` record**：
```java
record WorkshopDetail(
    String publishedFileId,
    int result,                  // 1=成功
    String title,
    String fileName,
    long fileSize,
    String fileUrl,
    String previewUrl,
    List<String> childrenIds     // 合集子项 ID
) {}
```

**API 请求格式**（POST form-urlencoded）：
```
POST https://api.steampowered.com/IPublishedFileService/GetDetails/v1/
key={api_key}
appid=550
publishedfileids[0]={id1}
publishedfileids[1]={id2}
includechildren=true
```

**响应解析**：`response.publishedfiledetails[]`

**API key 缺失处理**：抛 `L4D2PluginException("STEAM_API_KEY 未配置")`

#### 4.2.2 新建 WorkshopDownloadService

**文件**：`service/WorkshopDownloadService.java`

**核心方法**：
```java
// 解析 Workshop URL/ID 为可下载项列表
public WorkshopParseResultVO parseWorkshop(String workshopUrlOrId);

// 创建 Workshop 下载任务（支持合集，返回多任务 ID）
public List<String> createWorkshopTasks(WorkshopDownloadDTO dto);
```

**`parseWorkshop` 流程**（对齐源项目 workshop.go:38-85）：
1. `parseWorkshopId(workshopUrlOrId)` → 提取数字 ID（>= 100000）
2. `steamApiClient.getPublishedFileDetails(List.of(id))` → 取首个 detail
3. 若 detail.children 非空：
   - 批量查询 children 详情（`getPublishedFileDetails(childrenIds)`）
   - 合并：父项（若 file_url 非空）+ 子项
4. 过滤无效项（result != 1 或 publishedFileId 为空）
5. 去重 + 质量评分排序（对齐源项目 normalizeWorkshopItems）

**`createWorkshopTasks` 流程**：
1. `parseWorkshop(dto.getWorkshopUrlOrId())` → 获取 items 列表
2. 对每个 item：
   - 若 `fileUrl` 非空：
     - 调用 `downloadService.createWorkshopTask(...)` 创建任务
   - 若 `fileUrl` 为空：
     - 创建 `PENDING_MANUAL` 状态任务
     - 在 `remark` 中记录 `"Steam API 未返回 file_url，请配置代理 URL：plugin.l4d2.workshop.proxy-url"`
3. 返回 taskId 列表

#### 4.2.3 新建 LinkParser（链接解析工具）

**文件**：`util/LinkParser.java`

**职责**：通用链接解析（对齐源项目 link_parser.go）

**核心方法**：
```java
// 解析任意链接：先尝试 Workshop，再尝试普通 URL
public static LinkParseResult parse(String rawLink);

// 提取 Workshop ID（对齐源 workshop.go:87-120）
public static String parseWorkshopId(String url) throws L4D2PluginException;

// 校验 Workshop ID 有效性（数字且 >= 100000）
public static boolean isValidWorkshopId(String id);
```

**`LinkParseResult` record**：
```java
record LinkParseResult(
    String sourceType,    // "workshop" / "url" / "unknown"
    String sourceId,
    List<LinkParseItem> items
) {}

record LinkParseItem(
    String id,             // publishedFileId 或 URL hash
    String title,
    String filename,
    String fileSize,
    String fileUrl,
    String previewUrl,
    String referer,
    boolean supported,
    String disabledReason
) {}
```

#### 4.2.4 VO 文件

**`vo/WorkshopParseResultVO`**：sourceId / items（List<WorkshopItemVO>）
**`vo/WorkshopItemVO`**：publishedFileId / title / filename / fileSize / fileUrl / previewUrl / hasFileUrl
**`vo/LinkParseResultVO`**：sourceType / sourceId / items（List<LinkParseItemVO>）
**`vo/LinkParseItemVO`**：id / title / filename / fileSize / fileUrl / previewUrl / supported / disabledReason

#### 4.2.5 L4D2Config 扩展

**文件**：`config/L4D2Config.java`（已含 `Workshop` 内部类）

**修改**：
```java
@Data
public static class Workshop {
    private String downloadDir = "addons/";        // 下载目标目录
    private int maxConcurrent = 3;                  // Workshop 下载并发数（已存在）
    private String proxyUrl = "";                   // 已存在，作为 referer
    private long parseTimeoutMs = 30_000L;          // 新增：Steam API 超时
    private boolean allowManualProxy = true;        // 新增：是否允许 pending_manual
}
```

#### 4.2.6 测试用例

**`SteamApiClientTest`**（mock RestClient）：
- `get_details_single` — 单 ID 查询
- `get_details_with_children` — 合集查询
- `get_details_api_key_missing` — API key 缺失抛异常
- `get_details_steam_error` — Steam 返回非 200
- `get_details_invalid_response` — 响应格式错误

**`WorkshopDownloadServiceTest`**：
- `parse_workshop_url` — 从 URL 解析 ID
- `parse_workshop_id_only` — 纯数字 ID
- `parse_workshop_collection` — 合集展开
- `create_workshop_task_with_file_url` — 有 file_url 创建下载任务
- `create_workshop_task_without_file_url` — 无 file_url 创建 PENDING_MANUAL
- `create_workshop_task_batch` — 合集批量创建

**`LinkParserTest`**：
- `parse_workshop_url` — Workshop URL
- `parse_workshop_id` — 纯数字 ID
- `parse_invalid_link` — 无效链接
- `parse_workshop_id_from_sharedfiles` — `steamcommunity.com/sharedfiles/filedetails/?id=123456`
- `parse_workshop_id_from_workshop` — `steamcommunity.com/workshop/browse?id=123456`
- `is_valid_workshop_id` — 边界值（99999 / 100000 / 字母）

---

### Task 4.3: 前端 Download.vue + 进度展示

#### 4.3.1 前端 API 扩展

**文件**：`frontend/src/api/index.ts`（追加）

```typescript
export interface DownloadTaskVO {
  taskId: string
  instanceId: number
  taskType: 'URL' | 'WORKSHOP'
  taskUrl: string
  filename: string
  fileSize: number
  downloadedSize: number
  progress: number
  downloadSpeed: number
  status: 'PENDING' | 'DOWNLOADING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'PENDING_MANUAL'
  errorMessage?: string
  targetPath?: string
  workshopId?: string
  workshopTitle?: string
  previewUrl?: string
  startTime: string
  completeTime?: string
  formattedSpeed: string
  formattedSize: string
}

export interface UrlDownloadDTO {
  instanceId: number
  url: string
  filename?: string
  referer?: string
  targetPath?: string
}

export interface WorkshopDownloadDTO {
  instanceId: number
  workshopUrlOrId: string
}

export interface WorkshopItemVO {
  publishedFileId: string
  title: string
  filename: string
  fileSize: string
  fileUrl: string
  previewUrl: string
  hasFileUrl: boolean
}

export interface WorkshopParseResultVO {
  sourceId: string
  items: WorkshopItemVO[]
}

export const downloadApi = {
  createUrlTask: (data: UrlDownloadDTO) => post<string[]>('/download/url', data),
  createWorkshopTask: (data: WorkshopDownloadDTO) => post<string[]>('/download/workshop', data),
  parseWorkshop: (url: string) => post<WorkshopParseResultVO>('/download/parse-workshop', { url }),
  listTasks: (instanceId: number, status?: string) =>
    get<DownloadTaskVO[]>('/download/tasks', { instanceId, status }),
  getTask: (taskId: string) => get<DownloadTaskVO>(`/download/tasks/${taskId}`),
  cancelTask: (taskId: string) => post<void>(`/download/tasks/${taskId}/cancel`),
  deleteTask: (taskId: string) => del<void>(`/download/tasks/${taskId}`),
}
```

#### 4.3.2 实现 Download.vue

**文件**：`frontend/src/views/Download.vue`

**布局**：
```
┌─────────────────────────────────────────────────┐
│ [URL 下载] [Workshop 下载]                       │ Tab 切换
├─────────────────────────────────────────────────┤
│ Tab 1: URL 下载表单                              │
│   URL（textarea，多 URL 用换行分隔）              │
│   文件名（可选）                                  │
│   Referer（可选）                                 │
│   目标路径（可选，默认 addons/）                  │
│   [开始下载]                                      │
├─────────────────────────────────────────────────┤
│ Tab 2: Workshop 下载表单                         │
│   Workshop URL 或 ID                             │
│   [解析预览]                                      │
│   ┌─ 解析结果 ─────────────────────────────┐    │
│   │ 标题 / 文件名 / 大小 / 是否可下载        │    │
│   │ [全选] [批量下载]                       │    │
│   └─────────────────────────────────────────┘    │
├─────────────────────────────────────────────────┤
│ 任务列表（共用，2 秒轮询）                        │
│ ┌─taskId─类型─文件名─进度─速度─状态─操作────┐    │
│ │ xxx   URL   a.vpk   45% 1.2MB/s 下载中 [取消] │
│ │ yyy   WS    b.vpk   --    --   待手动  [删除] │
│ └──────────────────────────────────────────────┘ │
│ [刷新] [清理已完成]                              │
└─────────────────────────────────────────────────┘
```

**关键实现**：
- **Tab 切换**：`<el-tabs>` + `v-model="activeTab"`
- **URL 表单**：`<el-input type="textarea">` 多行
- **Workshop 预览**：调用 `parseWorkshop` → 弹窗显示 items 表格 → 用户勾选 → 调用 `createWorkshopTask`
- **任务列表轮询**：
  - `onMounted` 启动 `setInterval(loadTasks, 2000)`
  - `onUnmounted` 清理定时器
  - 有 `DOWNLOADING/PENDING` 状态时持续轮询，全部终态后停止
- **进度展示**：`<el-progress :percentage="task.progress" :status="progressStatus">`
- **状态徽章**：`<el-tag :type="tagType">{{ statusLabel }}</el-tag>`
  - PENDING: info
  - DOWNLOADING: primary
  - COMPLETED: success
  - FAILED: danger
  - CANCELLED: warning
  - PENDING_MANUAL: warning
- **操作按钮**：
  - 取消（仅 PENDING/DOWNLOADING）
  - 删除（仅终态）
  - 重试（仅 FAILED/CANCELLED）— 调用 `createUrlTask` 用相同参数
- **格式化速度/大小**：直接用后端返回的 `formattedSpeed / formattedSize`

#### 4.3.3 路由配置

**文件**：`frontend/src/router/index.ts`

新增路由：
```typescript
{
  path: '/download',
  name: 'Download',
  component: () => import('../views/Download.vue'),
  meta: { title: '下载管理', icon: 'Download' }
}
```

#### 4.3.4 测试

**手动验证**（无单元测试，依赖后端）：
- URL 下载：粘贴一个 VPK 直链 → 任务创建 → 进度更新 → 完成
- Workshop 下载：粘贴 Workshop URL → 解析预览 → 选择下载
- 多 URL 切分：粘贴 3 个 URL → 创建 3 个任务
- 取消任务：下载中点击取消 → 状态变 CANCELLED
- 删除任务：终态任务删除 → 列表更新

---

### Task 4.4: Phase 4 集成验证

#### 4.4.1 后端验证

```bash
cd backend
mvn clean install -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests
mvn test -pl plugin-l4d2/plugin-l4d2-core
```

**预期**：
- 编译成功
- 所有测试通过（Phase 3 测试 148 + Phase 4 新增约 25 = 173+）
- 无未使用 import 警告

#### 4.4.2 前端验证

```bash
cd backend/plugin-l4d2/frontend
npm run build
```

**预期**：
- TypeScript 编译通过
- 产物输出到 `plugin-l4d2-core/src/main/resources/ui/`
- 工作树干净

#### 4.4.3 集成验证清单

- [ ] 后端：所有端点可访问（Swagger UI）
- [ ] 后端：URL 下载流程完整（创建 → 进度 → 完成）
- [ ] 后端：Workshop 下载流程完整（解析 → 创建 → 完成 或 PENDING_MANUAL）
- [ ] 后端：并发安全（3 个并发任务不冲突）
- [ ] 后端：取消任务能立即停止下载
- [ ] 后端：磁盘空间不足时拒绝创建
- [ ] 后端：VPK magic 检测正确（自动加 .vpk 后缀 + 改 targetPath）
- [ ] 前端：Download.vue 页面渲染正常
- [ ] 前端：任务列表 2s 轮询更新
- [ ] 前端：进度条和状态徽章正确显示
- [ ] 前端：取消/删除按钮工作
- [ ] 前端：Workshop 解析预览弹窗正常
- [ ] 前端：build 产物正确打入 core JAR

---

## 5. 实施顺序

按依赖关系：

1. **Task 4.1**（URL 下载器）— 先完成基础设施
   - 4.1.1 DownloadTaskSpec 扩展
   - 4.1.2 DownloadTaskVO
   - 4.1.4 FilenameSanitizeUtil
   - 4.1.3 DownloadTaskRuntime
   - 4.1.5 DownloadService
   - 4.1.6 DownloadController
   - 4.1.7 DTO
   - 4.1.8 测试

2. **Task 4.2**（Workshop 下载器）— 依赖 Task 4.1 的 DownloadService
   - 4.2.1 SteamApiClient
   - 4.2.3 LinkParser
   - 4.2.2 WorkshopDownloadService
   - 4.2.4 VO
   - 4.2.5 L4D2Config 扩展
   - 4.2.6 测试

3. **Task 4.3**（前端）— 依赖 Task 4.1 + 4.2 的 API
   - 4.3.1 前端 API
   - 4.3.2 Download.vue
   - 4.3.3 路由

4. **Task 4.4**（集成验证）

---

## 6. 风险与缓解

### 6.1 Steam API 不可用

**风险**：Steam Web API 在国内可能无法直接访问，或 API key 未配置。

**缓解**：
- API key 缺失时返回明确错误：`"STEAM_API_KEY 未配置，请在 application.yml 中设置 plugin.l4d2.steam.api-key"`
- 网络超时（30s）后返回明确错误：`"Steam API 不可达，请检查网络或配置代理"`
- 文档提示用户可通过 `plugin.l4d2.workshop.proxy-url` 配置反向代理

### 6.2 大文件下载内存占用

**风险**：下载 10GB+ 文件时，临时文件可能耗尽磁盘。

**缓解**：
- 创建任务前检查磁盘可用空间 < 10% 拒绝
- 下载中不缓存到内存，直接流式写入临时文件（`ExternalHttpClient.download` 已实现）
- 完成后立即删除临时文件

### 6.3 任务状态不一致

**风险**：内存 VO 与 DB Resource 状态可能不一致（如服务重启）。

**缓解**：
- 启动时 `@PostConstruct cleanupOnStartup()`：所有 IN_PROGRESS 记录标记为 FAILED
- 关键状态变更（PENDING → DOWNLOADING → COMPLETED/FAILED）同步写 DB
- 进度/速度等高频字段每 5s 合并写入 DB（不阻塞下载）

### 6.4 并发任务取消竞态

**风险**：取消信号与下载完成的竞态条件。

**缓解**：
- 取消时设置 `runtime.cancelled = true`（volatile）
- 下载循环每 8KB 检查一次 cancelToken
- 完成后再次检查 cancelled 标志，若已取消则删除已下载文件 + 更新状态为 CANCELLED

---

## 7. 完成标准

- [ ] 后端：所有 Task 4.1 + 4.2 测试通过（173+ tests, 0 failures）
- [ ] 后端：`mvn clean install -pl plugin-l4d2/plugin-l4d2-core -am` 成功
- [ ] 前端：`npm run build` 成功，产物打入 core JAR
- [ ] 主仓库：submodule 指针更新 + plan 文档提交
- [ ] 工作树：干净（无未提交修改）

---

*最后更新：2026-07-19*
