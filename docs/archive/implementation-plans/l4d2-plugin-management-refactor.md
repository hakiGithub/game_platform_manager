# L4D2 插件管理重构计划：对齐 l4d2-server-next 设计

## 摘要

参考开源项目 `l4d2-server-next` 的设计，对 `plugin-l4d2` 模块的插件管理进行全量重构。本次改造覆盖 7 个方面：**存储模型、插件来源、删除语义、回滚机制、预设、商店、配置编辑**。

核心改造方向：
1. **存储模型**：从"游戏目录直接管理"改为"插件库目录 + 游戏目录分离"
2. **插件来源**：引入 `source` 字段，区分 `panel`（预设内建）/`store`（商店下载）/`upload`（用户上传）
3. **删除语义**：基于内存引用计数（`fileRefs Map`）实现归零物理删除
4. **回滚机制**：RCON 加载/卸载失败时自动回滚文件状态
5. **预设**：`preset.yaml` 结构对齐 l4d2-server-next（`platform` 平台插件优先 + `configs` 内嵌配置覆盖）
6. **商店**：改用 GitHub Trees API + 临时目录原子提交 + Git LFS 支持 + 代理/Token
7. **配置编辑**：CVAR 解析对齐（Default/Min/Max 元数据 + l4d_/l4d2_ 前缀互转），双模式（临时设置 RCON + 持久化保存）

---

## 当前状态分析

### 已存在的后端服务（均需重写）

| 服务 | 文件路径 | 当前实现 | 与 l4d2-server-next 差距 |
|------|----------|----------|--------------------------|
| `PluginInstallService` | `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` | 直接在 `addons/sourcemod/plugins/` 与 `disabled/` 间移动 .smx | 无插件库目录概念，无 source 字段，无引用计数删除 |
| `FileRefsService` | `.../service/FileRefsService.java` | 持久化到远程 `.file_refs.json` 文件 | 应改为内存 Map + 从 `enabled_plugins.yaml` 懒加载重建 |
| `PresetService` | `.../service/PresetService.java` | 4 个预设，已实现 apply 流程 | `preset.yaml` 结构需对齐（platform 字段、configs 内嵌） |
| `PluginStoreService` | `.../service/PluginStoreService.java` | GitHub API + 10min 缓存 + 3 并发 | 缺 Trees API + 临时目录原子提交 + LFS + 代理/Token |
| `SourceModCfgService` | `.../service/SourceModCfgService.java` | GBK 解析 + ExtensionClient 持久化 | 缺 Default/Min/Max 元数据解析、l4d_/l4d2_ 前缀互转、RestoreSourceModConfig |
| `L4D2PathResolver` | `.../resolver/L4D2PathResolver.java` | 13 个路径方法 | 缺插件库目录路径方法 |
| `L4D2Extension` | `.../L4D2Extension.java` | 4 个生命周期回调为空 | 应在 `onInstanceCreate` 初始化插件库目录与 `enabled_plugins.yaml` |

### 已存在的前端页面（均需重写）

| 页面 | 路径 | 行数 | 主要缺口 |
|------|------|------|----------|
| `Plugins.vue` | `backend/plugin-l4d2/frontend/src/pages/Plugins.vue` | 501 | 缺 source 列、热加载/卸载下拉、预设入口、商店入口、README 详情、双 Tab（已启用/未启用） |
| `Preset.vue` | `.../pages/Preset.vue` | 303 | 需对齐新 preset.yaml 结构 |
| `PluginConfig.vue` | `.../pages/PluginConfig.vue` | 313 | 缺临时设置（RCON sm_cvar）、Default/Min/Max Tag 展示 |
| `PluginStore.vue` | `.../pages/PluginStore.vue` | 485 | 缺 GitHub Token/代理/自定义仓库、下载进度轮询 |

### 关键基础设施（保留不动）

- **`InstanceFileService` SPI**（17 个方法）：`backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java` — 完整保留
- **`ExtensionClient`**：插件持久化统一入口，保留用于 `PluginConfigResource`、新增 `EnabledPluginResource`
- **`PluginExportService`**：异步导出任务，已实现完整，保留不动
- **数据库表 `l4d2_plugin_config`**：当前未使用，本次重构后可清理（持久化改为扩展资源 + enabled_plugins.yaml）

---

## 核心设计决策

### 决策 1：插件库目录的物理位置

**采用方案**：插件库目录位于游戏数据根目录下的 `addons/sourcemod/plugins_store/`，每个插件一个子目录。

```
{游戏数据根}/
└── left4dead2/
    └── addons/
        └── sourcemod/
            ├── plugins/                    ← 游戏目录（启用时才有的 .smx）
            ├── plugins_store/              ← 插件库目录（新增）
            │   ├── {插件名A}/
            │   │   ├── README.md
            │   │   ├── plugin.yaml         ← 元数据（source/fileList/configFiles）
            │   │   └── left4dead2/         ← 完整文件树（与游戏目录结构一致）
            │   │       ├── addons/sourcemod/plugins/A.smx
            │   │       ├── addons/sourcemod/configs/A.cfg
            │   │       └── cfg/sourcemod/A.cfg
            │   └── {插件名B}/...
            ├── .enabled_plugins.yaml        ← 已启用插件清单（新增）
            └── .file_refs.json              ← 废弃（改为内存 Map）
```

**理由**：
- 与游戏目录同级，便于通过 `InstanceFileService` 操作
- 与 l4d2-server-next 的 `./plugins/{name}/left4dead2/` 结构一致
- `plugin.yaml` 存储元数据（source、fileList、configFiles），对齐 l4d2-server-next 的 `plugins.yaml` 设计但按插件独立存储

### 决策 2：enabled_plugins.yaml 数据结构

```yaml
# 文件路径：{游戏数据根}/left4dead2/addons/sourcemod/.enabled_plugins.yaml
enabled_plugins:
  - name: "插件名A"
    source: "store"           # panel / store / upload
    enabled_at: 1711084800000
    files:                    # 该插件启用时复制到游戏目录的所有文件（相对 left4dead2/）
      - "addons/sourcemod/plugins/A.smx"
      - "addons/sourcemod/configs/A.cfg"
      - "cfg/sourcemod/A.cfg"
  - name: "插件名B"
    source: "upload"
    enabled_at: 1711084900000
    files:
      - "addons/sourcemod/plugins/B.smx"
```

**理由**：与 l4d2-server-next 的 `plugins.yaml` 结构一致，`fileRefs` 内存 Map 从此文件懒加载重建。

### 决策 3：preset.yaml 新结构

```yaml
# backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml
platform:
  linux: "1.11插件平台linux版"     # 平台插件名关键字（用于在 plugins_store 中查找）
  windows: "1.11插件平台windows版"

presets:
  - id: multi-versus
    name: 多特战役
    description: 8 人多特战役配置，特感刷新增强
    gameMode: versus
    maxPlayers: 8
    plugins:
      - name: "l4d2_ai_damagefix"
        configs:
          - name: "l4d2_ai_damagefix.cfg"
            values:
              ai_damage_multiplier: "1.5"
      - name: "l4d2_multi_slot"
        configs: []
  - id: pure-coop
    name: 纯净战役
    description: 纯净战役，新人开服必备
    gameMode: coop
    maxPlayers: 4
    plugins: []
```

**与当前结构差异**：
- 新增顶层 `platform` 字段（按 OS 区分平台插件）
- `enabledPlugins` + `disabledPlugins` 改为单一的 `plugins` 列表（应用时先禁用所有再启用列表中的）
- `configOverrides` 改为每个插件内嵌的 `configs` 列表（结构更清晰）

### 决策 4：fileRefs 内存化

`FileRefsService` 不再持久化到 `.file_refs.json`，改为：
- `Map<Long, Map<String, Set<String>>> refsCache`：实例级缓存
- 首次访问时从 `.enabled_plugins.yaml` 重建（遍历 `enabled_plugins[].files`，每个文件路径 → 引用它的插件名集合）
- 进程重启后自动重建，无需远程文件
- 路径标准化：`normalizeRelPath` 统一 `\` → `/` 并转小写

### 决策 5：扩展资源 EnabledPluginResource

新增扩展资源 `EnabledPluginResource`（通过 ExtensionClient 持久化），用于：
- 记录每个实例的已启用插件及其元数据（source、enabledAt、files）
- 替代 `.enabled_plugins.yaml` 的远程文件持久化（数据库存储更可靠）

**最终决策**：采用 `.enabled_plugins.yaml` 远程文件 + `EnabledPluginResource` 扩展资源**双写**策略：
- `.enabled_plugins.yaml` 是事实来源（source of truth），fileRefs 从此重建
- `EnabledPluginResource` 用于前端列表展示的快速查询（避免每次都读远程文件）

---

## 提议的变更

### 阶段 1：路径与生命周期基础

#### 1.1 扩展 L4D2PathResolver

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java`

新增方法：
```java
public String getPluginsStorePath() {
    return getSourceModPath() + "/plugins_store";  // addons/sourcemod/plugins_store
}
public String getPluginStorePath(String pluginName) {
    return getPluginsStorePath() + "/" + pluginName;
}
public String getPluginLeft4Dead2Path(String pluginName) {
    return getPluginStorePath(pluginName) + "/left4dead2";
}
public String getEnabledPluginsYamlPath() {
    return getSourceModPath() + "/.enabled_plugins.yaml";
}
public String getPluginYamlPath(String pluginName) {
    return getPluginStorePath(pluginName) + "/plugin.yaml";
}
public String getPluginReadmePath(String pluginName) {
    return getPluginStorePath(pluginName) + "/README.md";
}
```

**保留**现有所有方法（`getSourceModPluginsPath` 等仍用于游戏目录操作）。

#### 1.2 L4D2Extension 生命周期回调

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java`

`onInstanceCreate`：初始化插件库目录与 `.enabled_plugins.yaml`（通过 InstanceFileService 创建空目录和空 yaml 文件）。
`onInstanceDelete`：清理扩展资源（EnabledPluginResource、PluginConfigResource 中该实例的数据）。
`onInstanceStart`/`onInstanceStop`：保留日志输出，无业务逻辑。

### 阶段 2：核心服务重写

#### 2.1 重写 FileRefsService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java`

**核心变更**：
- 删除 `.file_refs.json` 持久化逻辑（`saveRefs` / `loadFromRemote`）
- 新增 `rebuildFromEnabledPlugins(Long instanceId, List<EnabledPlugin> enabledPlugins)`：从 `.enabled_plugins.yaml` 重建内存 Map
- `loadRefs` 改为：先从缓存取，缓存未命中时调用 `EnabledPluginsService.loadYaml()` 重建
- 新增 `normalizeRelPath(String relPath)`：转小写 + `\` 转 `/`
- `addRefs` / `removeRefs` 仅操作内存 Map，不再写远程文件
- `removeRefs` 返回归零文件列表的逻辑保持不变

**新签名**：
```java
public Map<String, Set<String>> loadRefs(Long instanceId);
public void addRefs(Long instanceId, String pluginName, List<String> sharedFiles);
public List<String> removeRefs(Long instanceId, String pluginName);
public void rebuild(Long instanceId);  // 强制重建（启用/禁用后调用）
```

#### 2.2 新增 EnabledPluginsService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java`

**职责**：管理 `.enabled_plugins.yaml` 远程文件的读写 + `EnabledPluginResource` 扩展资源双写。

```java
@Service
public class EnabledPluginsService {
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final ExtensionClient extensionClient;
    private final ObjectMapper yamlMapper;

    // 从远程 yaml 加载已启用插件列表
    public List<EnabledPlugin> loadYaml(Long instanceId);

    // 保存到远程 yaml + 同步到扩展资源
    public void saveYaml(Long instanceId, List<EnabledPlugin> plugins);

    // 添加一个已启用插件
    public void add(Long instanceId, EnabledPlugin plugin);

    // 移除一个已启用插件
    public void remove(Long instanceId, String pluginName);

    // 查询插件是否已启用
    public boolean isEnabled(Long instanceId, String pluginName);

    // 列出所有已启用插件（优先从扩展资源查，fallback 到 yaml）
    public List<EnabledPlugin> list(Long instanceId);
}
```

**`EnabledPlugin` 数据类**：
```java
@Data
public class EnabledPlugin {
    private String name;
    private String source;        // panel / store / upload
    private Long enabledAt;
    private List<String> files;   // 相对 left4dead2/ 的文件路径
}
```

#### 2.3 新增 EnabledPluginResource 扩展资源

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resource/EnabledPluginResource.java`

```java
@Data
@EqualsAndHashCode(callSuper = true)
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class EnabledPluginResource extends AbstractExtension<EnabledPluginResource> {
    private Long instanceId;
    private String pluginName;
    private String source;          // panel / store / upload
    private Long enabledAt;
    private List<String> files;     // JSON 数组
}
```

**文件**：`.../resource/EnabledPluginSpec.java`（对应 Spec 类，与 PluginConfigSpec 结构类似）

**注册**：在 `L4D2Extension.getManifest()` 中追加此资源类型。

#### 2.4 重写 PluginInstallService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`

**核心变更**：
- 删除 `scanSmxFiles`（扫描游戏目录的逻辑）
- 删除 `getSourceModPluginsDisabledPath` 相关逻辑
- 新增 `pluginStorePath` 概念：所有上传/下载的插件先入库（`plugins_store/{name}/`）

**新方法签名**：
```java
public class PluginInstallService {
    // 列出所有插件（扫描 plugins_store 目录，合并 enabled_plugins.yaml 状态）
    public List<PluginListVO> listPlugins(Long instanceId);

    // 上传安装：解压到 plugins_store/{name}/，写入 plugin.yaml（source=upload）
    public PluginListVO installFromUpload(Long instanceId, MultipartFile file);

    // 启用插件：复制 plugins_store/{name}/left4dead2/* 到游戏目录 + 更新 enabled_plugins.yaml + fileRefs.addRefs
    public void enablePlugin(Long instanceId, String pluginName);

    // 启用并 RCON 加载：enablePlugin + sm plugins load（失败回滚：删除已复制文件 + 移除 enabled_plugins 记录）
    public void enableAndLoad(Long instanceId, String pluginName);

    // 禁用插件：按 fileRefs.removeRefs 归零删除游戏目录文件 + 移除 enabled_plugins.yaml 记录
    public void disablePlugin(Long instanceId, String pluginName);

    // 禁用并 RCON 卸载：sm plugins unload + disablePlugin（失败回滚：重新启用）
    public void disableAndUnload(Long instanceId, String pluginName);

    // 批量启用
    public void enablePlugins(Long instanceId, List<String> pluginNames);

    // 批量禁用
    public void disablePlugins(Long instanceId, List<String> pluginNames);

    // 禁用所有插件（供 PresetService 调用）
    public void disableAllPlugins(Long instanceId);

    // 启用平台插件（供 PresetService 调用）：从 plugins_store 中查找名称含 platform 的插件
    public void enablePlatformPlugin(Long instanceId, String platformKeyword);

    // 删除插件：先 disable（如果在 enabled），再删除 plugins_store/{name}/ 整个目录
    public void deletePlugin(Long instanceId, String pluginName);

    // 列出已启用插件名（仅 enabled_plugins.yaml 中的）
    public List<String> listEnabledPluginNames(Long instanceId);

    // 供 PluginStoreService 调用：从本地 ZIP 安装到 plugins_store
    public void installFromLocalFile(Long instanceId, File localFile, String source);
}
```

**关键实现细节**：

1. **`installFromUpload` ZIP 解压逻辑**：
   - 检测 ZIP 结构：单插件（根目录有 `left4dead2/`）vs 多插件（每个一级目录是一个插件）
   - 单插件：解压到 `plugins_store/{zipBasename}/`
   - 多插件：循环解压每个一级目录到 `plugins_store/{dirName}/`
   - 写入 `plugin.yaml`：`source=upload`，扫描 `left4dead2/addons/sourcemod/plugins/*.smx` 填入 fileList
   - 自动忽略 `__MACOSX/` 和 `.DS_Store`

2. **`enablePlugin` 复制逻辑**：
   ```java
   public void enablePlugin(Long instanceId, String pluginName) {
       String storeLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
       List<FileInfo> files = instanceFileService.listFiles(instanceId, storeLeft4Dead2);
       List<String> copiedFiles = new ArrayList<>();
       for (FileInfo f : recursivelyCollect(files, storeLeft4Dead2)) {
           String relPath = storeLeft4Dead2 + "/" + f.getRelativePath();  // 相对 left4dead2/
           String gamePath = "left4dead2/" + f.getRelativePath();
           instanceFileService.copyFile(instanceId, relPath, gamePath);
           copiedFiles.add(gamePath);
       }
       EnabledPlugin ep = new EnabledPlugin();
       ep.setName(pluginName);
       ep.setSource(readSourceFromPluginYaml(pluginName));
       ep.setEnabledAt(System.currentTimeMillis());
       ep.setFiles(copiedFiles);
       enabledPluginsService.add(instanceId, ep);
       fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
       fileRefsService.rebuild(instanceId);
   }
   ```

3. **`enableAndLoad` 回滚逻辑**：
   ```java
   public void enableAndLoad(Long instanceId, String pluginName) {
       // 1. 先复制文件
       List<String> copiedFiles = copyFilesToGameDir(instanceId, pluginName);
       // 2. RCON 加载
       try {
           String output = rconService.executeCommand(instanceId, "sm plugins load " + pluginName);
           if (isLoadFailed(output)) {
               // 3. 回滚：删除已复制文件
               rollbackCopiedFiles(instanceId, copiedFiles);
               throw new L4D2PluginException(...);
           }
           // 4. 成功：更新 enabled_plugins.yaml
           enabledPluginsService.add(instanceId, buildEnabledPlugin(pluginName, copiedFiles));
           fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
           fileRefsService.rebuild(instanceId);
       } catch (Exception e) {
           rollbackCopiedFiles(instanceId, copiedFiles);
           throw new L4D2PluginException(...);
       }
   }
   ```

#### 2.5 重写 PluginListVO

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java`

```java
@Data
public class PluginListVO {
    private String name;                    // 改为 name（对齐 l4d2-server-next）
    private String status;                  // enabled / disabled
    private String source;                  // panel / store / upload（新增）
    private Boolean hasSmx;                 // 是否包含 .smx 文件（新增）
    private Boolean hasConfig;              // 是否包含 cfg 配置文件（新增）
    private String description;             // 从 README 第一段提取
    private String version;
    private String author;
    private List<String> fileList;
    private List<String> configFiles;
    private LocalDateTime enableTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 阶段 3：预设系统重写

#### 3.1 重写 preset.yaml

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml`

按决策 3 的新结构重写，保留 4 个预设（multi-versus / fun-versus / pure-coop / official-roguelike），新增 `platform` 顶层字段。

#### 3.2 重写 PresetService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`

**`apply` 方法新流程**（对齐 l4d2-server-next）：
```java
public void apply(Long instanceId, String presetId) {
    PresetDetailVO preset = detail(presetId);
    // 1. 校验所有插件存在于 plugins_store
    validatePluginsExist(instanceId, preset);
    // 2. 禁用当前所有已启用插件
    pluginInstallService.disableAllPlugins(instanceId);
    // 3. 启用平台插件（优先）
    String platform = getPlatformByOs();
    pluginInstallService.enablePlatformPlugin(instanceId, platform);
    // 4. 启用预设中的插件（跳过平台插件）
    for (PresetPlugin p : preset.getPlugins()) {
        if (p.getName().equals(platform)) continue;
        pluginInstallService.enablePlugin(instanceId, p.getName());
    }
    // 5. 应用配置覆盖
    for (PresetPlugin p : preset.getPlugins()) {
        for (PresetPluginConfig cfg : p.getConfigs()) {
            cfgService.updateOrCreateConfig(instanceId, cfg.getName(), cfg.getValues());
        }
    }
}
```

#### 3.3 重写 PresetDetailVO

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java`

```java
@Data
public class PresetDetailVO {
    private String id;
    private String name;
    private String description;
    private String gameMode;
    private Integer maxPlayers;
    private List<PresetPlugin> plugins;       // 改为内嵌 configs
    private Integer pluginCount;              // 前端展示用
}
```

### 阶段 4：插件商店重写

#### 4.1 重写 PluginStoreService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`

**核心改造**：
- 改用 GitHub Trees API：`GET /repos/{repo}/git/trees/master?recursive=1`
- 10 分钟缓存（保留现有逻辑）
- 下载流程：临时目录 → 原子提交（重命名）到 plugins_store
- 支持 Git LFS：检测 pointer 文件并通过 LFS Batch API 下载
- 支持代理（`proxyUrl`）和 Token（`githubToken`）和自定义仓库（`repo`）
- 并发下载（保留 `Semaphore(3)`）
- 任务去重：同一插件正在下载则返回现有进度

**新方法签名**：
```java
public class PluginStoreService {
    // 列出商店插件（forceRefresh 强制刷新缓存）
    public List<StorePluginVO> list(StoreListDTO dto);

    // 详情（README + 文件列表）
    public StorePluginDetailVO detail(String pluginName, StoreQueryDTO dto);

    // 下载并安装到指定实例的 plugins_store
    public StoreDownloadTaskVO download(StoreDownloadDTO dto);

    // 查询下载任务状态
    public List<StoreDownloadTaskVO> listTasks(Long instanceId);

    // 取消下载
    public void cancel(String taskId);
}
```

**`StoreListDTO` / `StoreQueryDTO` / `StoreDownloadDTO`**：
```java
@Data
public class StoreDownloadDTO {
    private Long instanceId;
    private String pluginName;
    private String proxyUrl;     // 可选
    private String githubToken;  // 可选
    private String repo;         // 默认 "LaoYutang/l4d2-plugins-store"
}
```

#### 4.2 下载任务状态机

```
PENDING → DOWNLOADING → INSTALLING → COMPLETED
                ↓           ↓
             FAILED      FAILED
                ↓           ↓
           CANCELLED    CANCELLED
```

- `PENDING`：任务已创建，等待执行
- `DOWNLOADING`：正在从 GitHub 下载文件到本地临时目录
- `INSTALLING`：正在通过 `InstanceFileService.uploadLocalFile` 上传到远程 `plugins_store/{name}/`
- `COMPLETED`：上传完成，写入 `plugin.yaml`（source=store）
- `FAILED`：下载或安装失败，清理临时目录
- `CANCELLED`：用户取消，清理临时目录

**临时目录位置**：`{user.home}/game-platform-l4d2/store-tasks/{taskId}/`

**原子提交**：本地下载完成后，先调用 `instanceFileService.exists` 检查远程 `plugins_store/{name}/` 是否存在，不存在则逐个上传文件，全部成功后写入 `plugin.yaml`。

### 阶段 5：配置编辑重写

#### 5.1 重写 SourceModCfgService

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`

**新增方法**：
```java
// 候选 cfg 发现：从 plugins_store/{name}/left4dead2/addons/sourcemod/plugins/ 扫描 .smx 推导 cfg 名
// 支持 l4d_ 与 l4d2_ 前缀互转
public List<String> getCandidatePaths(String pluginName);

// 临时设置：通过 RCON sm_cvar 即时生效
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue);

// 还原默认值：从注释中的 Default 元数据还原
public void restoreDefaults(Long instanceId, String pluginName);

// 创建或更新配置（供 PresetService 调用）：文件不存在时按完整 SourceMod 注释格式重建
public void updateOrCreateConfig(Long instanceId, String configName, Map<String, String> values);
```

#### 5.2 增强 SourceModCfgParser

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`

新增元数据解析（从注释中提取 Default/Min/Max）：
```java
public class SourceModCfgParser {
    private static final Pattern CVAR_REGEX = Pattern.compile("^\"?([a-zA-Z0-9_]+)\"?\\s+\"?([^\"]*)\"?");
    private static final Pattern DEFAULT_REGEX = Pattern.compile("(?i)^\\s*//\\s*Default:\\s*\"(.*)\"");
    private static final Pattern MIN_REGEX = Pattern.compile("(?i)^\\s*//\\s*Minimum:\\s*\"(.*)\"");
    private static final Pattern MAX_REGEX = Pattern.compile("(?i)^\\s*//\\s*Maximum:\\s*\"(.*)\"");

    private static final Set<String> CONSOLE_CMD_NAMES = Set.of("sm", "exec", "meta", "rcon");

    public List<ConfigItem> parse(String content);
    public String serialize(List<ConfigItem> items);  // 保留 Default/Min/Max 注释
    public String restoreFormat(List<ConfigItem> items);  // 按完整 SourceMod 注释格式重建
}
```

**`ConfigItem` 扩展**：
```java
@Data
public class ConfigItem {
    private String name;
    private String value;
    private String defaultValue;     // 新增：从 // Default: 注释提取
    private String min;              // 新增
    private String max;              // 新增
    private String description;
}
```

### 阶段 6：前端重写

#### 6.1 重写 Plugins.vue

**文件**：`backend/plugin-l4d2/frontend/src/pages/Plugins.vue`

**新结构**（对齐 l4d2-server-next）：
- **顶部操作区**：应用预设 / 导出所有插件 / 刷新
- **双 Tab**：
  - 已启用插件（批量禁用、配置、详情、禁用并立即卸载）
  - 未启用插件（批量启用、批量删除、上传 ZIP、打开插件商店、启用并立即加载）
- **表格列**：插件名称 / 来源（panel/store/upload Tag）/ 操作
- **上传**：仅接受 `.zip`，100ms 防抖合并多文件
- **预设 Modal**：单选预设 + 警示 + 双重确认
- **商店 Drawer**：自定义仓库 / 代理 / Token / 搜索 / 安装状态筛选 / 1s 轮询下载进度
- **导出 Modal**：进度条 + 取消 + 自动下载

**类型定义**：
```typescript
interface Plugin {
  name: string;
  status: 'enabled' | 'disabled';
  description?: string;
  source: 'panel' | 'store' | 'upload';
  hasSmx: boolean;
  hasConfig: boolean;
}
```

#### 6.2 重写 PluginConfig.vue

**文件**：`backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue`

**新功能**：
- 折叠面板展示多个 cfg 文件（accordion 模式）
- 每个 CVAR 卡片：name + Default/Min/Max Tag + Description
- 双按钮：「临时设置」（RCON sm_cvar）+ 「保存」（持久化）
- 「还原默认值」按钮

#### 6.3 重写 Preset.vue

**文件**：`backend/plugin-l4d2/frontend/src/pages/Preset.vue`

对齐新 preset.yaml 结构，展示 `plugins` 列表（含内嵌 configs），双重确认后应用。

#### 6.4 重写 PluginStore.vue

**文件**：`backend/plugin-l4d2/frontend/src/pages/PluginStore.vue`

**新功能**：
- 自定义仓库输入（`a-select mode="tags"`，默认 `LaoYutang/l4d2-plugins-store`，存 localStorage）
- GitHub 代理选择（6 个预设代理）
- GitHub Token 设置 Modal
- 搜索框 + 强制刷新按钮
- 安装状态筛选（全部/未安装/已安装）
- 1s 轮询下载进度
- 取消下载按钮

#### 6.5 更新 api/index.ts

**文件**：`backend/plugin-l4d2/frontend/src/api/index.ts`

新增/修改的 API：
```typescript
export const pluginManageApi = {
  list(instanceId),
  upload(files: File[], instanceId, onProgress),
  enable(instanceId, pluginName),
  enableAndLoad(instanceId, pluginName),
  enableBatch(instanceId, pluginNames),
  disable(instanceId, pluginName),
  disableAndUnload(instanceId, pluginName),
  disableBatch(instanceId, pluginNames),
  delete(instanceId, pluginName),
  readme(instanceId, pluginName),  // 新增
};

export const pluginConfigApi = {
  get(instanceId, pluginName),
  update(instanceId, pluginName, items),
  candidates(instanceId, pluginName),
  applyTemp(instanceId, cvarName, cvarValue),  // 新增
  restoreDefaults(instanceId, pluginName),     // 新增
};

export const pluginStoreApi = {
  list({ keyword, forceRefresh, proxyUrl, githubToken, repo }),  // 改签名
  detail(pluginName, { proxyUrl, githubToken, repo }),           // 改签名
  readme(pluginName, { proxyUrl, githubToken, repo }),           // 新增
  download({ instanceId, pluginName, proxyUrl, githubToken, repo }),  // 改签名
  tasks(instanceId),
  cancelTask(taskId),
};

export const presetApi = {
  list(),
  detail(presetId),
  apply(presetId, instanceId),
};
```

### 阶段 7：Controller 层调整

#### 7.1 调整 PluginManageController

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`

新增端点：
- `GET /plugins/{pluginName}/readme`：返回 README markdown

修改端点签名：
- `enable-load` → `enable-and-load`（对齐命名）
- `disable-unload` → `disable-and-unload`
- `batch-enable` 接收 `{instanceId, pluginNames}`
- `batch-disable` 接收 `{instanceId, pluginNames}`

#### 7.2 调整 PluginStoreController

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java`

修改端点签名以支持 `proxyUrl`/`githubToken`/`repo` 参数。

#### 7.3 调整 PluginConfigController

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`

新增端点：
- `POST /plugin-config/apply-temp`：临时设置 CVAR
- `POST /plugin-config/restore-defaults`：还原默认值

### 阶段 8：数据迁移与清理

#### 8.1 数据迁移脚本

**文件**：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java`

**迁移逻辑**（在 `L4D2Extension.onInstanceCreate` 或首次访问插件管理时触发）：
1. 检查 `plugins_store/` 目录是否存在，不存在则跳过（新实例）
2. 检查 `addons/sourcemod/plugins/*.smx` 中的文件
3. 对每个 .smx 文件：
   - 在 `plugins_store/` 下创建同名插件目录
   - 复制 .smx 到 `plugins_store/{name}/left4dead2/addons/sourcemod/plugins/{name}.smx`
   - 写入 `plugin.yaml`：`source=upload`，`fileList=[addons/sourcemod/plugins/{name}.smx]`
4. 检查 `disabled/` 目录，同样迁移
5. 删除游戏目录中的 .smx（迁移完成后）
6. 创建空的 `.enabled_plugins.yaml`

**幂等性**：迁移前检查 `plugins_store/` 是否已有内容，有则跳过。

#### 8.2 清理废弃代码

- 删除 `FileRefsService.saveRefs` / `loadFromRemote`（持久化到 .file_refs.json 的逻辑）
- 删除 `PluginInstallService.scanSmxFiles`（扫描游戏目录的逻辑）
- 删除 `PluginInstallService.getSourceModPluginsDisabledPath` 相关逻辑
- 删除数据库表 `l4d2_plugin_config`（已在 V1.4 中定义但未使用）
- 删除前端 `pluginApi`（旧版 API，已被 `pluginManageApi` 替代）
- 删除前端 `types/index.ts` 中的 `PluginInfo` / `PresetConfig` 旧版类型

---

## 假设与决策

### 假设

1. **远程文件操作成本高**：`InstanceFileService` 的每次调用都涉及 SSH/Docker，需尽量减少调用次数（如批量复制文件时先 `listFiles` 递归收集，再循环 `copyFile`）
2. **GBK 编码**：L4D2 cfg 文件统一使用 GBK 编码（保留现有 `GbkCodecUtil`）
3. **platform 插件按 OS 区分**：通过 `System.getProperty("os.name")` 判断，linux/windows 分别对应 preset.yaml 中的 key
4. **ZIP 结构**：参考 l4d2-server-next 的 `plugin-package.md` 规范，单插件 ZIP 根目录直接是 `left4dead2/`，多插件 ZIP 每个一级目录是一个插件
5. **GitHub Trees API 限制**：单次请求最多 100,000 个文件，超出需分页（实际 l4d2 插件仓库远小于此）

### 决策

1. **保留 PluginExportService 不动**：异步导出已完整实现，与新存储模型兼容（导出 `plugins_store/` 目录即可）
2. **保留 PluginConfigResource 扩展资源**：SourceModCfgService 继续通过 ExtensionClient 持久化配置
3. **新增 EnabledPluginResource 扩展资源**：用于前端列表快速查询，与 `.enabled_plugins.yaml` 双写
4. **不引入 PluginConfigAuditService**：本次不实现配置变更审计（用户未要求）
5. **删除 `l4d2_plugin_config` SQL 表**：迁移到扩展资源后清理（通过 V1.5 迁移脚本）

---

## 验证步骤

### 后端单元测试

1. **FileRefsServiceTest**：
   - `rebuild` 从 `enabled_plugins.yaml` 正确重建内存 Map
   - `addRefs` / `removeRefs` 正确维护引用计数
   - `removeRefs` 返回归零文件列表
   - `normalizeRelPath` 正确处理 `\` 和大小写

2. **EnabledPluginsServiceTest**：
   - `loadYaml` 正确解析 `.enabled_plugins.yaml`
   - `saveYaml` 正确序列化并同步到扩展资源
   - `add` / `remove` 正确维护列表

3. **PluginInstallServiceTest**：
   - `installFromUpload` 单插件 ZIP 正确解压到 `plugins_store/{name}/`
   - `installFromUpload` 多插件 ZIP 正确解压每个一级目录
   - `enablePlugin` 正确复制文件并更新 `enabled_plugins.yaml`
   - `enableAndLoad` RCON 失败时正确回滚（删除已复制文件）
   - `disablePlugin` 按引用计数删除归零文件
   - `disableAndUnload` RCON 失败时正确回滚（重新启用）
   - `deletePlugin` 删除 `plugins_store/{name}/` 整个目录

4. **PresetServiceTest**：
   - `apply` 正确按顺序执行：禁用全部 → 启用平台 → 启用预设插件 → 应用配置
   - 平台插件不存在时抛出明确异常

5. **PluginStoreServiceTest**：
   - `list` 10 分钟缓存生效
   - `download` 临时目录原子提交
   - `download` 任务去重
   - `cancel` 正确取消并清理临时目录

6. **SourceModCfgServiceTest**：
   - `getCandidatePaths` 从 .smx 推导 cfg 名 + l4d_/l4d2_ 前缀互转
   - `parse` 正确提取 Default/Min/Max 元数据
   - `applyTempConfig` 通过 RCON 执行 sm_cvar
   - `restoreDefaults` 从 Default 注释还原
   - `updateOrCreateConfig` 文件不存在时按完整格式重建

### 前端测试

1. **Plugins.vue**：
   - 双 Tab 正确切换
   - source Tag 颜色正确（panel 蓝 / store 绿 / upload 橙）
   - 上传 .zip 后列表刷新
   - 启用/禁用/删除/批量操作正常
   - 热加载/卸载下拉菜单显示
   - 预设 Modal 双重确认
   - 商店 Drawer 1s 轮询

2. **PluginConfig.vue**：
   - 折叠面板正确展开
   - Default/Min/Max Tag 显示
   - 临时设置按钮调用 RCON
   - 保存按钮持久化
   - 还原默认值

3. **PluginStore.vue**：
   - 自定义仓库 / 代理 / Token 持久化到 localStorage
   - 搜索过滤正确
   - 下载进度轮询

### 集成测试（手动）

1. **完整流程**：
   - 部署新实例 → 插件库目录自动初始化
   - 上传 ZIP 插件 → 出现在未启用列表（source=upload）
   - 启用插件 → 出现在已启用列表
   - 启用并立即加载 → RCON `sm plugins list` 确认加载成功
   - 禁用并立即卸载 → RCON `sm plugins list` 确认卸载
   - 删除插件 → plugins_store 目录被清除
   - 应用预设 → 所有插件重置为预设状态
   - 商店下载 → 自动安装到 plugins_store
   - 编辑 CVAR → 临时设置即时生效，保存持久化

2. **回滚验证**：
   - 故意上传损坏的 .smx → `enableAndLoad` 应回滚（文件删除，enabled_plugins.yaml 无记录）
   - RCON 连接失败 → `disableAndUnload` 应回滚（重新启用）

3. **引用计数验证**：
   - 上传两个共享相同 cfg 文件的插件
   - 启用两个插件 → cfg 文件存在
   - 禁用一个插件 → cfg 文件仍存在（引用未归零）
   - 禁用另一个插件 → cfg 文件被删除（引用归零）

---

## 实施顺序建议

按依赖关系分阶段实施（每阶段完成后可独立验证）：

1. **阶段 1**：L4D2PathResolver + L4D2Extension 生命周期（基础）
2. **阶段 2.1-2.3**：FileRefsService + EnabledPluginsService + EnabledPluginResource（核心基础设施）
3. **阶段 2.4-2.5**：PluginInstallService + PluginListVO（核心业务逻辑）
4. **阶段 8.1**：数据迁移脚本（确保现有实例可平滑升级）
5. **阶段 3**：PresetService + preset.yaml（依赖 PluginInstallService）
6. **阶段 5**：SourceModCfgService + SourceModCfgParser（独立于插件管理，但被 PresetService 依赖）
7. **阶段 4**：PluginStoreService（依赖 PluginInstallService.installFromLocalFile）
8. **阶段 7**：Controller 层调整（依赖所有 Service 完成）
9. **阶段 6**：前端重写（依赖后端 API 稳定）
10. **阶段 8.2**：清理废弃代码（最后执行）

---

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 数据迁移失败导致现有插件丢失 | 高 | 迁移前备份 `addons/sourcemod/plugins/` 目录；迁移脚本幂等，失败可重试 |
| 远程文件操作性能差（大量 SSH 调用） | 中 | 批量操作时先递归收集文件列表，再循环调用；考虑后续优化为 tar 打包传输 |
| GitHub API 限流 | 中 | 10 分钟缓存 + Token 支持 + 代理 fallback |
| RCON 命令执行超时 | 中 | 设置 5s 超时，失败时明确报错并回滚 |
| 前端 Wujie 模式下 localStorage 隔离 | 低 | 主应用通过 props 注入 token，子应用 localStorage 仅存非敏感配置 |
| 扩展资源表数据膨胀 | 低 | EnabledPluginResource 按实例隔离，实例删除时自动清理 |

---

## 文件变更清单

### 新增文件

| 文件 | 用途 |
|------|------|
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java` | 已启用插件管理（yaml + 扩展资源双写） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resource/EnabledPluginResource.java` | 已启用插件扩展资源 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resource/EnabledPluginSpec.java` | 扩展资源 Spec |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/EnabledPlugin.java` | 已启用插件数据类 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StorePluginVO.java` | 商店插件列表 VO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StorePluginDetailVO.java` | 商店插件详情 VO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StoreDownloadTaskVO.java` | 商店下载任务 VO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreListDTO.java` | 商店列表查询 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreQueryDTO.java` | 商店详情查询 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreDownloadDTO.java` | 商店下载 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java` | 数据迁移脚本 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsServiceTest.java` | 单元测试 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java` | 单元测试（重写） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java` | 单元测试（重写） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreServiceTest.java` | 单元测试（重写） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java` | 单元测试（重写） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/FileRefsServiceTest.java` | 单元测试（重写） |

### 修改文件

| 文件 | 变更说明 |
|------|----------|
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java` | 新增 6 个路径方法 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java` | 实现 onInstanceCreate/onInstanceDelete + 注册 EnabledPluginResource |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java` | 重写为内存 Map + 从 enabled_plugins.yaml 重建 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` | 重写为库/游戏分离模式 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java` | 对齐新 preset.yaml 结构 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java` | 改用 Trees API + LFS + 代理/Token |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java` | 增强 CVAR 解析 + 临时设置 + 还原默认 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java` | 新增 Default/Min/Max 解析 + restoreFormat |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java` | 字段对齐 l4d2-server-next |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java` | 改为内嵌 configs 结构 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/ConfigItem.java` | 新增 defaultValue/min/max 字段 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml` | 重写为新结构 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java` | 新增 readme 端点 + 调整签名 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java` | 调整端点签名 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java` | 新增 apply-temp / restore-defaults |
| `backend/plugin-l4d2/frontend/src/pages/Plugins.vue` | 重写为双 Tab + source + 热加载 + 预设/商店入口 |
| `backend/plugin-l4d2/frontend/src/pages/Preset.vue` | 对齐新结构 |
| `backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue` | 折叠面板 + 双按钮 + Default/Min/Max Tag |
| `backend/plugin-l4d2/frontend/src/pages/PluginStore.vue` | 自定义仓库/代理/Token + 轮询 |
| `backend/plugin-l4d2/frontend/src/api/index.ts` | 调整 API 签名 |

### 删除文件/代码

| 删除项 | 原因 |
|--------|------|
| `FileRefsService.saveRefs` / `loadFromRemote` | 改为内存 Map |
| `PluginInstallService.scanSmxFiles` | 改为扫描 plugins_store |
| `PluginInstallService.getSourceModPluginsDisabledPath` 相关逻辑 | 不再有 disabled 目录 |
| 前端 `pluginApi`（旧版） | 已被 `pluginManageApi` 替代 |
| 前端 `types/index.ts` 中的 `PluginInfo` / `PresetConfig` | 旧版类型 |
| 数据库表 `l4d2_plugin_config` | 改为扩展资源 |

---

## 附录：l4d2-server-next 关键设计参考

### 引用计数实现（plugins.go）

```go
// fileRefs 仅驻留内存，进程启动后首次使用时从 enabled_plugins 重建
var fileRefs map[string][]string

func rebuildFileRefs(enabledPlugins []PluginConfig) map[string][]string {
    refs := make(map[string][]string)
    for _, p := range enabledPlugins {
        for _, f := range p.Files {
            normPath := normalizeRelPath(f)
            // 去重后追加
            ...
        }
    }
    return refs
}

func DisablePlugin(name string) error {
    for _, relPath := range targetPlugin.Files {
        normPath := normalizeRelPath(relPath)
        newRefs := removeFromSlice(fileRefs[normPath], name)
        if len(newRefs) == 0 {
            os.Remove(filepath.Join(gamePath, relPath))
            delete(fileRefs, normPath)
        } else {
            fileRefs[normPath] = newRefs
        }
    }
}
```

### SourceMod CVAR 解析（config_parser.go）

```go
var cvarRegex = regexp.MustCompile(`^"?([a-zA-Z0-9_]+)"?\s+"?([^"]*)"?`)
var defaultRegex = regexp.MustCompile(`(?i)^\s*//\s*Default:\s*"(.*)"`)
var minRegex = regexp.MustCompile(`(?i)^\s*//\s*Minimum:\s*"(.*)"`)
var maxRegex = regexp.MustCompile(`(?i)^\s*//\s*Maximum:\s*"(.*)"`)

var consoleCmdNames = map[string]bool{
    "sm": true, "exec": true, "meta": true, "rcon": true,
}
```

### 商店下载原子提交（plugin_store.go）

```go
func (t *storePluginDownloadTask) run() {
    // 1. 下载到临时目录 .download_temp/{uuid}/
    t.downloadFiles()
    // 2. 检查 finalDir 不存在
    // 3. os.Rename(tempDir, finalDir)  // 原子提交
    // 4. writePluginSource(t.name, "store")
}
```

### 预设应用流程（preset.go）

```go
func ApplyPreset(presetName string) error {
    // 1. 校验所有插件存在于 plugins_store
    // 2. 禁用当前所有已启用插件
    DisablePlugins(toDisable)
    // 3. 启用平台插件（优先）
    EnablePlugin(platformPlugin)
    // 4. 启用预设中的其他插件
    for _, p := range targetPreset.Plugins {
        if p.Name == platformPlugin { continue }
        EnablePlugin(p.Name)
    }
    // 5. 应用配置覆盖
    for _, p := range targetPreset.Plugins {
        for _, cfg := range p.Configs {
            UpdateOrCreateSourceModConfig(cfgPath, cfg.Values)
        }
    }
}
```
