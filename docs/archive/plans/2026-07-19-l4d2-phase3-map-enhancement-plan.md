# Phase 3: L4D2 地图增强模块实施计划

> **主 plan**：`docs/superpowers/plans/2026-07-19-l4d2-server-next-port-plan.md`
> **设计文档**：`docs/superpowers/specs/2026-07-19-l4d2-server-next-port-design.md` §4 模块 8-9 + §3.7
> **当前阶段**：Phase 3（Map Enhancement）— 覆盖 spec §4 模块 8-9

---

## 1. 概述

Phase 3 实现 L4D2 地图管理增强的 3 个核心模块：

| Task | 模块 | 后端 | 前端 |
|------|------|------|------|
| 3.1 | VPK 二进制裁剪 | `VpkTrimService` + `VpkParser` 扩展 | - |
| 3.2 | MapController 重构 | 重构现有 `MapController`（接入 VPK 裁剪 + 地图热重载） | - |
| 3.3 | 分片上传 | `ChunkUploadController` + `ChunkUploadService` + `ChunkUploadResource` | - |
| 3.4 | 前端 Maps.vue 重构 | - | 接入分片上传 + 裁剪 + 热重载 |
| 3.5 | 集成验证 | 后端编译 + 测试 | 前端构建 + 工作树干净 |

下载体系（URL 下载器 + Workshop）属于 Phase 4，不在本 plan 范围。

---

## 2. 关键架构约束

> **必读**：以下约束来自 Phase 0/1/2 经验。

### 2.1 模块依赖

- `plugin-l4d2-core` **禁止依赖** `core` 模块
- 通过以下接口获取宿主能力：
  - `InstanceQueryService.getInstanceById(Long)` → `InstanceVO`
  - `FileAccessService`：`readTextFile / writeTextFile / listFiles / uploadFile / deleteFile`
  - `ExtensionClient`：扩展资源 CRUD
  - `RconService.executeCommand(host, port, password, command)`

### 2.2 ExtensionClient API

```java
// create 返回 void
extensionClient.create(resource);
return resource;

// 按 ID 查询
extensionClient.getById(Klass.class, id).orElse(null);

// 按 ID 删除
extensionClient.deleteById(Klass.class, id);

// 列表查询
ListOptions opts = ListOptions.builder()
    .specFilter("$.instanceId", "=", instanceId)
    .build();
extensionClient.list(Klass.class, opts);

// 更新
extensionClient.update(resource);
```

### 2.3 前端约束

- **所有前端代码必须放在** `backend/plugin-l4d2/frontend/` 内
- 构建产物通过 `vite.config.ts` 的 `outDir` 打入 core JAR

### 2.4 前端 API 调用模式

```typescript
import { get, post, del, upload } from './request'
// 返回 Promise<T>，T 直接是 data
export const xxxApi = {
  list: (instanceId: number) => get<XXX[]>('/xxx/list', { instanceId }),
}
```

### 2.5 Store 字段名

```typescript
const instanceId = computed(() => store.instanceInfo?.instanceId)  // 不是 .id
```

### 2.6 已存在的前置类（直接复用）

| 类 | 路径 | 用途 |
|----|------|------|
| `L4D2Config` | `config/L4D2Config.java` | 已含 `chunkUpload`/`vpkTrim`/`mapHotReload` 配置 |
| `L4D2PathResolver` | `resolver/L4D2PathResolver.java` | 路径解析 |
| `VpkParser` | `util/VpkParser.java` | VPK 解析（已实现 parse + parseMissionFile） |
| `VpkParserService` | `service/VpkParserService.java` | VPK 解析服务（含缓存） |
| `GbkCodecUtil` | `util/GbkCodecUtil.java` | GBK Charset |
| `ArchiveExtractUtil` | `util/ArchiveExtractUtil.java` | ZIP/7z 解压 |
| `FilenameSanitizeUtil` | `util/FilenameSanitizeUtil.java` | 文件名清洗 |
| `L4D2PluginException` | `exception/L4D2PluginException.java` | 业务异常 |
| `RconService` | `service/RconService.java` | RCON 命令执行 |
| `ExternalHttpClient` | `service/ExternalHttpClient.java` | HTTP 下载 |
| `DownloadTaskResource` + `DownloadTaskSpec` | `extension/` | 下载任务扩展资源（已存在） |

### 2.7 现有 MapController 状态

当前 `MapController` 已实现基础端点：
- `GET /maps/list` — 解析 addons 目录 VPK
- `POST /maps/upload` — 上传 VPK 到 addons
- `DELETE /maps/{mapName}` — 删除 VPK
- `POST /maps/refresh` — 刷新缓存

Phase 3 需**保留现有端点**，**新增**：
- `POST /maps/hot-reload` — 地图热重载（RCON）
- `POST /maps/{mapName}/trim` — VPK 手动裁剪（带备份）
- `POST /maps/trim-batch` — 批量裁剪
- `GET /maps/{mapName}/mission` — 解析 VPK mission 信息

### 2.8 Git submodule

- git 命令需在 `backend/` 目录下执行

---

## 3. 文件结构（新增/重构）

### 3.1 后端新增

```
backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/
├── controller/
│   ├── MapController.java                 # Task 3.2 [重构]
│   └── ChunkUploadController.java         # Task 3.3
├── service/
│   ├── VpkTrimService.java                # Task 3.1
│   ├── ChunkUploadService.java            # Task 3.3
│   └── MapService.java                    # Task 3.2 [新] 抽取业务逻辑
├── extension/
│   └── ChunkUploadResource.java           # Task 3.3 [新]
├── vo/
│   ├── VpkTrimResultVO.java               # Task 3.1
│   ├── MissionInfoVO.java                 # Task 3.1
│   ├── ChunkUploadInitVO.java             # Task 3.3
│   └── ChunkUploadStatusVO.java           # Task 3.3
└── dto/
    ├── ChunkUploadInitDTO.java            # Task 3.3
    └── MapTrimBatchDTO.java               # Task 3.2
```

### 3.2 前端重构

```
backend/plugin-l4d2/frontend/src/
├── pages/
│   └── Maps.vue                           # Task 3.4 [重构]
├── components/
│   └── ChunkUploader.vue                  # Task 3.4 [新] 分片上传组件
└── api/index.ts                           # [追加 mapApi + chunkUploadApi]
```

---

## 4. Tasks

### Task 3.1: VpkTrimService + VpkParser 扩展

**目标**：实现 VPK v1 二进制裁剪，删除冗余文件（nav/lump/materials/sound/models），保留 mission/bsp 引用。

**对齐 spec**：§3.7（第 737-761 行）

**关键设计**：
- VPK v1 格式：Header(12B) + Tree + Chunk
  - Header：magic(4B `0x55AA1234` 小端) + version(4B = 1) + treeSize(4B)
  - Tree：按 extension/path/filename 三层嵌套，每条目含 crc/preBytes/archiveIdx/offset/length/preload
  - Tree 末尾以 16 字节 `\0` 终止
  - Chunk 区紧跟 Tree，文件物理位置 = `headerSize(12) + treeSize + offset`
- 裁剪策略：在 Tree 中**删除**特定条目 + 重写 Chunk 区（保留的文件按顺序紧凑排列，调整 offset）
- **需移除的文件模式**：
  - `*.vmf` `*.vmx`（地图源文件，运行时无用）
  - `materials/**/*.vtf`（材质贴图，体积大）
  - `sound/**/*.mp3` `sound/**/*.wav`（音频）
  - `models/**/*.vvd` `models/**/*.vtx`（模型顶点数据）
- **保留**：`maps/*.bsp`、`missions/*.txt`、`*.txt`/`*.bsp`/`*.vpk` 等运行时必需文件

**步骤**：

1. **扩展 `VpkParser`**（`util/VpkParser.java`）：
   - 现有 `parse(File)` 返回 `VpkArchive`，已包含 `fileEntries: List<VpkFileEntry>`
   - **新增方法** `parseWithChunkData(File)` → 返回 `VpkArchive` 含每个 entry 的 `byte[] chunkData`（懒加载）
   - **新增内部类** `VpkChunkData`：保存每个 entry 的字节数据，供裁剪使用

2. **创建 `VpkTrimService`**（`service/VpkTrimService.java`）：

```java
@Slf4j
@Service
public class VpkTrimService {
    private static final int VPK_MAGIC = 0x55AA1234;
    private static final int VPK_VERSION = 1;
    private static final int HEADER_SIZE = 12;

    // 需移除的扩展名
    private static final Set<String> TRIM_EXTENSIONS = Set.of("vmf", "vmx");
    // 需移除的路径前缀（按 path 匹配）
    private static final List<String> TRIM_PATH_PREFIXES = List.of(
        "materials", "sound", "sounds", "models"
    );

    /**
     * 裁剪 VPK 文件（原地或备份后裁剪）
     * @param vpkFile VPK 文件
     * @param backup 是否备份原文件
     * @return 裁剪结果（含大小差异）
     */
    public VpkTrimResultVO trim(File vpkFile, boolean backup);

    /**
     * 解析 VPK mission 信息
     */
    public MissionInfoVO parseMission(File vpkFile);

    /**
     * 判断文件是否需要裁剪
     */
    private boolean shouldTrim(VpkFileEntry entry);
}
```

3. **创建 `VpkTrimResultVO`**：

```java
@Data
public class VpkTrimResultVO {
    private String fileName;
    private long originalSize;
    private long trimmedSize;
    private long savedBytes;
    private int totalEntries;
    private int trimmedEntries;
    private boolean backupCreated;
    private String backupFileName;
}
```

4. **创建 `MissionInfoVO`**：

```java
@Data
public class MissionInfoVO {
    private String vpkName;
    private String title;
    private List<ChapterVO> chapters;

    @Data
    public static class ChapterVO {
        private String code;
        private String title;
        private List<String> modes;
    }
}
```

5. **测试**（`VpkTrimServiceTest.java`）：
   - 构造一个最小 VPK 文件（magic + version=1 + treeSize + 含 3 个条目的 tree + chunk 数据）
   - 调用 `trim`：验证保留 `maps/x.bsp` + `missions/x.txt`，移除 `materials/x.vtf` + `sound/x.mp3`
   - 验证裁剪后文件大小 < 原大小
   - 验证 `backup=true` 时生成 `.bak.{timestamp}` 文件
   - `shouldTrim` 测试：vmf/vmx → true；materials/* → true；maps/*.bsp → false

**验收**：
- 后端编译通过
- 单测全部通过
- `VpkTrimService.trim` 返回正确的 `VpkTrimResultVO`

**commit**：`feat(l4d2): vpk trim service (phase 3.1)`

---

### Task 3.2: MapController 重构 + MapService

**目标**：重构 `MapController`，抽取业务到 `MapService`，新增热重载/裁剪/批量裁剪/mission 端点。

**对齐 spec**：§4 模块 8（第 1029-1050 行）

**步骤**：

1. **创建 `MapService`**（`service/MapService.java`）：抽取 MapController 业务逻辑

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class MapService {
    private final VpkParserService vpkParserService;
    private final VpkTrimService vpkTrimService;
    private final InstanceQueryService instanceQueryService;
    private final FileAccessService fileAccessService;
    private final RconService rconService;
    private final L4D2Config config;
    private final L4D2PathResolver pathResolver;

    /** 列出实例的地图 */
    public List<MapListVO> listMaps(Long instanceId);

    /** 上传地图（VPK magic 校验 → 复制到 addons/ → 可选自动裁剪） */
    public MapListVO uploadMap(Long instanceId, MultipartFile file);

    /** 删除地图 */
    public void deleteMap(Long instanceId, String mapName);

    /** 刷新缓存 */
    public void refreshCache(Long instanceId);

    /** 地图热重载（RCON 命令） */
    public void hotReload(Long instanceId);

    /** VPK 手动裁剪（带备份） */
    public VpkTrimResultVO trimMap(Long instanceId, String mapName);

    /** 批量裁剪 */
    public List<VpkTrimResultVO> trimBatch(Long instanceId, List<String> mapNames);

    /** 解析 VPK mission 信息 */
    public MissionInfoVO getMission(Long instanceId, String mapName);
}
```

**关键实现点**：
- `uploadMap`：先校验 VPK magic（用 `VpkParser`），再上传到 `addons/{filename}.vpk`，若 `L4D2Config.vpkTrim.enabled=true` 则自动裁剪
- `hotReload`：通过 `InstanceQueryService` 获取实例 → `RconService.executeCommand(host, port, password, config.getMapHotReload().getCommand())`
- `trimMap`：流程
  1. 从远程下载 VPK 到本地临时文件（用 `FileAccessService.downloadFile`，如不存在则用 SFTP 读取）
  2. 调用 `VpkTrimService.trim(localFile, true)`（带备份）
  3. 上传裁剪后的 VPK 到远程（覆盖原文件）
  4. 失败回滚：从备份恢复
- `getMission`：用 `VpkParser` 解析 mission 文件

2. **重构 `MapController`**（`controller/MapController.java`）：

```java
@Slf4j
@Tag(name = "L4D2 地图管理")
@RestController
@RequestMapping("/api/plugin/l4d2/maps")
@RequiredArgsConstructor
@Validated
public class MapController {
    private final MapService mapService;

    @GetMapping("/list")
    public Result<List<MapListVO>> list(@RequestParam Long instanceId);

    @PostMapping("/upload")
    public Result<MapListVO> upload(@RequestParam Long instanceId,
                                    @RequestParam("file") MultipartFile file);

    @DeleteMapping("/{mapName}")
    public Result<Void> delete(@RequestParam Long instanceId, @PathVariable String mapName);

    @PostMapping("/refresh")
    public Result<Void> refresh(@Valid @RequestBody InstanceIdDTO dto);

    // 新增端点
    @PostMapping("/hot-reload")
    public Result<Void> hotReload(@RequestParam Long instanceId);

    @PostMapping("/{mapName}/trim")
    public Result<VpkTrimResultVO> trim(@RequestParam Long instanceId, @PathVariable String mapName);

    @PostMapping("/trim-batch")
    public Result<List<VpkTrimResultVO>> trimBatch(@Valid @RequestBody MapTrimBatchDTO dto);

    @GetMapping("/{mapName}/mission")
    public Result<MissionInfoVO> mission(@RequestParam Long instanceId, @PathVariable String mapName);
}
```

3. **创建 `MapTrimBatchDTO`**：

```java
@Data
public class MapTrimBatchDTO {
    @NotNull private Long instanceId;
    @NotEmpty private List<String> mapNames;
}
```

4. **测试**（`MapServiceTest.java`）：
   - `hotReload`：mock RconService，验证调用 `executeCommand` 一次
   - `trimMap`：mock FileAccessService 下载文件 + VpkTrimService 返回结果，验证上传裁剪后文件
   - `trimBatch`：mock 流程，验证返回 N 个结果

**验收**：
- 后端编译通过
- 单测全部通过

**commit**：`refactor(l4d2): map controller with trim/hot-reload (phase 3.2)`

---

### Task 3.3: ChunkUploadController + ChunkUploadService + ChunkUploadResource

**目标**：实现大文件分片上传，支持断点续传。

**对齐 spec**：§4 模块 9（第 1052-1078 行）+ §2.2.8（第 427-449 行）

**关键设计**：
- 临时目录：`System.getProperty("java.io.tmpdir") + "/l4d2-chunk-{uploadId}/"`
- 元数据持久化：`ChunkUploadResource`（ExtensionClient）
- 分片大小：5MB（`L4D2Config.chunkUpload.chunkSizeBytes`）
- 总大小上限：2GB（`L4D2Config.chunkUpload.maxTotalSizeBytes`）
- uploadId：UUIDv4 严格校验
- 磁盘空间检查：本机磁盘使用率 > 90%（`L4D2Config.chunkUpload.diskUsageThreshold`）拒绝新上传
- 过期清理：每小时扫描，删除 6 小时未完成的记录 + 临时文件

**步骤**：

1. **创建 `ChunkUploadResource` + `ChunkUploadSpec`**：

```java
// ChunkUploadResource.java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class ChunkUploadResource extends AbstractExtension<ChunkUploadSpec> {
}

// ChunkUploadSpec.java
@Data
public class ChunkUploadSpec implements Serializable {
    private String uploadId;        // UUIDv4
    private Long instanceId;
    private Long hostId;
    private String originalFilename;
    private long totalSize;
    private int totalChunks;
    private int receivedChunks;
    private String tempDir;         // 临时目录绝对路径
    private String targetPath;      // 完成后目标路径（相对 installPath）
    private String status;          // UPLOADING / COMPLETED / EXPIRED / FAILED
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Set<Integer> receivedIndexes;  // 已接收分片索引
}
```

2. **创建 `ChunkUploadService`**：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {
    private final ExtensionClient extensionClient;
    private final FileAccessService fileAccessService;
    private final L4D2Config config;

    /**
     * 初始化上传
     */
    public ChunkUploadInitVO init(ChunkUploadInitDTO dto) {
        // 1. 校验 totalSize <= maxTotalSize
        // 2. 检查磁盘空间
        // 3. 创建临时目录
        // 4. 创建 ChunkUploadResource（status=UPLOADING）
        // 5. 返回 uploadId
    }

    /**
     * 上传分片
     */
    public void uploadChunk(String uploadId, int index, MultipartFile chunk) {
        // 1. 校验 uploadId 存在 + status=UPLOADING
        // 2. 校验 index 在 [0, totalChunks)
        // 3. 写入临时文件 chunk-{index}
        // 4. 更新 receivedIndexes
        // 5. 更新 ChunkUploadResource
    }

    /**
     * 查询上传进度
     */
    public ChunkUploadStatusVO status(String uploadId);

    /**
     * 完成上传
     */
    public void complete(String uploadId) {
        // 1. 校验所有分片已接收
        // 2. 合并分片为完整文件到 {tempDir}/{originalFilename}
        // 3. 调用 FileAccessService 上传到远程 targetPath
        // 4. 更新 status=COMPLETED
        // 5. 清理临时文件
    }

    /**
     * 取消上传
     */
    public void cancel(String uploadId) {
        // 1. 删除临时目录
        // 2. 删除 ChunkUploadResource
    }

    /**
     * 过期清理（@Scheduled 每小时）
     */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupExpired() {
        // 1. 查询所有 status=UPLOADING 的记录
        // 2. createdAt + 6h < now 的记录 → 删除临时目录 + 删除 ChunkUploadResource
    }
}
```

3. **创建 `ChunkUploadController`**：

```java
@Slf4j
@Tag(name = "L4D2 分片上传")
@RestController
@RequestMapping("/api/plugin/l4d2/chunk-upload")
@RequiredArgsConstructor
public class ChunkUploadController {
    private final ChunkUploadService chunkUploadService;

    @PostMapping("/init")
    public Result<ChunkUploadInitVO> init(@Valid @RequestBody ChunkUploadInitDTO dto);

    @PostMapping("/{uploadId}/chunk")
    public Result<Void> uploadChunk(@PathVariable String uploadId,
                                    @RequestParam int index,
                                    @RequestParam("chunk") MultipartFile chunk);

    @GetMapping("/{uploadId}/status")
    public Result<ChunkUploadStatusVO> status(@PathVariable String uploadId);

    @PostMapping("/{uploadId}/complete")
    public Result<Void> complete(@PathVariable String uploadId);

    @PostMapping("/{uploadId}/cancel")
    public Result<Void> cancel(@PathVariable String uploadId);
}
```

4. **创建 VO/DTO**：

```java
// ChunkUploadInitDTO
@Data
public class ChunkUploadInitDTO {
    @NotNull private Long instanceId;
    @NotBlank private String filename;
    @Positive private long totalSize;
    @Positive private int totalChunks;
    private String targetPath;  // 可选
}

// ChunkUploadInitVO
@Data
public class ChunkUploadInitVO {
    private String uploadId;
    private long chunkSize;  // 服务端期望的分片大小
}

// ChunkUploadStatusVO
@Data
public class ChunkUploadStatusVO {
    private String uploadId;
    private int totalChunks;
    private int receivedChunks;
    private Set<Integer> receivedIndexes;
    private String status;
    private double progress;  // 0-100
}
```

5. **测试**（`ChunkUploadServiceTest.java`）：
   - `init`：校验大小上限 + 创建临时目录 + 创建 Resource
   - `uploadChunk`：写入分片文件 + 更新 Resource
   - `complete`：mock FileAccessService，验证合并文件 + 上传远程 + 清理临时
   - `cancel`：删除临时目录 + 删除 Resource
   - `cleanupExpired`：mock 6 小时前的记录，验证被清理

**验收**：
- 后端编译通过
- 单测全部通过

**commit**：`feat(l4d2): chunk upload service (phase 3.3)`

---

### Task 3.4: 前端 Maps.vue 重构 + ChunkUploader 组件

**目标**：重构 Maps 页面，接入分片上传、裁剪、热重载。

**步骤**：

1. **追加 API**（`api/index.ts`）：

```typescript
export const mapApi = {
  list: (instanceId: number) => get<MapListVO[]>('/maps/list', { instanceId }),
  upload: (file: File, instanceId: number, onProgress?: (p: number) => void) =>
    upload<MapListVO>(`/maps/upload?instanceId=${instanceId}`, file, onProgress),
  delete: (instanceId: number, mapName: string) =>
    del<void>(`/maps/${mapName}?instanceId=${instanceId}`),
  refresh: (instanceId: number) => post<void>('/maps/refresh', { instanceId }),
  hotReload: (instanceId: number) => post<void>('/maps/hot-reload', { instanceId }),
  trim: (instanceId: number, mapName: string) =>
    post<VpkTrimResultVO>(`/maps/${mapName}/trim?instanceId=${instanceId}`),
  trimBatch: (data: { instanceId: number; mapNames: string[] }) =>
    post<VpkTrimResultVO[]>('/maps/trim-batch', data),
  mission: (instanceId: number, mapName: string) =>
    get<MissionInfoVO>(`/maps/${mapName}/mission`, { instanceId }),
}

export const chunkUploadApi = {
  init: (data: { instanceId: number; filename: string; totalSize: number; totalChunks: number; targetPath?: string }) =>
    post<{ uploadId: string; chunkSize: number }>('/chunk-upload/init', data),
  uploadChunk: (uploadId: string, index: number, chunk: Blob, onProgress?: (p: number) => void) =>
    upload<void>(`/chunk-upload/${uploadId}/chunk?index=${index}`, chunk as File, onProgress),
  status: (uploadId: string) => get<{
    uploadId: string
    totalChunks: number
    receivedChunks: number
    receivedIndexes: number[]
    status: string
    progress: number
  }>(`/chunk-upload/${uploadId}/status`),
  complete: (uploadId: string) => post<void>(`/chunk-upload/${uploadId}/complete`),
  cancel: (uploadId: string) => post<void>(`/chunk-upload/${uploadId}/cancel`),
}
```

2. **创建 `ChunkUploader.vue` 组件**（`components/ChunkUploader.vue`）：

Props: `instanceId`, `targetPath?`, `accept`（默认 `.vpk`）
Emits: `success`, `error`, `progress`

逻辑：
- 大文件（>100MB）自动启用分片上传
- 小文件直接调用 `mapApi.upload`
- 分片上传流程：
  1. 计算分片数 `Math.ceil(file.size / 5MB)`
  2. 调用 `chunkUploadApi.init`
  3. 循环上传每个分片（支持并发 3 个）
  4. 所有分片上传完成 → 调用 `chunkUploadApi.complete`
- 显示进度条（按已接收分片数 / 总分片数）
- "取消"按钮：调用 `chunkUploadApi.cancel`

3. **重构 `Maps.vue`**：
- 顶部工具栏：上传按钮 + 批量裁剪 + 热重载 + 刷新
- 表格：地图名 + 大小 + mission 信息 + 操作（裁剪 + 删除 + 查看详情）
- 上传按钮打开 `ChunkUploader` 组件
- "裁剪"按钮：调用 `mapApi.trim`，展示 `VpkTrimResultVO`（含 savedBytes）
- "查看详情"按钮：弹出 mission 信息（章节列表）
- "热重载"按钮：`ElMessageBox.confirm` → 调用 `mapApi.hotReload`
- 批量勾选 + 批量裁剪

4. **构建验证**：

```bash
cd backend/plugin-l4d2/frontend
npm run build
```

**验收**：
- `npm run build` 通过
- UI 资源已输出到 `plugin-l4d2-core/src/main/resources/ui/`

**commit**：`feat(l4d2-fe): maps page with chunk upload/trim/hot-reload (phase 3.4)`

---

### Task 3.5: Phase 3 集成验证

**步骤**：

1. **后端编译 + 测试**：

```bash
cd backend
mvn install -pl api,plugin,core -am -DskipTests -q
mvn test -pl plugin-l4d2/plugin-l4d2-core -q
```

2. **前端构建**：

```bash
cd backend/plugin-l4d2/frontend
npm run build
```

3. **Git 提交**：
   - 在 `backend/` 目录提交所有变更
   - 在主仓库提交 submodule 更新

4. **手动验证清单**：
   - [ ] `GET /api/plugin/l4d2/maps/list` 返回地图列表
   - [ ] `POST /api/plugin/l4d2/maps/upload` 上传 VPK 成功
   - [ ] `POST /api/plugin/l4d2/maps/{name}/trim` 裁剪后返回 savedBytes
   - [ ] `POST /api/plugin/l4d2/maps/hot-reload` 调用 RCON 成功
   - [ ] `POST /api/plugin/l4d2/chunk-upload/init` 返回 uploadId
   - [ ] 前端 Maps 页面渲染正常

**验收**：
- 后端编译 + 所有测试通过
- 前端构建通过
- 工作树干净

**commit**：`chore(l4d2): phase 3 integration verification`

---

## 5. 执行顺序

```
Task 3.1 (VpkTrimService)  → 独立
Task 3.2 (MapController)   → 依赖 3.1
Task 3.3 (ChunkUpload)     → 独立（可与 3.1/3.2 并行）
Task 3.4 (前端)            → 依赖 3.1/3.2/3.3
Task 3.5 (集成验证)
```

建议顺序：3.1 → 3.3 → 3.2 → 3.4 → 3.5

---

## 6. 关键参考代码

### 6.1 现有 MapController 用法（保留风格）

```java
// 获取 addons 路径
String addonsPath = instance.getInstallPath() + "/left4dead2/addons";

// 上传文件
fileAccessService.uploadFile(instance.getHostId(), targetPath, file);

// 删除文件
fileAccessService.deleteFile(instance.getHostId(), mapPath);
```

### 6.2 VpkParser 用法（已存在）

```java
VpkParser vpkParser = new VpkParser();
VpkParser.VpkArchive archive = vpkParser.parse(vpkFile);
// archive.getFileEntries() 返回 List<VpkFileEntry>
// archive.getMissionFiles() 返回 mission txt 文件
```

### 6.3 RconService 用法（热重载）

```java
// 通过 InstanceQueryService 获取 InstanceVO
InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
// 执行 RCON 命令
rconService.executeCommand(instance.getHost(), rconPort, rconPassword, "update_addon_paths; mission_reload");
```

**注意**：先 Read `InstanceVO.java` 确认 RCON 相关字段名（可能是 `rconPort`/`rconPassword` 或 `getRconPassword()`）。

### 6.4 ExtensionClient 用法（ChunkUploadResource）

```java
// 创建
ChunkUploadResource resource = new ChunkUploadResource();
ChunkUploadSpec spec = new ChunkUploadSpec();
spec.setUploadId(uploadId);
spec.setInstanceId(instanceId);
resource.setSpec(spec);
resource.setName(uploadId);  // name = uploadId
extensionClient.create(resource);
return resource;

// 按 name 查询
ChunkUploadResource r = extensionClient.get(ChunkUploadResource.class, uploadId).orElse(null);

// 更新
r.getSpec().setReceivedChunks(n);
extensionClient.update(r);

// 列表
ListOptions opts = ListOptions.builder()
    .specFilter("$.status", "=", "UPLOADING")
    .build();
List<ChunkUploadResource> list = extensionClient.list(ChunkUploadResource.class, opts);

// 删除
extensionClient.delete(ChunkUploadResource.class, uploadId);
```

---

## 7. 风险与降级

| 风险 | 影响 | 降级方案 |
|------|------|---------|
| VPK 裁剪破坏文件 | 地图无法加载 | 必做备份 + 失败回滚 + 单元测试覆盖 |
| 大文件上传超时 | 上传失败 | 5MB 分片 + 断点续传 + 取消重试 |
| 临时目录磁盘满 | 上传失败 | 磁盘空间检查 + 90% 阈值拒绝 |
| RCON 命令失败 | 热重载无效 | 错误提示 + 保留文件操作 |
| 分片上传中断 | 临时文件残留 | 6 小时过期清理 |

---

## 8. 完成标准

- [ ] Task 3.1-3.5 全部完成
- [ ] 后端编译通过，所有单测通过
- [ ] 前端构建通过
- [ ] 工作树干净

---

*创建日期：2026-07-19*
*下一步：Phase 4（下载体系：URL 下载器 + Workshop 下载器）*
