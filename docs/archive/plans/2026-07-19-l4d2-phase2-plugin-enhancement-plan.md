# Phase 2: L4D2 插件增强模块实施计划

> **主 plan**：`docs/superpowers/plans/2026-07-19-l4d2-server-next-port-plan.md`
> **设计文档**：`docs/superpowers/specs/2026-07-19-l4d2-server-next-port-design.md` §4 模块 4-7
> **当前阶段**：Phase 2（Plugin Enhancement）— 覆盖 spec §4 模块 4-7

---

## 1. 概述

Phase 2 实现 L4D2 插件管理的 4 个核心增强模块：

| Task | 模块 | 后端 | 前端 |
|------|------|------|------|
| 2.1 | 插件配置 cfg | `PluginConfigController` + `SourceModCfgService` | - |
| 2.2 | 插件商店 | `PluginStoreController` + `PluginStoreService` + GitHub/LFS | - |
| 2.3 | 预设系统 | `PresetController` + `PresetService` + `preset.yaml` | - |
| 2.4 | 插件管理重构 | `PluginManageController` 重构 + `PluginInstallService` + `PluginExportService` + `FileRefsService` | - |
| 2.5 | 前端商店 | - | `PluginStore.vue` |
| 2.6 | 前端配置 | - | `PluginConfig.vue` |
| 2.7 | 前端预设 | - | `Preset.vue` |
| 2.8 | 前端插件管理 | - | `Plugins.vue` 重构 |
| 2.9 | 集成验证 | 后端编译 + 测试 | 前端构建 + 工作树干净 |

---

## 2. 关键架构约束

> **必读**：以下约束来自 Phase 0/1 经验，违反会导致编译错误或运行时异常。

### 2.1 模块依赖

- `plugin-l4d2-core` **禁止依赖** `core` 模块
- 通过以下接口获取宿主能力：
  - `com.gameplatform.plugin.service.InstanceQueryService`：`getInstanceById(Long)` → `InstanceVO`
  - `com.gameplatform.plugin.service.FileAccessService`：`readTextFile / writeTextFile / listFiles / uploadFile / deleteFile`
  - `com.gameplatform.plugin.service.HostQueryService`：主机信息（本阶段不使用）
  - `com.gameplatform.plugin.extension.ExtensionClient`：扩展资源 CRUD

### 2.2 ExtensionClient API（**关键**）

```java
// create 返回 void，不能 return extensionClient.create(resource)
extensionClient.create(resource);
return resource;

// 按 name 删除（注意：name 不是 ID）
extensionClient.delete(Klass.class, name);

// 按 ID 删除（path variable 是 ID 时用这个）
extensionClient.deleteById(Klass.class, id);

// 按 ID 查询，返回 Optional<T>
extensionClient.getById(Klass.class, id).orElse(null);

// 按 name 查询，返回 Optional<T>
extensionClient.get(Klass.class, name).orElse(null);

// 列表查询
ListOptions opts = ListOptions.builder()
    .specFilter("$.instanceId", "=", instanceId)
    .build();
List<T> list = extensionClient.list(Klass.class, opts);

// 更新（直接传 resource 对象）
extensionClient.update(resource);
```

### 2.3 前端约束（**用户强制要求**）

- **所有前端代码必须放在** `backend/plugin-l4d2/frontend/` 内
- 仅在需要适配主应用全局组件/路由时才修改主应用 `frontend/`
- 构建产物通过 `vite.config.ts` 的 `outDir: '../plugin-l4d2-core/src/main/resources/ui'` 打入 core JAR

### 2.4 前端 API 调用模式

```typescript
// 用 get/post/put/del/upload 函数（不是 request.get）
// 返回 Promise<T>，T 直接是 data（不需要 res.data）
import { get, post, put, del, upload } from './request'

export const xxxApi = {
  list: (instanceId: number) => get<XXX[]>('/xxx/list', { instanceId }),
  create: (data: XXXDTO) => post<{ id: string }>('/xxx/create', data),
}
```

### 2.5 Store 字段名

```typescript
// store.instanceInfo 是 InitPayload | null
// 字段名是 instanceId（不是 id）
const instanceId = computed(() => store.instanceInfo?.instanceId)
```

### 2.6 已存在的前置类（Phase 0/1 已实现，本阶段直接复用）

| 类 | 路径 | 用途 |
|----|------|------|
| `L4D2Config` | `config/L4D2Config.java` | 配置项（已含 `pluginStore`、`workshop` 等） |
| `L4D2PathResolver` | `resolver/L4D2PathResolver.java` | 路径解析（已含 `getFileRefsPath`） |
| `SourceModCfgParser` | `parser/SourceModCfgParser.java` | cfg 解析（已实现 parse/serialize） |
| `ConfigItem` | `vo/config/ConfigItem.java` | cfg 配置项 VO |
| `GbkCodecUtil` | `util/GbkCodecUtil.java` | `gbk()` 返回 GBK Charset |
| `ExternalHttpClient` | `service/ExternalHttpClient.java` | HTTP 下载/GET/POST |
| `ArchiveExtractUtil` | `util/ArchiveExtractUtil.java` | ZIP/7z 解压 |
| `FilenameSanitizeUtil` | `util/FilenameSanitizeUtil.java` | 文件名清洗 |
| `L4D2PluginException` | `exception/L4D2PluginException.java` | 业务异常（已含 BUSINESS/RCON/FILE/NETWORK/EXTERNAL_API） |
| `RconService` | `service/RconService.java` | RCON 命令执行 |
| `PluginConfigResource` + `PluginConfigSpec` | `extension/` | 扩展资源（已存在，**但 Spec 字段需扩展**） |

### 2.7 Git submodule 注意

- git 命令需在 `backend/` 目录下执行（submodule 根）
- 主仓库提交时再做一次 `git add backend && git commit`

---

## 3. 文件结构（新增/重构）

### 3.1 后端新增

```
backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/
├── controller/
│   ├── PluginConfigController.java          # Task 2.1
│   ├── PluginStoreController.java           # Task 2.2
│   ├── PresetController.java                # Task 2.3
│   └── PluginManageController.java          # Task 2.4 [重构]
├── service/
│   ├── SourceModCfgService.java             # Task 2.1
│   ├── PluginStoreService.java              # Task 2.2
│   ├── PresetService.java                   # Task 2.3
│   ├── PluginInstallService.java            # Task 2.4 [新]
│   ├── PluginExportService.java             # Task 2.4 [新]
│   └── FileRefsService.java                 # Task 2.4 [新]
├── vo/
│   ├── PluginConfigVO.java                  # Task 2.1
│   ├── PluginStoreItemVO.java               # Task 2.2
│   ├── PluginStoreDetailVO.java             # Task 2.2
│   ├── PresetDetailVO.java                  # Task 2.3
│   └── PluginExportTaskVO.java              # Task 2.4
├── dto/
│   ├── PluginConfigUpdateDTO.java           # Task 2.1
│   ├── PluginStoreDownloadDTO.java          # Task 2.2
│   └── BatchPluginOperationDTO.java         # Task 2.4
├── util/
│   └── GitHubApiClient.java                 # Task 2.2 [GitHub API + LFS BatchAPI 封装]
└── extension/
    └── PluginConfigSpec.java                # Task 2.1 [扩展字段]
```

### 3.2 后端新增资源文件

```
backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/
└── preset.yaml                              # Task 2.3
```

### 3.3 前端新增

```
backend/plugin-l4d2/frontend/src/
├── pages/
│   ├── PluginStore.vue                      # Task 2.5
│   ├── PluginConfig.vue                     # Task 2.6
│   ├── Preset.vue                           # Task 2.7
│   └── Plugins.vue                          # Task 2.8 [重构]
└── api/index.ts                             # [追加 pluginStoreApi/pluginConfigApi/presetApi/pluginManageApi]
└── router/index.ts                          # [追加 3 个路由]
```

---

## 4. Tasks

### Task 2.1: PluginConfigController + SourceModCfgService

**目标**：实现 SourceMod cfg 配置项的读取、更新、候选路径推导。

**对齐 spec**：§4 模块 4（端点 `/api/plugin/l4d2/plugin-config`）

**步骤**：

1. **扩展 `PluginConfigSpec`**（已存在，需补充字段）：

```java
@Data
public class PluginConfigSpec implements Serializable {
    private Long instanceId;
    private Long hostId;
    private String pluginName;        // .smx 文件名（不含扩展名）
    private String configName;        // cfg 文件名
    private String configPath;        // 相对 installPath 的路径
    private List<ConfigItem> items;   // 解析后的配置项
    private String rawContent;        // 原始文件内容（GBK 解码后）
    private LocalDateTime lastSyncedAt;
    // 旧字段（保留兼容，不再使用）：pluginStatus/description/version/author/enableTime/isDeleted/remark
}
```

2. **创建 `SourceModCfgService`**（`service/SourceModCfgService.java`）：

```java
@Service
@RequiredArgsConstructor
public class SourceModCfgService {
    private final InstanceQueryService instanceQueryService;
    private final FileAccessService fileAccessService;
    private final ExtensionClient extensionClient;
    private final SourceModCfgParser cfgParser;
    private final L4D2PathResolver pathResolver;
    private final Charset gbk = GbkCodecUtil.gbk();

    /** 候选 cfg 路径推导 */
    public List<String> getCandidatePaths(InstanceVO instance, String pluginName) {
        // 1. 优先匹配前缀 l4d2_ / l4d_：l4d2_ai_upgrade → cfg/sourcemod/l4d2_ai_upgrade.cfg
        // 2. 否则用插件名直接匹配：{pluginName}.cfg
        // 候选列表：
        //   - cfg/sourcemod/{pluginName}.cfg
        //   - addons/sourcemod/plugins/{pluginName}.cfg
    }

    /** 获取配置：读第一个存在的候选文件 → GBK 解码 → parse */
    public PluginConfigResource getConfig(Long instanceId, String pluginName);

    /** 列出候选 cfg 文件路径（含存在性标记） */
    public List<CandidatePathVO> listCandidates(Long instanceId, String pluginName);

    /** 更新配置：serialize → GBK 编码 → 写回 + 更新扩展资源 */
    public void updateConfig(Long instanceId, String pluginName, List<ConfigItem> items);
}
```

3. **创建 `PluginConfigController`**：

```java
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-config")
public class PluginConfigController {
    @GetMapping("/get")
    public Result<PluginConfigVO> get(@RequestParam Long instanceId, @RequestParam String pluginName);

    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody PluginConfigUpdateDTO dto);

    @GetMapping("/candidates")
    public Result<List<CandidatePathVO>> candidates(@RequestParam Long instanceId, @RequestParam String pluginName);
}
```

4. **创建 `PluginConfigVO`**：

```java
@Data
public class PluginConfigVO {
    private String pluginName;
    private String configName;
    private String configPath;
    private List<ConfigItem> items;
    private String rawContent;
    private LocalDateTime lastSyncedAt;
}
```

5. **创建 `PluginConfigUpdateDTO`**：

```java
@Data
public class PluginConfigUpdateDTO {
    @NotNull private Long instanceId;
    @NotBlank private String pluginName;
    @NotEmpty private List<ConfigItem> items;
}
```

6. **测试**（`SourceModCfgServiceTest.java`）：
   - 候选路径推导：`l4d2_ai_upgrade` → `[cfg/sourcemod/l4d2_ai_upgrade.cfg, addons/sourcemod/plugins/l4d2_ai_upgrade.cfg]`
   - `parse` + `serialize` 往返测试：保留注释和元数据
   - 不存在候选文件时返回空 `PluginConfigResource`

**验收**：
- 后端编译通过
- 单测全部通过
- 手动验证：`GET /api/plugin/l4d2/plugin-config/get?instanceId=1&pluginName=l4d2_ai_upgrade` 返回正确格式

**commit**：`feat(l4d2): plugin config controller & service (phase 2.1)`

---

### Task 2.2: PluginStoreController + PluginStoreService

**目标**：实现 GitHub 插件商店浏览、详情、下载（含 Git LFS BatchAPI）。

**对齐 spec**：§4 模块 5（端点 `/api/plugin/l4d2/plugin-store`）

**关键设计**：
- GitHub API：`GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1` 获取目录树
- 缓存 10 分钟（Caffeine 或 `ConcurrentHashMap` + TTL）
- 检测 LFS pointer：文件内容以 `version https://git-lfs.github.com/spec/v1` 开头
- LFS BatchAPI：`POST /repos/{owner}/{repo}/info/lfs/objects/batch`
- 3 并发下载（`Semaphore`）
- 下载完成后调用 `PluginInstallService.installFromLocalFile`（Task 2.4 先 stub，后续接入）

**步骤**：

1. **创建 `GitHubApiClient`**（`util/GitHubApiClient.java`）：

```java
@Component
@RequiredArgsConstructor
public class GitHubApiClient {
    private final ExternalHttpClient httpClient;
    private final L4D2Config config;

    /** 获取仓库目录树（递归） */
    public GitHubTreeResponse getTree();  // GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1

    /** 获取文件内容（小文件，非 LFS） */
    public String getFileContent(String path);  // GET /repos/{owner}/{repo}/contents/{path}

    /** 检测 LFS pointer */
    public boolean isLfsPointer(String content);

    /** LFS BatchAPI：批量获取真实下载链接 */
    public LfsBatchResponse batchLfsObjects(List<String> oids);  // POST /repos/{owner}/{repo}/info/lfs/objects/batch
}
```

2. **创建 `PluginStoreService`**：

```java
@Service
@RequiredArgsConstructor
public class PluginStoreService {
    private final GitHubApiClient gitHubApiClient;
    private final ExternalHttpClient httpClient;
    private final PluginInstallService pluginInstallService;  // Task 2.4
    private final L4D2Config config;

    private final Map<String, Long> cachedTreeTimestamp = new ConcurrentHashMap<>();
    private volatile List<PluginStoreItemVO> cachedItems;
    private final Semaphore downloadSemaphore = new Semaphore(3);  // 3 并发

    /** 商店列表（缓存 10 分钟） */
    public List<PluginStoreItemVO> list(String keyword, String category);

    /** 商店详情（含 README Markdown） */
    public PluginStoreDetailVO detail(String pluginId);

    /** README 内容 */
    public String readme(String pluginId);

    /** 下载到指定实例：走 LFS BatchAPI → 3 并发下载 → 调用 PluginInstallService */
    public String download(PluginStoreDownloadDTO dto);

    /** 取消下载 */
    public void cancel(String taskId);
}
```

3. **创建 `PluginStoreController`**：

```java
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-store")
public class PluginStoreController {
    @GetMapping("/list")
    public Result<List<PluginStoreItemVO>> list(@RequestParam(required=false) String keyword,
                                                @RequestParam(required=false) String category,
                                                @RequestParam(defaultValue="1") int page,
                                                @RequestParam(defaultValue="20") int size);

    @GetMapping("/{pluginId}")
    public Result<PluginStoreDetailVO> detail(@PathVariable String pluginId);

    @GetMapping("/{pluginId}/readme")
    public Result<String> readme(@PathVariable String pluginId);

    @PostMapping("/download")
    public Result<String> download(@Valid @RequestBody PluginStoreDownloadDTO dto);

    @GetMapping("/tasks")
    public Result<List<DownloadTaskVO>> tasks(@RequestParam Long instanceId);

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable String taskId);
}
```

4. **创建 VO/DTO**：
   - `PluginStoreItemVO`：pluginId, name, description, category, size, updatedAt
   - `PluginStoreDetailVO`：item + readme + fileList
   - `PluginStoreDownloadDTO`：instanceId, pluginId, targetPath?
   - 复用 `DownloadTaskResource`（source=STORE）

5. **测试**（`PluginStoreServiceTest.java`）：
   - LFS pointer 检测：`version https://git-lfs.github.com/spec/v1` 开头返回 true
   - 列表过滤：keyword 命中 name/description
   - 缓存命中：第二次调用不发 HTTP

**验收**：
- GitHub API 调用成功（需网络；离线时测试 mock）
- 单测通过

**commit**：`feat(l4d2): plugin store with GitHub API + LFS (phase 2.2)`

---

### Task 2.3: PresetController + PresetService

**目标**：实现 preset.yaml 内嵌的 4 个预设场景，支持应用预设到实例。

**对齐 spec**：§4 模块 6（端点 `/api/plugin/l4d2/presets`）

**关键设计**：
- `preset.yaml` 内嵌在 `plugin-l4d2-core/src/main/resources/preset.yaml`
- 4 个预设：多特战役、娱乐多特战役、纯净战役、官图肉鸽模式
- 平台映射：检测实例所在平台 → 选择对应平台插件
- 应用流程：
  1. `PluginInstallService.disableAllPlugins(instanceId)`
  2. 启用预设中的平台插件（优先）
  3. 启用预设中其他插件
  4. 应用预设中的 cfg 覆盖（如有）

**步骤**：

1. **创建 `preset.yaml`**（`plugin-l4d2-core/src/main/resources/preset.yaml`）：

```yaml
presets:
  - id: multi-versus
    name: 多特战役
    description: 8 人多特战役配置，特感刷新增强
    gameMode: versus
    maxPlayers: 8
    platform: 1.11插件平台
    enabledPlugins:
      - l4d2_ai_damagefix
      - l4d2_vs_new_item_spawn
      - l4d2_multi_slot
    disabledPlugins:
      - l4d_tool_equipment
    configOverrides:
      - file: cfg/sourcemod/l4d2_ai_damagefix.cfg
        items:
          ai_damage_multiplier: "1.5"
  - id: fun-versus
    name: 娱乐多特战役
    description: 娱乐向多特配置
    ...
  - id: pure-coop
    name: 纯净战役
    description: 仅基础插件，原版体验
    ...
  - id: official-roguelike
    name: 官图肉鸽模式
    description: 官方地图肉鸽玩法
    ...
```

2. **创建 `PresetService`**：

```java
@Service
public class PresetService {
    private final PluginInstallService pluginInstallService;  // Task 2.4
    private final SourceModCfgService cfgService;             // Task 2.1
    private List<PresetVO> presets;

    @PostConstruct
    public void loadPresetYaml();  // 用 Jackson YAML 解析 preset.yaml

    public List<PresetVO> list();
    public PresetDetailVO detail(String presetId);

    /** 应用预设 */
    public void apply(Long instanceId, String presetId) {
        // 1. pluginInstallService.disableAllPlugins(instanceId)
        // 2. 启用预设中的平台插件
        // 3. 启用预设中其他插件
        // 4. 应用 cfg 覆盖
    }
}
```

3. **创建 `PresetController`**：

```java
@RestController
@RequestMapping("/api/plugin/l4d2/presets")
public class PresetController {
    @GetMapping("/list")
    public Result<List<PresetVO>> list();

    @GetMapping("/{presetId}")
    public Result<PresetDetailVO> detail(@PathVariable String presetId);

    @PostMapping("/{presetId}/apply")
    public Result<Void> apply(@PathVariable String presetId, @RequestParam Long instanceId);
}
```

4. **重构 `PluginManageController`**（删除原有 PRESETS 静态列表与 `/presets`、`/apply-preset` 端点，由 `PresetController` 替代；保留兼容期到 Task 2.4）

5. **创建 `PresetDetailVO`**：

```java
@Data
public class PresetDetailVO {
    private String id;
    private String name;
    private String description;
    private String gameMode;
    private Integer maxPlayers;
    private String platform;
    private List<String> enabledPlugins;
    private List<String> disabledPlugins;
    private List<ConfigOverride> configOverrides;

    @Data
    public static class ConfigOverride {
        private String file;
        private Map<String, String> items;
    }
}
```

6. **测试**：
   - preset.yaml 解析：4 个预设
   - 应用预设：mock PluginInstallService，验证调用顺序

**验收**：
- 后端编译通过
- `GET /api/plugin/l4d2/presets/list` 返回 4 个预设
- 单测通过

**commit**：`feat(l4d2): preset system with yaml (phase 2.3)`

---

### Task 2.4: PluginManageController 重构 + PluginInstallService + PluginExportService

**目标**：重构插件管理，新增 fileRefs 引用计数、安装/卸载、批量操作、全量导出。

**对齐 spec**：§4 模块 7（端点 `/api/plugin/l4d2/plugins`）

**关键设计**：

#### PluginInstallService 核心方法

```java
@Service
public class PluginInstallService {
    InstallResult installFromUpload(Long instanceId, MultipartFile file);  // ZIP/RAR/7z/VPK
    void installFromLocalFile(Long instanceId, File localFile);            // 供 PluginStoreService 调用
    RconResult enableAndLoad(Long instanceId, String pluginName);          // 启用并 RCON 加载（失败回滚）
    RconResult disableAndUnload(Long instanceId, String pluginName);       // RCON 卸载并禁用
    List<PluginInfo> listPlugins(Long instanceId);
    List<String> listEnabledPlugins(Long instanceId);
    void disableAllPlugins(Long instanceId);                              // 供 PresetService 调用
    void enablePlatformPlugins(Long instanceId, String platform);
    void deletePlugin(Long instanceId, String pluginName);                // 含 fileRefs 引用计数
}
```

#### FileRefsService（引用计数）

- 持久化到 `addons/sourcemod/.file_refs.json`：`{filePath: [pluginName1, pluginName2, ...]}`
- 每次启动加载（懒加载）
- 删除插件时：从 map 移除该插件对每个共享文件的引用，归零则删除文件
- 共享文件类型：cfg、translations、models、sounds

#### PluginExportService

- 创建临时目录 `~/game-platform-l4d2/export-tasks/{taskId}/`
- 遍历 `addons/sourcemod/plugins/` 所有 .smx + 对应 cfg + translations
- 打包为 ZIP（保留 `left4dead2/` 根目录结构）
- 30 分钟过期清理
- 任务状态用内存 Map 跟踪（不持久化，重启失效可接受）

#### PluginManageController 端点

```
现有（重构）：
- GET  /list                          → 改为调用 PluginInstallService.listPlugins
- POST /upload                        → 改为调用 PluginInstallService.installFromUpload
- DELETE /{filename}                  → 改为调用 PluginInstallService.deletePlugin
- PUT  /{filename}/toggle             → 废弃，标记 @Deprecated，保留 1 个版本兼容

新增：
- POST /enable-load                   → enableAndLoad
- POST /disable-unload                → disableAndUnload
- POST /batch-enable                  → 批量启用
- POST /batch-disable                 → 批量禁用
- GET  /export-all/start              → 启动全量导出任务
- GET  /export-all/status             → 查询导出进度
- GET  /export-all/download           → 下载导出的 ZIP
- POST /export-all/cancel             → 取消导出

移除（迁移到 PresetController）：
- GET  /presets
- POST /apply-preset
```

**步骤**：

1. **创建 `PluginInstallService`**：实现上述方法。关键点：
   - `installFromUpload`：保存到临时文件 → 调用 `installFromLocalFile`
   - `installFromLocalFile`：检测 VPK magic（地图）vs ZIP/RAR/7z（插件）
     - VPK → 复制到 `addons/`
     - ZIP/RAR/7z → `ArchiveExtractUtil.extractZip`/`extract7z` 到临时目录 → 检测 `addons/sourcemod/plugins/*.smx` → 上传到远程
   - `enableAndLoad`：移动 .smx 到 plugins 目录 → RCON `sm plugins load {name}` → 失败则回滚（移回 disabled）
   - `disableAndUnload`：RCON `sm plugins unload {name}` → 移动到 disabled 目录
   - `deletePlugin`：删除 .smx → 调用 `FileRefsService.removePluginRefs` 删除归零的共享文件

2. **创建 `FileRefsService`**：
   - `loadRefs(instanceId)`：读取 `.file_refs.json`（懒加载 + 缓存）
   - `addRefs(instanceId, pluginName, List<String> sharedFiles)`
   - `removeRefs(instanceId, pluginName)`：返回归零需删除的文件列表
   - `saveRefs(instanceId, Map)`

3. **创建 `PluginExportService`**：
   - 内存 Map 跟踪任务：`Map<String, PluginExportTaskVO>`
   - `startExport(instanceId)` → 返回 taskId
   - `getStatus(taskId)` → 返回 `PluginExportTaskVO`（status: RUNNING/COMPLETED/FAILED/CANCELLED）
   - `download(taskId)` → 返回 `Resource`（Spring `FileSystemResource`）
   - `cancel(taskId)`
   - `@Scheduled(fixedRate = 3600000)` 清理 30 分钟过期任务

4. **重构 `PluginManageController`**：
   - 注入 `PluginInstallService`、`PluginExportService`
   - 删除静态 PRESETS、`getPresetList`、`applyPreset`（迁移到 `PresetController`）
   - 删除 `pluginCache`、`scanPlugins`（改为调用 service）
   - 重构 `enablePlugin`/`disablePlugin`：保留为 `@Deprecated`，调用新 `enableAndLoad`/`disableAndUnload`

5. **创建 `BatchPluginOperationDTO`**：

```java
@Data
public class BatchPluginOperationDTO {
    @NotNull private Long instanceId;
    @NotEmpty private List<String> pluginNames;
}
```

6. **创建 `PluginExportTaskVO`**：

```java
@Data
public class PluginExportTaskVO {
    private String taskId;
    private Long instanceId;
    private String status;       // RUNNING/COMPLETED/FAILED/CANCELLED
    private int totalFiles;
    private int processedFiles;
    private String downloadUrl;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
```

7. **测试**：
   - `PluginInstallServiceTest`：mock FileAccessService + RconService，验证安装流程
   - `FileRefsServiceTest`：引用计数归零删除文件
   - `PluginExportServiceTest`：start → status COMPLETED → download

**验收**：
- 后端编译通过
- 单测全部通过
- `GET /api/plugin/l4d2/plugins/list` 返回真实插件列表（从远程主机扫描）

**commit**：`refactor(l4d2): plugin manage with install/export/filerefs (phase 2.4)`

---

### Task 2.5: 前端 PluginStore.vue

**目标**：插件商店浏览页面，支持搜索、详情查看、下载到当前实例。

**步骤**：

1. **追加 `pluginStoreApi`**（`api/index.ts`）：

```typescript
export const pluginStoreApi = {
  list: (params?: { keyword?: string; category?: string; page?: number; size?: number }) =>
    get<Array<{ pluginId: string; name: string; description?: string; category?: string; size?: number; updatedAt?: string }>>('/plugin-store/list', params),
  detail: (pluginId: string) => get<{
    pluginId: string
    name: string
    description?: string
    readme: string
    fileList: Array<{ path: string; size: number }>
  }>(`/plugin-store/${pluginId}`),
  readme: (pluginId: string) => get<string>(`/plugin-store/${pluginId}/readme`),
  download: (data: { instanceId: number; pluginId: string; targetPath?: string }) =>
    post<string>('/plugin-store/download', data),
  tasks: (instanceId: number) => get<Array<{ id: string; status: string; progress: number; filename: string }>>('/plugin-store/tasks', { instanceId }),
  cancelTask: (taskId: string) => post<void>(`/plugin-store/tasks/${taskId}/cancel`),
}
```

2. **创建 `PluginStore.vue`**：
   - 顶部：搜索框 + 分类筛选
   - 中部：插件卡片网格（name + description + size + updatedAt + 下载按钮）
   - 详情抽屉：点击卡片展示 README（用 `marked` + `dompurify` 渲染）+ 文件列表 + 下载按钮
   - 底部：下载任务列表（实时进度条）

3. **追加路由**：

```typescript
{
  path: '/plugin-store',
  name: 'PluginStore',
  component: () => import('@/pages/PluginStore.vue'),
  meta: { title: '插件商店', icon: 'ShoppingBag' }
}
```

4. **新增依赖**（`package.json`）：

```json
{
  "marked": "^12.0.0",
  "dompurify": "^3.0.0"
}
```

**验收**：
- 页面渲染正常，搜索/筛选可用
- README Markdown 正确渲染
- 下载任务进度实时更新（轮询 tasks 接口）
- `npm run build` 通过

**commit**：`feat(l4d2-fe): plugin store page (phase 2.5)`

---

### Task 2.6: 前端 PluginConfig.vue

**目标**：SourceMod cfg 配置项编辑页面。

**步骤**：

1. **追加 `pluginConfigApi`**（`api/index.ts`）：

```typescript
export const pluginConfigApi = {
  get: (instanceId: number, pluginName: string) => get<{
    pluginName: string
    configName?: string
    configPath?: string
    items: Array<{ key: string; value: string; defaultValue?: string; min?: number; max?: number; description?: string; lineNumber: number }>
    rawContent?: string
    lastSyncedAt?: string
  }>('/plugin-config/get', { instanceId, pluginName }),
  update: (data: { instanceId: number; pluginName: string; items: Array<{ key: string; value: string; lineNumber: number }> }) =>
    post<void>('/plugin-config/update', data),
  candidates: (instanceId: number, pluginName: string) => get<Array<{ path: string; exists: boolean }>>('/plugin-config/candidates', { instanceId, pluginName }),
}
```

2. **创建 `PluginConfig.vue`**：
   - 顶部：插件选择下拉框（从 `pluginApi.getList` 获取）+ 刷新按钮
   - 中部：配置项表格（key + value 输入框 + defaultValue + min/max + description）
   - 底部：保存按钮 + 还原默认值按钮
   - 切换插件时重新加载

3. **追加路由**：

```typescript
{
  path: '/plugin-config',
  name: 'PluginConfig',
  component: () => import('@/pages/PluginConfig.vue'),
  meta: { title: '插件配置', icon: 'Setting' }
}
```

**验收**：
- 选择插件后正确加载配置项
- 修改 value 后保存成功
- "还原默认值"按钮可重置所有 value 为 defaultValue
- `npm run build` 通过

**commit**：`feat(l4d2-fe): plugin config page (phase 2.6)`

---

### Task 2.7: 前端 Preset.vue

**目标**：预设场景应用页面。

**步骤**：

1. **追加 `presetApi`**（`api/index.ts`）：

```typescript
export const presetApi = {
  list: () => get<Array<{ id: string; name: string; description?: string; gameMode?: string; maxPlayers?: number }>>('/presets/list'),
  detail: (presetId: string) => get<{
    id: string
    name: string
    description?: string
    gameMode?: string
    maxPlayers?: number
    platform?: string
    enabledPlugins: string[]
    disabledPlugins: string[]
    configOverrides: Array<{ file: string; items: Record<string, string> }>
  }>(`/presets/${presetId}`),
  apply: (presetId: string, instanceId: number) => post<void>(`/presets/${presetId}/apply?instanceId=${instanceId}`),
}
```

2. **创建 `Preset.vue`**：
   - 顶部：实例选择提示（确认当前实例）
   - 中部：预设卡片网格（4 个预设：name + description + gameMode + maxPlayers + 应用按钮）
   - 详情抽屉：点击卡片展示 enabledPlugins/disabledPlugins/configOverrides
   - 应用按钮：确认弹窗 → 调用 apply → 进度提示

3. **追加路由**：

```typescript
{
  path: '/preset',
  name: 'Preset',
  component: () => import('@/pages/Preset.vue'),
  meta: { title: '预设场景', icon: 'MagicStick' }
}
```

**验收**：
- 4 个预设正确展示
- 详情抽屉展示插件列表
- 应用后弹出成功提示
- `npm run build` 通过

**commit**：`feat(l4d2-fe): preset page (phase 2.7)`

---

### Task 2.8: 前端 Plugins.vue 重构

**目标**：重构插件管理页面，对接新的 `PluginInstallService` 端点，新增批量操作和导出功能。

**步骤**：

1. **追加 `pluginManageApi`**（`api/index.ts`，与旧 `pluginApi` 并存）：

```typescript
export const pluginManageApi = {
  list: (instanceId: number) => get<PluginListVO[]>('/plugins/list', { instanceId }),
  upload: (file: File, instanceId: number, onProgress?: (percent: number) => void) =>
    upload<PluginListVO>(`/plugins/upload?instanceId=${instanceId}`, file, onProgress),
  delete: (instanceId: number, pluginName: string) => del<void>(`/plugins/${pluginName}?instanceId=${instanceId}`),
  enableLoad: (instanceId: number, pluginName: string) => post<void>('/plugins/enable-load', { instanceId, pluginName }),
  disableUnload: (instanceId: number, pluginName: string) => post<void>('/plugins/disable-unload', { instanceId, pluginName }),
  batchEnable: (data: { instanceId: number; pluginNames: string[] }) => post<void>('/plugins/batch-enable', data),
  batchDisable: (data: { instanceId: number; pluginNames: string[] }) => post<void>('/plugins/batch-disable', data),
  exportAllStart: (instanceId: number) => get<{ taskId: string }>('/plugins/export-all/start', { instanceId }),
  exportAllStatus: (instanceId: number) => get<{
    taskId: string
    status: string
    totalFiles: number
    processedFiles: number
    downloadUrl?: string
    error?: string
  }>('/plugins/export-all/status', { instanceId }),
  exportAllDownload: (instanceId: number) => `/api/plugin/l4d2/plugins/export-all/download?instanceId=${instanceId}`,
  exportAllCancel: (instanceId: number) => post<void>('/plugins/export-all/cancel', { instanceId }),
}
```

2. **重构 `Plugins.vue`**：
   - 顶部：上传插件 + 批量启用 + 批量禁用 + 全量导出 + 刷新
   - 表格：复选框（批量操作） + 插件名 + 状态 + 操作（启用/禁用切换 + 配置链接 + 删除）
   - "配置"按钮跳转到 `/plugin-config?pluginName=xxx`
   - 全量导出：点击后弹窗显示进度（轮询 status），完成后提供下载链接

3. **删除旧预设对话框**（迁移到 `Preset.vue`）

4. **保留旧 `pluginApi` 一段时间**（兼容期），新增 `pluginManageApi` 用于新端点

**验收**：
- 插件列表正确展示（含状态）
- 启用/禁用切换通过 `enableLoad`/`disableUnload` 工作
- 批量勾选后批量启用/禁用成功
- 全量导出进度显示正常，可下载
- "配置"按钮跳转到 PluginConfig 页面并预选插件
- `npm run build` 通过

**commit**：`refactor(l4d2-fe): plugins page with batch/export (phase 2.8)`

---

### Task 2.9: Phase 2 集成验证

**目标**：验证 Phase 2 全部功能集成正确，工作树干净。

**步骤**：

1. **后端编译 + 测试**：

```bash
cd backend
mvn install -pl api,plugin,core -am -DskipTests
mvn test -pl plugin-l4d2/plugin-l4d2-core
```

2. **前端构建**：

```bash
cd backend/plugin-l4d2/frontend
npm run build
```

3. **UI 资源更新**：构建产物已通过 `vite.config.ts` 的 `outDir` 打入 `plugin-l4d2-core/src/main/resources/ui/`

4. **Git 提交**：
   - 在 `backend/` 目录提交所有变更
   - 在主仓库提交 submodule 更新

5. **手动验证清单**：
   - [ ] `GET /api/plugin/l4d2/plugin-config/get` 返回正确格式
   - [ ] `GET /api/plugin/l4d2/plugin-store/list` 返回商店列表（需网络）
   - [ ] `GET /api/plugin/l4d2/presets/list` 返回 4 个预设
   - [ ] `GET /api/plugin/l4d2/plugins/list` 返回插件列表
   - [ ] `POST /api/plugin/l4d2/plugins/enable-load` 启用并加载成功
   - [ ] `GET /api/plugin/l4d2/plugins/export-all/start` 启动导出任务
   - [ ] 前端 4 个页面（PluginStore/PluginConfig/Preset/Plugins）渲染正常

**验收**：
- 后端编译 + 所有测试通过
- 前端构建通过
- 工作树干净（`git status` 无未提交变更）

**commit**：`chore(l4d2): phase 2 integration verification`

---

## 5. 执行顺序与依赖

```
Task 2.1 (PluginConfig)  ─────┐
                              ├──> Task 2.4 (PluginManage 重构)
Task 2.3 (Preset)        ─────┤    需要 PluginInstallService（2.4 实现）
   需要 PluginInstallService  │    PresetService.apply 调用 disableAllPlugins
                              │
Task 2.2 (PluginStore)   ─────┘
   需要 PluginInstallService（2.4 实现）

Task 2.4 完成后 → Task 2.5/2.6/2.7/2.8（前端，可并行）
Task 2.9（集成验证）
```

**建议执行顺序**：
1. Task 2.4 先行（提供 PluginInstallService 基础）
2. Task 2.1（PluginConfig，独立）
3. Task 2.3（Preset，依赖 2.4）
4. Task 2.2（PluginStore，依赖 2.4）
5. Task 2.5-2.8（前端，可并行，建议用 subagent）
6. Task 2.9（集成验证）

**实际上 Task 2.4 的 PluginInstallService 是关键基础，Task 2.2/2.3 都依赖它。但为了减少 plan 文档的复杂度，可以按 2.1 → 2.4 → 2.2 → 2.3 顺序执行。**

---

## 6. 关键参考代码

### 6.1 ExtensionClient 用法（来自 BackupService）

```java
// 列表查询
ListOptions opts = ListOptions.builder()
    .specFilter("$.instanceId", "=", instanceId)
    .build();
List<PluginBackupResource> list = extensionClient.list(PluginBackupResource.class, opts);

// 创建（注意 create 返回 void）
PluginBackupResource resource = new PluginBackupResource();
PluginBackupSpec spec = new PluginBackupSpec();
spec.setInstanceId(instanceId);
resource.setSpec(spec);
resource.setName(slugify(instanceId + "-" + name));
extensionClient.create(resource);
return resource;

// 按 ID 查询
PluginBackupResource backup = extensionClient.getById(PluginBackupResource.class, backupId).orElse(null);

// 按 ID 删除
extensionClient.deleteById(PluginBackupResource.class, backupId);

// 更新
backup.getSpec().setName(newName);
backup.setName(slugify(spec.getInstanceId() + "-" + newName));
extensionClient.update(backup);
```

### 6.2 FileAccessService 用法（来自 BackupService）

```java
// 读文本文件（GBK）
String content = fileAccessService.readTextFile(hostId, path, gbk);

// 写文本文件（默认 UTF-8，写 GBK 时需传 Charset）
fileAccessService.writeTextFile(hostId, path, content, gbk);

// 列出目录
List<FileInfo> files = fileAccessService.listFiles(hostId, path);

// 上传文件
fileAccessService.uploadFile(hostId, targetPath, multipartFile);

// 删除文件
fileAccessService.deleteFile(hostId, path);
```

### 6.3 L4D2PathResolver 用法

```java
// 已有方法：
pathResolver.getSourceModPluginsPath(instance);           // .../addons/sourcemod/plugins
pathResolver.getSourceModPluginsDisabledPath(instance);   // .../addons/sourcemod/plugins/disabled
pathResolver.getSourceModConfigsPath(instance);           // .../addons/sourcemod/configs
pathResolver.getSourceModCfgPath(instance);               // .../cfg/sourcemod
pathResolver.getFileRefsPath(instance);                   // .../addons/sourcemod/.file_refs.json
```

### 6.4 RconService 用法

```java
// 执行任意 RCON 命令
String result = rconService.executeCommand(host, port, password, "sm plugins load " + pluginName);
rconService.executeCommand(host, port, password, "sm plugins unload " + pluginName);
rconService.executeCommand(host, port, password, "sm plugins reload_all");
```

### 6.5 ExternalHttpClient 用法

```java
// GET JSON
T result = httpClient.getForObject(url, T.class, params);

// POST JSON
T result = httpClient.postForObject(url, body, T.class);

// 下载文件（含进度回调 + 取消）
File localFile = httpClient.download(url, filename, referer, 
    downloadedBytes -> updateProgress(downloadedBytes),
    () -> isCancelled);
```

### 6.6 前端 API 模式（来自 api/index.ts）

```typescript
import { get, post, put, del, upload } from './request'

export const xxxApi = {
  list: (instanceId: number) => get<XXX[]>('/xxx/list', { instanceId }),
  create: (data: XXXDTO) => post<{ id: string }>('/xxx/create', data),
  update: (data: XXXDTO) => post<void>('/xxx/update', data),
  delete: (id: string) => del<void>(`/xxx/${id}`),
}
```

### 6.7 前端 Store 用法

```typescript
import { usePluginStore } from '@/stores/plugin'
const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// 通知
store.notifySuccess('标题', '内容')
store.notifyError('标题', '内容')

// 确认框
const confirmed = await store.confirm('标题', '内容')
```

---

## 7. 风险与降级

| 风险 | 影响 | 降级方案 |
|------|------|---------|
| GitHub API 限流（60/h 未认证） | 商店列表加载失败 | 缓存 10 分钟 + 错误提示用户稍后重试 |
| Git LFS 流量限制 | 下载失败 | 错误提示 + 复制直链让用户手动下载 |
| 实例未启动 RCON 失败 | enable/disable 失败 | 错误提示 + 保留文件操作（仅跳过 RCON load） |
| 大文件上传超时 | 上传失败 | 提示用分片上传（Phase 3 实现） |
| preset.yaml 解析失败 | 预设不可用 | 启动失败日志 + 返回空列表 |

---

## 8. 完成标准

- [ ] Task 2.1-2.9 全部完成
- [ ] 后端编译通过，所有单测通过
- [ ] 前端构建通过
- [ ] 工作树干净
- [ ] 主 plan 文档中 Phase 2 标记为已完成
- [ ] project_memory.md 更新（如有新约定）

---

*创建日期：2026-07-19*
*预计完成：Phase 2 完成后进入 Phase 3（地图管理增强 + 分片上传 + 下载器）*
