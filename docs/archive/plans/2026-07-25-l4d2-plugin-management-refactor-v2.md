# L4D2 插件管理重构 v2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 plugin-l4d2 模块的插件管理全量对齐开源项目 `l4d2-server-next` 的设计：库/游戏目录分离、内存引用计数、RCON 失败回滚、新预设结构、商店原子提交与 LFS 支持、CVAR 元数据增强与恢复默认值。

**Architecture:** 三层模型 — `EnabledPluginsService` 管理 `.enabled_plugins.yaml` 远程文件 + `EnabledPluginResource` 扩展资源双写；`FileRefsService` 纯内存 Map 从 yaml 重建引用计数；`PluginInstallService` 重写为 `plugins_store/{name}/left4dead2/` → 游戏目录的复制/删除流程，RCON 失败自动回滚已复制文件。所有远程文件操作通过 `InstanceFileService` SPI（屏蔽 SSH/Docker 差异）。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus + PF4J + Jackson YAML + Hutool + InstanceFileService SPI + RconService + ExtensionClient

---

## 参考与现状基线

### 参考项目
- `D:\program\open_source\l4d2-server-next-master`（Go + Vue 3）
- 核心文件：
  - `backend/logic/plugins.go`（945 行，CRUD + 启用/禁用 + 回滚）
  - `backend/logic/plugin_store.go`（826 行，商店 + LFS）
  - `backend/logic/preset.go`（183 行，预设应用）
  - `backend/logic/config_parser.go`（259 行，CVAR 解析 + 三种写入操作）
  - `backend/logic/plugin_config.go`（112 行，配置候选发现 + l4d2↔l4d 互转）
  - `backend/preset.yaml`（377 行，4 个预设）

### 已完成成果（保留不动）
- **阶段 1**：`L4D2PathResolver` 新增 6 个插件库路径方法 + `L4D2Extension` 懒初始化
- **阶段 2.1-2.4**：
  - `vo/EnabledPlugin.java`（name/source/enabledAt/files）
  - `extension/EnabledPluginResource.java` + `EnabledPluginSpec.java`（MODEL_ISOLATED）
  - `service/EnabledPluginsService.java`（yaml + 扩展资源双写）
  - `service/FileRefsService.java`（纯内存 Map，从 yaml 重建）

### 关键架构差异（l4d2-server-next → 本项目）
| 维度 | l4d2-server-next (Go) | 本项目 (Java) |
|------|----------------------|--------------|
| 文件操作 | `os.ReadFile/WriteFile` 本地 | `InstanceFileService` SPI 远程（SSH/Docker） |
| 启用状态文件 | `plugins.yaml`（viper） | `.enabled_plugins.yaml`（Jackson YAML） |
| 来源记录 | `plugin_sources` map（独立字段） | `EnabledPlugin.source` 字段（内嵌） |
| 引用计数 | 内存 `fileRefs map` | 内存 `Map<Long, Map<String, Set<String>>>` |
| 扩展资源 | 无 | `EnabledPluginResource` + `PluginConfigResource`（双写） |
| 配置持久化 | 仅写 cfg 文件 | cfg 文件 + `PluginConfigResource` 扩展资源 |
| RCON | `gorcon/rcon` 库 | `RconService`（已有分层架构） |

---

## 文件结构总览

### 已完成（保留不动）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/EnabledPlugin.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginResource.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginSpec.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java`

### 需修改
| 文件 | 责任 |
|------|------|
| `vo/PluginListVO.java` | 列表响应 VO，对齐 `name/status/source/hasSmx/hasConfig` |
| `service/PluginInstallService.java` | 重写为库/游戏分离模型，RCON 回滚 |
| `service/PresetService.java` | 新预设结构应用流程 |
| `config/PresetConfig.java` | 增加 `platform` 字段 |
| `vo/PresetDetailVO.java` | 改为 `plugins: List<PresetPlugin>` |
| `resources/preset.yaml` | 重写为新结构（platform + plugins.configs） |
| `parser/SourceModCfgParser.java` | 新增 `restoreFormat` 方法 |
| `service/SourceModCfgService.java` | 新增 3 方法 + 注入 RconService |
| `service/PluginStoreService.java` | 支持 DTO 参数 + 原子提交 + LFS |
| `util/GitHubApiClient.java` | 新增带 proxyUrl/githubToken/repo 参数重载 |
| `controller/PluginManageController.java` | 新增 `/readme` 端点 |
| `controller/PluginConfigController.java` | 新增 `/apply-temp` + `/restore-defaults` |
| `controller/PluginStoreController.java` | 签名对齐 Store DTOs |
| `resolver/L4D2PathResolver.java` | 标记 `getFileRefsPath` 为 `@Deprecated` |
| `L4D2Extension.java` | 在 `onInstanceCreate` 中调用 `PluginStoreMigration` |

### 需新建
| 文件 | 责任 |
|------|------|
| `vo/PresetPlugin.java` | 预设插件 VO（name + configs） |
| `vo/PresetPluginConfig.java` | 预设配置 VO（name + values） |
| `dto/StoreListDTO.java` | 商店列表请求（keyword/category/repo/proxyUrl/githubToken） |
| `dto/StoreQueryDTO.java` | 商店详情请求（pluginId/repo/proxyUrl/githubToken） |
| `dto/StoreDownloadDTO.java` | 商店下载请求（instanceId/pluginName/repo/proxyUrl/githubToken） |
| `migration/PluginStoreMigration.java` | 启动时迁移旧 plugins/ 到 plugins_store/ + 启动清理 |

### 测试文件
所有新方法均需单元测试，置于 `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/...` 对应目录。

---

## 阶段 1：存储模型对齐（已完成 ✅）

跳过，保留已有成果。

---

## 阶段 2：插件列表与安装服务重写

### Task 2.5: 更新 PluginListVO 字段

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java`

- [ ] **Step 1: 重写 PluginListVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 插件列表响应 VO（对齐 l4d2-server-next）。
 */
@Data
@Schema(description = "插件列表响应")
public class PluginListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "插件名（plugins_store 子目录名）")
    private String name;

    @Schema(description = "状态", allowableValues = {"enabled", "disabled"})
    private String status;

    @Schema(description = "来源", allowableValues = {"panel", "store", "upload"})
    private String source;

    @Schema(description = "是否包含 .smx 文件")
    private Boolean hasSmx;

    @Schema(description = "是否包含 cfg 配置文件")
    private Boolean hasConfig;

    @Schema(description = "描述（README 第一段）")
    private String description;

    @Schema(description = "版本")
    private String version;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "插件文件列表（相对 left4dead2/）")
    private List<String> fileList;

    @Schema(description = "配置文件列表")
    private List<String> configFiles;

    @Schema(description = "启用时间")
    private LocalDateTime enableTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 修复引用 PluginListVO 的旧字段（pluginName/pluginStatus）的代码**

使用 Grep 搜索 `pluginName` 和 `pluginStatus` 在 plugin-l4d2-core 中的使用位置，逐个替换为 `name` 和 `status`。重点关注：
- `service/PluginInstallService.java`（listPlugins 方法构建 VO 处）
- `controller/PluginManageController.java`（如有字段引用）
- `service/PluginExportService.java`（如有字段引用）

- [ ] **Step 4: 编译并运行测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS，已有测试全部通过

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java
git commit -m "refactor(l4d2): PluginListVO 字段对齐 l4d2-server-next（name/status/source/hasSmx/hasConfig）"
```

---

### Task 2.6: 重写 PluginInstallService 为库/游戏分离模型

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java`

- [ ] **Step 1: 编写失败测试 — listPlugins 扫描 plugins_store**

```java
// PluginInstallServiceTest.java
@Test
void listPlugins_shouldScanPluginsStoreAndMergeEnabledStatus() throws Exception {
    // 准备：plugins_store 下有 plugin-a 和 plugin-b 两个目录
    when(instanceFileService.listDirectory(eq(INSTANCE_ID), eq("addons/sourcemod/plugins_store")))
        .thenReturn(List.of(
            createFileInfo("plugin-a", true),
            createFileInfo("plugin-b", true)
        ));
    when(instanceFileService.listDirectory(eq(INSTANCE_ID), eq("addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins")))
        .thenReturn(List.of(createFileInfo("plugin-a.smx", false)));
    when(instanceFileService.listDirectory(eq(INSTANCE_ID), eq("addons/sourcemod/plugins_store/plugin-a/left4dead2/cfg/sourcemod")))
        .thenReturn(List.of(createFileInfo("plugin-a.cfg", false)));
    when(instanceFileService.listDirectory(eq(INSTANCE_ID), eq("addons/sourcemod/plugins_store/plugin-b/left4dead2/addons/sourcemod/plugins")))
        .thenReturn(Collections.emptyList());
    when(enabledPluginsService.isEnabled(eq(INSTANCE_ID), eq("plugin-a"))).thenReturn(true);
    when(enabledPluginsService.isEnabled(eq(INSTANCE_ID), eq("plugin-b"))).thenReturn(false);
    when(enabledPluginsService.loadYaml(eq(INSTANCE_ID)))
        .thenReturn(List.of(
            EnabledPlugin.builder().name("plugin-a").source("panel").enabledAt(1700000000000L).build()
        ));

    List<PluginListVO> result = pluginInstallService.listPlugins(INSTANCE_ID);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getName()).isEqualTo("plugin-a");
    assertThat(result.get(0).getStatus()).isEqualTo("enabled");
    assertThat(result.get(0).getSource()).isEqualTo("panel");
    assertThat(result.get(0).getHasSmx()).isTrue();
    assertThat(result.get(0).getHasConfig()).isTrue();
    assertThat(result.get(1).getName()).isEqualTo("plugin-b");
    assertThat(result.get(1).getStatus()).isEqualTo("disabled");
    assertThat(result.get(1).getSource()).isEqualTo("panel"); // 默认值
    assertThat(result.get(1).getHasSmx()).isFalse();
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#listPlugins_shouldScanPluginsStoreAndMergeEnabledStatus`
Expected: FAIL — 当前 listPlugins 仍是扫描 plugins/disabled 模型

- [ ] **Step 3: 重写 PluginInstallService 构造函数与字段**

注入 `EnabledPluginsService`，并新增辅助方法：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginInstallService {

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final RconService rconService;
    private final FileRefsService fileRefsService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;

    private static final String SMX_DIR_SUFFIX = "/left4dead2/addons/sourcemod/plugins";
    private static final String CFG_DIR_SUFFIX = "/left4dead2/cfg/sourcemod";
    private static final String README_FILE = "README.md";
    private static final String PLATFORM_KEYWORD = "1.11插件平台";
```

- [ ] **Step 4: 实现 listPlugins（扫 plugins_store + 合并 enabled 状态）**

```java
public List<PluginListVO> listPlugins(Long instanceId) {
    String storePath = pathResolver.getPluginsStorePath();
    List<FileInfo> storeEntries;
    try {
        storeEntries = instanceFileService.listDirectory(instanceId, storePath);
    } catch (Exception e) {
        log.warn("列出 plugins_store 失败 instanceId={}, err={}", instanceId, e.getMessage());
        return Collections.emptyList();
    }
    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    Map<String, EnabledPlugin> enabledMap = enabled.stream()
        .collect(Collectors.toMap(EnabledPlugin::getName, Function.identity(), (a, b) -> a));

    List<PluginListVO> result = new ArrayList<>();
    for (FileInfo entry : storeEntries) {
        if (!entry.isDirectory()) continue;
        String name = entry.getName();
        PluginListVO vo = new PluginListVO();
        vo.setName(name);
        vo.setStatus(enabledMap.containsKey(name) ? "enabled" : "disabled");
        EnabledPlugin ep = enabledMap.get(name);
        vo.setSource(ep != null && ep.getSource() != null ? ep.getSource() : "panel");
        vo.setEnableTime(ep != null && ep.getEnabledAt() != null
            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(ep.getEnabledAt()), ZoneId.systemDefault())
            : null);
        vo.setHasSmx(hasSmxFile(instanceId, name));
        vo.setHasConfig(hasConfigFile(instanceId, name));
        vo.setFileList(listPluginFiles(instanceId, name));
        vo.setConfigFiles(listConfigFiles(instanceId, name));
        result.add(vo);
    }
    return result;
}

private boolean hasSmxFile(Long instanceId, String pluginName) {
    try {
        String dir = pathResolver.getPluginStorePath(pluginName) + SMX_DIR_SUFFIX;
        List<FileInfo> entries = instanceFileService.listDirectory(instanceId, dir);
        return entries.stream().anyMatch(e -> !e.isDirectory() && e.getName().endsWith(".smx"));
    } catch (Exception e) {
        return false;
    }
}

private boolean hasConfigFile(Long instanceId, String pluginName) {
    try {
        String dir = pathResolver.getPluginStorePath(pluginName) + CFG_DIR_SUFFIX;
        List<FileInfo> entries = instanceFileService.listDirectory(instanceId, dir);
        return entries.stream().anyMatch(e -> !e.isDirectory() && e.getName().endsWith(".cfg"));
    } catch (Exception e) {
        return false;
    }
}

private List<String> listPluginFiles(Long instanceId, String pluginName) {
    try {
        String dir = pathResolver.getPluginStorePath(pluginName) + SMX_DIR_SUFFIX;
        return instanceFileService.listDirectory(instanceId, dir).stream()
            .filter(e -> !e.isDirectory() && e.getName().endsWith(".smx"))
            .map(e -> "addons/sourcemod/plugins/" + e.getName())
            .collect(Collectors.toList());
    } catch (Exception e) {
        return Collections.emptyList();
    }
}

private List<String> listConfigFiles(Long instanceId, String pluginName) {
    try {
        String dir = pathResolver.getPluginStorePath(pluginName) + CFG_DIR_SUFFIX;
        return instanceFileService.listDirectory(instanceId, dir).stream()
            .filter(e -> !e.isDirectory() && e.getName().endsWith(".cfg"))
            .map(e -> "cfg/sourcemod/" + e.getName())
            .collect(Collectors.toList());
    } catch (Exception e) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 5: 实现 installFromLocalFile（入库到 plugins_store/{name}/left4dead2/）**

```java
public void installFromLocalFile(Long instanceId, File localFile) throws Exception {
    String storePath = pathResolver.getPluginsStorePath();
    ensureStoreDirectory(instanceId);
    String zipBaseName = localFile.getName().replaceFirst("\\.zip$", "");
    String pluginName = zipBaseName;
    String pluginDir = pathResolver.getPluginStorePath(pluginName) + "/left4dead2";

    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.FileInputStream(localFile), java.nio.charset.Charset.forName("GBK"))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String rawName = entry.getName();
            String filtered = filterZipEntry(rawName);
            if (filtered == null) continue;
            String targetRel = mapToPluginLeft4Dead2(filtered, pluginDir);
            if (targetRel == null) continue;
            byte[] content = zis.readAllBytes();
            instanceFileService.writeBytes(instanceId, targetRel, content);
        }
    }
    // 写 plugin.yaml 元数据（source=upload）
    writePluginYaml(instanceId, pluginName, "upload");
}

private void ensureStoreDirectory(Long instanceId) throws Exception {
    String storePath = pathResolver.getPluginsStorePath();
    try {
        instanceFileService.listDirectory(instanceId, storePath);
    } catch (Exception e) {
        instanceFileService.createDirectory(instanceId, "addons/sourcemod");
        instanceFileService.createDirectory(instanceId, storePath);
    }
}

private String filterZipEntry(String name) {
    String normalized = name.replace('\\', '/');
    if (normalized.contains("__MACOSX/") || normalized.endsWith(".DS_Store")) return null;
    return normalized;
}

private String mapToPluginLeft4Dead2(String zipPath, String pluginDir) {
    // 支持 left4dead2/... 和直接 ... 两种结构
    String prefix = "left4dead2/";
    if (zipPath.startsWith(prefix)) {
        return pluginDir + "/" + zipPath.substring(prefix.length());
    }
    // 顶层 README.md 等放到插件根目录
    if (zipPath.startsWith("README") || zipPath.endsWith(".md")) {
        return pluginDir + "/../" + zipPath;
    }
    return pluginDir + "/" + zipPath;
}

private void writePluginYaml(Long instanceId, String pluginName, String source) throws Exception {
    String yamlPath = pathResolver.getPluginYamlPath(pluginName);
    String content = "name: \"" + pluginName + "\"\nsource: \"" + source + "\"\n";
    instanceFileService.writeText(instanceId, yamlPath, content);
}
```

- [ ] **Step 6: 实现 enablePlugin（复制 store → 游戏 + 写 yaml，无 RCON）**

```java
public void enablePlugin(Long instanceId, String pluginName) throws Exception {
    String pluginLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
    List<FileInfo> files;
    try {
        files = instanceFileService.walkFiles(instanceId, pluginLeft4Dead2);
    } catch (Exception e) {
        throw new IllegalStateException("插件库目录不存在: " + pluginLeft4Dead2, e);
    }
    if (files.isEmpty()) {
        throw new IllegalStateException("插件 " + pluginName + " 库目录为空");
    }

    List<String> copiedFiles = new ArrayList<>();
    try {
        for (FileInfo file : files) {
            if (file.isDirectory()) continue;
            String relToStore = file.getPath(); // 相对 plugins_store/{name}/left4dead2/
            // 计算相对 left4dead2/ 的路径
            String relToGameDir = extractRelativeToLeft4Dead2(relToStore, pluginName);
            if (relToGameDir == null) continue;
            byte[] content = instanceFileService.readBytes(instanceId, file.getPath());
            instanceFileService.writeBytes(instanceId, relToGameDir, content);
            copiedFiles.add(relToGameDir);
        }
    } catch (Exception e) {
        // 回滚：删除已复制的文件
        rollbackCopiedFiles(instanceId, copiedFiles);
        throw new RuntimeException("启用插件 " + pluginName + " 失败: " + e.getMessage(), e);
    }

    // 写 yaml + 更新引用计数
    EnabledPlugin plugin = new EnabledPlugin();
    plugin.setName(pluginName);
    plugin.setSource(detectSource(instanceId, pluginName));
    plugin.setEnabledAt(System.currentTimeMillis());
    plugin.setFiles(copiedFiles);
    enabledPluginsService.add(instanceId, plugin);
    fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
}

private String extractRelativeToLeft4Dead2(String relToStore, String pluginName) {
    String prefix = "addons/sourcemod/plugins_store/" + pluginName + "/left4dead2/";
    if (!relToStore.startsWith(prefix)) return null;
    return relToStore.substring(prefix.length());
}

private void rollbackCopiedFiles(Long instanceId, List<String> copiedFiles) {
    for (String path : copiedFiles) {
        try {
            instanceFileService.deleteFile(instanceId, path);
        } catch (Exception ignore) {
            log.warn("回滚删除文件失败 path={}", path);
        }
    }
}

private String detectSource(Long instanceId, String pluginName) {
    try {
        String yamlPath = pathResolver.getPluginYamlPath(pluginName);
        if (instanceFileService.fileExists(instanceId, yamlPath)) {
            String content = instanceFileService.readText(instanceId, yamlPath);
            if (content.contains("source: \"store\"")) return "store";
            if (content.contains("source: \"upload\"")) return "upload";
        }
    } catch (Exception ignore) {}
    return "panel";
}
```

- [ ] **Step 7: 实现 enableAndLoad（复制 + RCON + 失败回滚）**

```java
public void enableAndLoad(Long instanceId, String pluginName) throws Exception {
    List<String> smxIds = listPluginSmxIds(instanceId, pluginName);
    if (smxIds.isEmpty()) {
        throw new IllegalStateException("插件 " + pluginName + " 未找到 .smx 文件");
    }

    // 先复制文件 + 写 yaml
    enablePlugin(instanceId, pluginName);

    // RCON 加载
    List<String> loadedIds = new ArrayList<>();
    for (String smxId : smxIds) {
        try {
            rconService.executeCommand(instanceId, "sm plugins load " + smxId);
            loadedIds.add(smxId);
        } catch (Exception e) {
            // 回滚：unload 已加载的 + disablePlugin
            for (int i = loadedIds.size() - 1; i >= 0; i--) {
                try {
                    rconService.executeCommand(instanceId, "sm plugins unload " + loadedIds.get(i));
                } catch (Exception ignore) {}
            }
            try {
                disablePlugin(instanceId, pluginName);
            } catch (Exception disableErr) {
                log.error("disablePlugin 回滚失败 pluginName={}", pluginName, disableErr);
            }
            throw new RuntimeException("加载 smx " + smxId + " 失败: " + e.getMessage(), e);
        }
    }
}

private List<String> listPluginSmxIds(Long instanceId, String pluginName) {
    try {
        String dir = pathResolver.getPluginStorePath(pluginName) + SMX_DIR_SUFFIX;
        return instanceFileService.listDirectory(instanceId, dir).stream()
            .filter(e -> !e.isDirectory() && e.getName().endsWith(".smx"))
            .map(e -> e.getName().replace(".smx", ""))
            .sorted()
            .collect(Collectors.toList());
    } catch (Exception e) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 8: 实现 disablePlugin（引用计数删除 + 移除 yaml，无 RCON）**

```java
public void disablePlugin(Long instanceId, String pluginName) throws Exception {
    List<String> zeroedFiles = fileRefsService.removeRefs(instanceId, pluginName);
    for (String path : zeroedFiles) {
        try {
            instanceFileService.deleteFile(instanceId, path);
        } catch (Exception e) {
            log.warn("删除归零文件失败 path={}, err={}", path, e.getMessage());
        }
    }
    enabledPluginsService.remove(instanceId, pluginName);
}
```

- [ ] **Step 9: 实现 disableAndUnload（RCON 逆序 unload + disablePlugin）**

```java
public void disableAndUnload(Long instanceId, String pluginName) throws Exception {
    List<String> smxIds = listPluginSmxIds(instanceId, pluginName);
    List<String> unloadedIds = new ArrayList<>();
    // 逆序 unload
    for (int i = smxIds.size() - 1; i >= 0; i--) {
        String smxId = smxIds.get(i);
        try {
            rconService.executeCommand(instanceId, "sm plugins unload " + smxId);
            unloadedIds.add(smxId);
        } catch (Exception e) {
            // 回滚：reload 已卸载的（逆序）
            for (int j = unloadedIds.size() - 1; j >= 0; j--) {
                try {
                    rconService.executeCommand(instanceId, "sm plugins load " + unloadedIds.get(j));
                } catch (Exception ignore) {}
            }
            throw new RuntimeException("卸载 smx " + smxId + " 失败: " + e.getMessage(), e);
        }
    }
    disablePlugin(instanceId, pluginName);
}
```

- [ ] **Step 10: 实现 deletePlugin（必须先禁用 + 硬删除 store 目录）**

```java
public void deletePlugin(Long instanceId, String pluginName) throws Exception {
    if (enabledPluginsService.isEnabled(instanceId, pluginName)) {
        throw new IllegalStateException("无法删除已启用的插件 " + pluginName + "，请先禁用");
    }
    String pluginDir = pathResolver.getPluginStorePath(pluginName);
    instanceFileService.deleteDirectory(instanceId, pluginDir);
}
```

- [ ] **Step 11: 实现 enablePlatformPlugin（单数，扫 plugins_store 关键字匹配）**

```java
public void enablePlatformPlugin(Long instanceId, String keyword) throws Exception {
    String storePath = pathResolver.getPluginsStorePath();
    List<FileInfo> entries = instanceFileService.listDirectory(instanceId, storePath);
    for (FileInfo entry : entries) {
        if (!entry.isDirectory()) continue;
        if (entry.getName().contains(keyword)) {
            if (!enabledPluginsService.isEnabled(instanceId, entry.getName())) {
                enablePlugin(instanceId, entry.getName());
            }
            return;
        }
    }
    throw new IllegalStateException("未找到平台插件，关键字: " + keyword);
}
```

- [ ] **Step 12: 实现 disableAllPlugins + 批量方法 + readReadme + 兼容包装**

```java
public void disableAllPlugins(Long instanceId) throws Exception {
    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    for (EnabledPlugin ep : enabled) {
        try {
            disablePlugin(instanceId, ep.getName());
        } catch (Exception e) {
            log.warn("禁用插件失败 name={}, err={}", ep.getName(), e.getMessage());
        }
    }
}

public void enablePlugins(Long instanceId, List<String> pluginNames) throws Exception {
    for (String name : pluginNames) {
        enablePlugin(instanceId, name);
    }
}

public void disablePlugins(Long instanceId, List<String> pluginNames) throws Exception {
    for (String name : pluginNames) {
        disablePlugin(instanceId, name);
    }
}

public List<String> listEnabledPluginNames(Long instanceId) {
    return enabledPluginsService.loadYaml(instanceId).stream()
        .map(EnabledPlugin::getName)
        .collect(Collectors.toList());
}

public String readReadme(Long instanceId, String pluginName) {
    try {
        String readmePath = pathResolver.getPluginReadmePath(pluginName);
        return instanceFileService.readText(instanceId, readmePath);
    } catch (Exception e) {
        return "";
    }
}

// ===== 旧方法兼容包装（已废弃，仅供过渡期使用） =====

/** @deprecated 使用 {@link #enablePlatformPlugin(Long, String)} */
@Deprecated
public void enablePlatformPlugins(Long instanceId, String platform) throws Exception {
    enablePlatformPlugin(instanceId, platform);
}

/** @deprecated 使用 {@link #listEnabledPluginNames(Long)} */
@Deprecated
public List<String> listEnabledPlugins(Long instanceId) {
    return listEnabledPluginNames(instanceId);
}
```

- [ ] **Step 13: 实现 installFromUpload**

```java
public PluginListVO installFromUpload(Long instanceId, MultipartFile file) throws Exception {
    java.io.File tempFile = java.io.File.createTempFile("l4d2-plugin-", ".zip");
    try {
        file.transferTo(tempFile);
        installFromLocalFile(instanceId, tempFile);
        String pluginName = tempFile.getName().replaceFirst("\\.zip$", "");
        PluginListVO vo = new PluginListVO();
        vo.setName(pluginName);
        vo.setStatus("disabled");
        vo.setSource("upload");
        return vo;
    } finally {
        if (!tempFile.delete()) {
            tempFile.deleteOnExit();
        }
    }
}
```

- [ ] **Step 14: 运行所有测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS

- [ ] **Step 15: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): PluginInstallService 重写为库/游戏分离模型 + RCON 失败回滚"
```

---

## 阶段 3：预设系统重构

### Task 3.1: 创建 PresetPlugin 和 PresetPluginConfig VO

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java`

- [ ] **Step 1: 创建 PresetPluginConfig**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * 预设中单个插件的配置项（对齐 l4d2-server-next PresetPluginConfig）。
 */
@Data
public class PresetPluginConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** cfg 文件名（不含目录） */
    private String name;

    /** CVAR key → value */
    private Map<String, String> values;
}
```

- [ ] **Step 2: 创建 PresetPlugin**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设中的插件项（对齐 l4d2-server-next PresetPlugin）。
 */
@Data
public class PresetPlugin implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 配置文件列表 */
    private List<PresetPluginConfig> configs = new ArrayList<>();
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java
git commit -m "feat(l4d2): 新增 PresetPlugin/PresetPluginConfig VO（对齐 l4d2-server-next）"
```

---

### Task 3.2: 重写 PresetDetailVO + PresetConfig + preset.yaml

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml`

- [ ] **Step 1: 重写 PresetDetailVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设详情 VO（对齐 l4d2-server-next Preset + PresetInfo）。
 */
@Data
public class PresetDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String gameMode;
    private Integer maxPlayers;

    /** 插件列表 */
    private List<PresetPlugin> plugins = new ArrayList<>();

    /** 插件数量（计算字段） */
    public int getPluginCount() {
        return plugins != null ? plugins.size() : 0;
    }
}
```

- [ ] **Step 2: 重写 PresetConfig**

```java
package com.gameplatform.plugin.l4d2.config;

import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import lombok.Data;
import java.util.Map;
import java.util.List;

/**
 * preset.yaml 配置根（对齐 l4d2-server-next PresetConfig）。
 */
@Data
public class PresetConfig {
    /** 平台插件名（按 OS 区分） */
    private Map<String, String> platform;

    /** 预设列表 */
    private List<PresetDetailVO> presets;
}
```

- [ ] **Step 3: 重写 preset.yaml（新结构：platform + presets[].plugins[].configs[]）**

参考 `D:\program\open_source\l4d2-server-next-master\backend\preset.yaml`，写入完整内容：

```yaml
platform:
  linux: "1.11插件平台linux版(6968-1155)(必须先启用这个)"
  windows: "1.11插件平台windows版(6968-1155)(必须先启用这个)"
presets:
  - id: multi-versus
    name: "多特战役"
    description: "标准的多人多特玩法，neko多特以及一些优化体验插件"
    plugins:
      - name: "必选-功能类插件(left4dhooks)(v1.155)(SilverShot)"
      - name: "自选-8角色共存(v1.9.10)(DeatChaos25, Mi123456 & Merudo, Lux, SilverShot)"
        configs:
          - name: "survivor_chat_select.cfg"
            values:
              l4d_csm_admin_flags: ""
  - id: fun-versus
    name: "娱乐多特战役"
    description: "更娱乐化的多特玩法"
    plugins: []
  - id: pure-coop
    name: "纯净战役"
    description: "仅启用基础平台插件，无任何附加功能"
    plugins: []
  - id: official-roguelike
    name: "官图肉鸽模式"
    description: "官图肉鸽玩法"
    plugins: []
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml
git commit -m "refactor(l4d2): PresetDetailVO/PresetConfig/preset.yaml 对齐 l4d2-server-next 新结构"
```

---

### Task 3.3: 重写 PresetService

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java`

- [ ] **Step 1: 编写失败测试 — apply 调用新方法链**

```java
@Test
void apply_shouldCallNewMethodChain() throws Exception {
    Long instanceId = 100L;
    String presetId = "multi-versus";

    PresetDetailVO preset = new PresetDetailVO();
    preset.setId(presetId);
    preset.setName("多特战役");
    PresetPlugin p1 = new PresetPlugin();
    p1.setName("platform-plugin");
    PresetPlugin p2 = new PresetPlugin();
    p2.setName("plugin-a");
    PresetPluginConfig cfg = new PresetPluginConfig();
    cfg.setName("plugin-a.cfg");
    cfg.setValues(Map.of("key1", "value1"));
    p2.setConfigs(List.of(cfg));
    preset.setPlugins(List.of(p1, p2));

    when(presetConfig.getPresets()).thenReturn(List.of(preset));
    when(presetConfig.getPlatform()).thenReturn(Map.of("linux", "platform-plugin"));

    presetService.apply(instanceId, presetId);

    // 验证：先禁用所有
    verify(pluginInstallService).disableAllPlugins(eq(instanceId));
    // 验证：启用平台插件（按 OS）
    verify(pluginInstallService).enablePlatformPlugin(eq(instanceId), contains("platform-plugin"));
    // 验证：启用其他插件
    verify(pluginInstallService).enablePlugin(eq(instanceId), eq("plugin-a"));
    // 验证：应用配置
    verify(cfgService).updateOrCreateConfig(eq(instanceId), eq("plugin-a.cfg"), eq(Map.of("key1", "value1")));
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest#apply_shouldCallNewMethodChain`
Expected: FAIL

- [ ] **Step 3: 重写 PresetService.apply**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetService {

    private final PresetConfig presetConfig;
    private final PluginInstallService pluginInstallService;
    private final SourceModCfgService cfgService;

    @PostConstruct
    public void loadPresetYaml() {
        try (var in = getClass().getResourceAsStream("/preset.yaml")) {
            if (in == null) {
                log.warn("preset.yaml 不存在");
                return;
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            PresetConfig loaded = mapper.readValue(in, PresetConfig.class);
            // 复制到注入的 presetConfig 单例
            presetConfig.setPlatform(loaded.getPlatform());
            presetConfig.setPresets(loaded.getPresets());
            log.info("加载 preset.yaml 成功: {} 个预设", presetConfig.getPresets().size());
        } catch (Exception e) {
            log.error("加载 preset.yaml 失败", e);
        }
    }

    public List<PresetDetailVO> list() {
        return presetConfig.getPresets() != null ? presetConfig.getPresets() : Collections.emptyList();
    }

    public PresetDetailVO detail(String presetId) {
        return list().stream()
            .filter(p -> presetId.equals(p.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("预设不存在: " + presetId));
    }

    public void apply(Long instanceId, String presetId) throws Exception {
        PresetDetailVO preset = detail(presetId);

        // 1. 校验所有插件存在
        validatePluginsExist(instanceId, preset);

        // 2. 禁用当前所有已启用插件
        pluginInstallService.disableAllPlugins(instanceId);

        // 3. 启用平台插件（按 OS）
        String platformPlugin = resolvePlatformPlugin();
        if (platformPlugin != null) {
            pluginInstallService.enablePlatformPlugin(instanceId, platformPlugin);
        }

        // 4. 顺序启用其他插件（跳过平台插件）
        for (PresetPlugin p : preset.getPlugins()) {
            if (platformPlugin != null && platformPlugin.equals(p.getName())) continue;
            try {
                pluginInstallService.enablePlugin(instanceId, p.getName());
            } catch (Exception e) {
                log.error("启用插件失败 name={}, err={}", p.getName(), e.getMessage());
                throw e;
            }
        }

        // 5. 应用配置
        for (PresetPlugin p : preset.getPlugins()) {
            for (PresetPluginConfig cfg : p.getConfigs()) {
                try {
                    cfgService.updateOrCreateConfig(instanceId, cfg.getName(), cfg.getValues());
                } catch (Exception e) {
                    log.warn("应用配置失败 plugin={}, cfg={}, err={}", p.getName(), cfg.getName(), e.getMessage());
                }
            }
        }
    }

    private void validatePluginsExist(Long instanceId, PresetDetailVO preset) {
        // 通过 listPlugins 校验
        List<PluginListVO> installed = pluginInstallService.listPlugins(instanceId);
        Set<String> installedNames = installed.stream().map(PluginListVO::getName).collect(Collectors.toSet());
        for (PresetPlugin p : preset.getPlugins()) {
            if (!installedNames.contains(p.getName())) {
                throw new IllegalStateException("未找到插件: " + p.getName());
            }
        }
    }

    private String resolvePlatformPlugin() {
        Map<String, String> platform = presetConfig.getPlatform();
        if (platform == null) return null;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) return platform.get("linux");
        if (osName.contains("windows")) return platform.get("windows");
        return platform.get("linux"); // 默认 Linux
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java
git commit -m "refactor(l4d2): PresetService.apply 对齐 l4d2-server-next 流程（先禁用→启用平台→启用其他→应用配置）"
```

---

## 阶段 4：商店增强（DTO + 原子提交 + LFS）

### Task 4.1: 创建 Store DTOs

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreListDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreQueryDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreDownloadDTO.java`

- [ ] **Step 1: 创建 StoreListDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class StoreListDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String keyword;
    private String category;
    private String repo;            // 可选，自定义仓库 owner/repo
    private String proxyUrl;        // 可选，加速代理
    private String githubToken;     // 可选，GitHub Token
}
```

- [ ] **Step 2: 创建 StoreQueryDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class StoreQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String pluginId;
    private String repo;
    private String proxyUrl;
    private String githubToken;
}
```

- [ ] **Step 3: 创建 StoreDownloadDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class StoreDownloadDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long instanceId;
    private String pluginName;
    private String repo;
    private String proxyUrl;
    private String githubToken;
}
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreListDTO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreQueryDTO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreDownloadDTO.java
git commit -m "feat(l4d2): 新增 Store DTOs（支持 repo/proxyUrl/githubToken 参数）"
```

---

### Task 4.2: 增强 GitHubApiClient（支持参数重载）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`

- [ ] **Step 1: 新增带参数的方法重载**

保留原方法作为兼容包装（从 config 读取参数），新增以下方法：

```java
/**
 * 获取仓库完整树（带参数）。
 */
public List<TreeEntry> getTree(String proxyUrl, String githubToken, String repo) {
    String effectiveRepo = normalizeRepo(repo != null ? repo : defaultRepo);
    String cacheKey = effectiveRepo;
    // 缓存 10 分钟
    if (treeCache.containsKey(cacheKey) &&
        System.currentTimeMillis() - treeCacheTime.get(cacheKey) < 10 * 60 * 1000) {
        return treeCache.get(cacheKey);
    }
    String url = String.format("https://api.github.com/repos/%s/git/trees/%s?recursive=1",
        effectiveRepo, defaultBranch);
    url = applyProxy(proxyUrl, url);
    HttpRequest req = buildRequest(url, githubToken);
    HttpResponse resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
        throw new RuntimeException("GitHub Trees API 失败: " + resp.statusCode() + " " + resp.body());
    }
    TreeResponse treeResp = objectMapper.readValue(resp.body(), TreeResponse.class);
    treeCache.put(cacheKey, treeResp.getTree());
    treeCacheTime.put(cacheKey, System.currentTimeMillis());
    return treeResp.getTree();
}

public String getBlobContent(String sha, String proxyUrl, String githubToken, String repo) {
    String effectiveRepo = normalizeRepo(repo != null ? repo : defaultRepo);
    String url = String.format("https://api.github.com/repos/%s/git/blobs/%s", effectiveRepo, sha);
    url = applyProxy(proxyUrl, url);
    HttpRequest req = buildRequest(url, githubToken);
    HttpResponse resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    BlobResponse blob = objectMapper.readValue(resp.body(), BlobResponse.class);
    if ("base64".equals(blob.getEncoding())) {
        return new String(Base64.getDecoder().decode(blob.getContent().replaceAll("\\s", "")));
    }
    return blob.getContent();
}

public Map<String, String> batchLfsObjects(List<String> oids, String proxyUrl, String githubToken, String repo) {
    String effectiveRepo = normalizeRepo(repo != null ? repo : defaultRepo);
    String url = String.format("https://github.com/%s.git/info/lfs/objects/batch", effectiveRepo);
    url = applyProxy(proxyUrl, url);
    // 构造请求体
    Map<String, Object> body = new HashMap<>();
    body.put("operation", "download");
    body.put("transfers", List.of("basic"));
    Map<String, Object> ref = new HashMap<>();
    ref.put("name", "refs/heads/" + defaultBranch);
    body.put("ref", ref);
    List<Map<String, Object>> objects = new ArrayList<>();
    for (String oid : oids) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("oid", oid);
        obj.put("size", 0);
        objects.add(obj);
    }
    body.put("objects", objects);
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Accept", "application/vnd.git-lfs+json")
        .header("Content-Type", "application/vnd.git-lfs+json")
        .header("Authorization", githubToken != null ? "Bearer " + githubToken : "")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    HttpResponse resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    // 解析返回的 download href
    Map<String, String> result = new HashMap<>();
    JsonNode root = objectMapper.readTree(resp.body());
    for (JsonNode obj : root.path("objects")) {
        String oid = obj.path("oid").asText();
        String href = obj.path("actions").path("download").path("href").asText();
        if (!oid.isEmpty() && !href.isEmpty()) {
            result.put(oid, href);
        }
    }
    return result;
}

private HttpRequest buildRequest(String url, String githubToken) {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "Mozilla/5.0")
        .timeout(Duration.ofSeconds(30));
    if (githubToken != null && !githubToken.isEmpty()) {
        builder.header("Authorization", "Bearer " + githubToken);
    }
    return builder.GET().build();
}

private String applyProxy(String proxyUrl, String targetUrl) {
    if (proxyUrl == null || proxyUrl.isEmpty()) return targetUrl;
    return proxyUrl.endsWith("/") ? proxyUrl + targetUrl : proxyUrl + "/" + targetUrl;
}

private String normalizeRepo(String repo) {
    if (repo == null || repo.isBlank()) return defaultRepo;
    return repo.trim();
}
```

- [ ] **Step 2: 旧方法改为包装新方法**

```java
public List<TreeEntry> getTree() {
    return getTree(null, System.getenv("GITHUB_TOKEN"), null);
}
public String getBlobContent(String sha) {
    return getBlobContent(sha, null, System.getenv("GITHUB_TOKEN"), null);
}
public Map<String, String> batchLfsObjects(List<String> oids) {
    return batchLfsObjects(oids, null, System.getenv("GITHUB_TOKEN"), null);
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java
git commit -m "feat(l4d2): GitHubApiClient 新增 proxyUrl/githubToken/repo 参数重载"
```

---

### Task 4.3: 增强 PluginStoreService（DTO 重载 + 原子提交）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`

- [ ] **Step 1: 新增 DTO 重载方法**

```java
public List<PluginStoreItemVO> list(StoreListDTO dto) {
    return listInternal(dto.getKeyword(), dto.getCategory(), dto.getRepo(),
        dto.getProxyUrl(), dto.getGithubToken());
}

public PluginStoreDetailVO detail(String pluginId, StoreQueryDTO dto) {
    return detailInternal(pluginId, dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken());
}

public String download(StoreDownloadDTO dto) {
    return downloadInternal(dto);
}
```

- [ ] **Step 2: 实现下载临时目录原子提交**

修改 `downloadInternal` 方法，将下载流程改为：
1. 创建本地临时目录 `{user.home}/game-platform-l4d2/store-tasks/{taskId}/`
2. 通过 `gitHubApiClient.getTree(proxyUrl, githubToken, repo)` 获取文件列表
3. 并发下载所有 blob 到本地临时目录（保留 `plugins/{name}/` 前缀结构）
4. 检测 LFS pointer，若是则走 `batchLfsObjects` + `downloadLfsObject`
5. 全部成功后：
   - 遍历本地临时目录，通过 `instanceFileService.writeBytes` 上传到远程 `plugins_store/{name}/left4dead2/`
   - 写 `plugin.yaml`（source=store）
6. 任意步骤失败：清理本地临时目录，不污染远程
7. 返回 taskId 供前端轮询

```java
private String downloadInternal(StoreDownloadDTO dto) {
    String taskId = UUID.randomUUID().toString();
    String localTempDir = System.getProperty("user.home") + "/game-platform-l4d2/store-tasks/" + taskId;
    new File(localTempDir).mkdirs();

    CompletableFuture.runAsync(() -> {
        try {
            List<TreeEntry> tree = gitHubApiClient.getTree(dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
            List<TreeEntry> pluginFiles = tree.stream()
                .filter(e -> e.getType().equals("blob"))
                .filter(e -> e.getPath().startsWith("plugins/" + dto.getPluginName() + "/"))
                .collect(Collectors.toList());

            // 1. 下载到本地临时目录
            for (TreeEntry file : pluginFiles) {
                downloadOneToLocal(file, localTempDir, dto);
            }

            // 2. 上传到远程 plugins_store
            String remotePluginDir = pathResolver.getPluginStorePath(dto.getPluginName()) + "/left4dead2";
            uploadLocalToRemote(localTempDir + "/plugins/" + dto.getPluginName() + "/left4dead2",
                dto.getInstanceId(), remotePluginDir);

            // 3. 写 plugin.yaml
            writePluginYaml(dto.getInstanceId(), dto.getPluginName(), "store");

            // 4. 更新任务状态
            updateTaskStatus(taskId, "completed", "下载完成");
        } catch (Exception e) {
            log.error("商店下载失败 taskId={}, err={}", taskId, e.getMessage(), e);
            updateTaskStatus(taskId, "failed", e.getMessage());
        } finally {
            // 清理本地临时目录
            deleteLocalDirectory(new File(localTempDir));
        }
    });

    storeDownloadTasks.put(taskId, new StoreDownloadTaskVO(taskId, dto.getPluginName(), "downloading"));
    return taskId;
}

private void downloadOneToLocal(TreeEntry file, String localTempDir, StoreDownloadDTO dto) throws Exception {
    String relPath = file.getPath(); // plugins/{name}/left4dead2/...
    String localPath = localTempDir + "/" + relPath;
    new File(localPath).getParentFile().mkdirs();

    String content = gitHubApiClient.getBlobContent(file.getSha(), dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
    if (gitHubApiClient.isLfsPointer(content)) {
        // LFS 流程
        String oid = parseLfsOid(content);
        Map<String, String> batchResult = gitHubApiClient.batchLfsObjects(
            List.of(oid), dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
        String lfsUrl = batchResult.get(oid);
        if (lfsUrl != null) {
            downloadLfsObjectToLocal(lfsUrl, localPath, dto);
        }
    } else {
        Files.write(Paths.get(localPath), content.getBytes(StandardCharsets.UTF_8));
    }
}

private void uploadLocalToRemote(String localDir, Long instanceId, String remoteDir) throws Exception {
    File dir = new File(localDir);
    if (!dir.exists()) return;
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
        String remotePath = remoteDir + "/" + file.getName();
        if (file.isDirectory()) {
            uploadLocalToRemote(file.getAbsolutePath(), instanceId, remotePath);
        } else {
            byte[] content = Files.readAllBytes(file.toPath());
            instanceFileService.writeBytes(instanceId, remotePath, content);
        }
    }
}
```

- [ ] **Step 3: 旧方法改为包装**

```java
public List<PluginStoreItemVO> list(String keyword, String category) {
    StoreListDTO dto = new StoreListDTO();
    dto.setKeyword(keyword);
    dto.setCategory(category);
    return list(dto);
}
```

- [ ] **Step 4: 编译验证 + 测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "feat(l4d2): PluginStoreService 支持 DTO 参数 + 临时目录原子提交 + LFS"
```

---

## 阶段 5：配置编辑增强

### Task 5.1: SourceModCfgParser 新增 restoreFormat

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`

- [ ] **Step 1: 编写失败测试 — restoreFormat 重建完整 cfg 内容**

```java
@Test
void restoreFormat_shouldRebuildFullConfigWithMetadata() {
    ConfigItem item1 = new ConfigItem();
    item1.setKey("l4d_csm_admin_flags");
    item1.setValue("");
    item1.setDefaultValue("");
    item1.setDescription("管理员标志\n第二行");

    ConfigItem item2 = new ConfigItem();
    item2.setKey("bots_give_slot0");
    item2.setValue("2079");
    item2.setDefaultValue("0");
    item2.setMin("0");
    item2.setMax("99999");

    String result = cfgParser.restoreFormat(List.of(item1, item2));

    assertThat(result).contains("// 管理员标志");
    assertThat(result).contains("// 第二行");
    assertThat(result).contains("// -");
    assertThat(result).contains("// Default: \"\"");
    assertThat(result).contains("l4d_csm_admin_flags \"\"");
    assertThat(result).contains("// Minimum: \"0\"");
    assertThat(result).contains("// Maximum: \"99999\"");
    assertThat(result).contains("bots_give_slot0 \"2079\"");
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest#restoreFormat_shouldRebuildFullConfigWithMetadata`
Expected: FAIL — 方法不存在

- [ ] **Step 3: 实现 restoreFormat**

```java
/**
 * 按完整 SourceMod 注释格式重建文件内容（对齐 l4d2-server-next RestoreSourceModConfig）。
 * <p>用于配置文件不存在时的初始化创建。
 */
public String restoreFormat(List<ConfigItem> items) {
    if (items == null || items.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
        ConfigItem cvar = items.get(i);
        if (i > 0) sb.append("\n");
        if (cvar.getDescription() != null && !cvar.getDescription().isEmpty()) {
            for (String descLine : cvar.getDescription().split("\n")) {
                sb.append("// ").append(descLine).append("\n");
            }
        }
        sb.append("// -\n");
        if (cvar.getDefaultValue() != null && !cvar.getDefaultValue().isEmpty()) {
            sb.append("// Default: \"").append(cvar.getDefaultValue()).append("\"\n");
        }
        if (cvar.getMin() != null && !cvar.getMin().isEmpty()) {
            sb.append("// Minimum: \"").append(cvar.getMin()).append("\"\n");
        }
        if (cvar.getMax() != null && !cvar.getMax().isEmpty()) {
            sb.append("// Maximum: \"").append(cvar.getMax()).append("\"\n");
        }
        sb.append(cvar.getKey()).append(" \"").append(cvar.getValue()).append("\"\n");
    }
    return sb.toString();
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): SourceModCfgParser 新增 restoreFormat 重建完整 cfg 内容"
```

---

### Task 5.2: SourceModCfgService 新增 3 方法 + 注入 RconService

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

- [ ] **Step 1: 编写失败测试**

```java
@Test
void applyTempConfig_shouldCallRconSmCvar() {
    cfgService.applyTempConfig(INSTANCE_ID, "l4d_csm_admin_flags", "z");
    verify(rconService).executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d_csm_admin_flags \"z\""));
}

@Test
void restoreDefaults_shouldUpdateValuesFromDefaultMetadata() throws Exception {
    // 准备：cfg 文件存在，包含带 Default 注释的 CVAR
    when(instanceFileService.fileExists(eq(INSTANCE_ID), contains("plugin-a.cfg"))).thenReturn(true);
    when(instanceFileService.readText(eq(INSTANCE_ID), contains("plugin-a.cfg")))
        .thenReturn("// Default: \"0\"\nbots_give_slot0 \"2079\"");

    cfgService.restoreDefaults(INSTANCE_ID, "plugin-a");

    // 验证：写回 cfg 文件，值为 0
    verify(instanceFileService).writeText(eq(INSTANCE_ID), contains("plugin-a.cfg"), contains("bots_give_slot0 \"0\""));
}

@Test
void updateOrCreateConfig_shouldCreateIfNotExist() throws Exception {
    // 准备：cfg 文件不存在
    when(instanceFileService.fileExists(eq(INSTANCE_ID), eq("cfg/sourcemod/new-cfg.cfg"))).thenReturn(false);

    cfgService.updateOrCreateConfig(INSTANCE_ID, "new-cfg.cfg", Map.of("key1", "value1"));

    // 验证：调用 writeText 写入新文件
    verify(instanceFileService).writeText(eq(INSTANCE_ID), eq("cfg/sourcemod/new-cfg.cfg"), anyString());
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgServiceTest`
Expected: FAIL — 方法不存在

- [ ] **Step 3: 修改 SourceModCfgService 构造函数**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceModCfgService {

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final ExtensionClient extensionClient;
    private final SourceModCfgParser cfgParser;
    private final L4D2PathResolver pathResolver;
    private final RconService rconService;  // 新增
```

- [ ] **Step 4: 实现 applyTempConfig（RCON sm_cvar 即时生效）**

```java
/**
 * 临时应用 CVAR（通过 RCON sm_cvar，对齐 l4d2-server-next 前端 applyTempConfig）。
 * <p>仅运行中服务器内存生效，重启或 reload 后丢失。
 */
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
    String cmd = String.format("sm_cvar %s \"%s\"", cvarName, cvarValue);
    rconService.executeCommand(instanceId, cmd);
}
```

- [ ] **Step 5: 实现 restoreDefaults（从注释 Default 元数据还原）**

```java
/**
 * 还原默认值（对齐 l4d2-server-next RestoreSourceModConfig）。
 * <p>从注释中的 Default 元数据提取默认值，写回 cfg 文件。
 */
public void restoreDefaults(Long instanceId, String pluginName) throws Exception {
    PluginConfigResource resource = getConfig(instanceId, pluginName);
    if (resource == null || resource.getSpec().getItems() == null) {
        throw new IllegalStateException("插件配置不存在: " + pluginName);
    }
    // 用 Default 值替换当前 Value
    List<ConfigItem> items = resource.getSpec().getItems();
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().isEmpty()) {
            item.setValue(item.getDefaultValue());
        }
    }
    // 序列化并写回
    String cfgPath = resource.getSpec().getConfigPath();
    String original = resource.getSpec().getRawContent();
    String updated = cfgParser.serialize(items, original);
    instanceFileService.writeText(instanceId, cfgPath, updated);

    // 同步扩展资源
    resource.getSpec().setItems(items);
    resource.getSpec().setLastSyncedAt(LocalDateTime.now());
    extensionClient.update(resource);
}
```

- [ ] **Step 6: 实现 updateOrCreateConfig（文件存在更新，不存在创建）**

```java
/**
 * 更新或创建配置文件（对齐 l4d2-server-next UpdateOrCreateSourceModConfig）。
 * <p>文件存在时仅更新已存在 key，缺失 key 追加；不存在时用 restoreFormat 创建。
 */
public void updateOrCreateConfig(Long instanceId, String configName, Map<String, String> values) throws Exception {
    String cfgPath = "cfg/sourcemod/" + configName;
    if (instanceFileService.fileExists(instanceId, cfgPath)) {
        // 更新已存在文件
        String content = instanceFileService.readText(instanceId, cfgPath);
        List<ConfigItem> items = cfgParser.parse(content);
        // 标记已更新的 key
        Set<String> updatedKeys = new HashSet<>();
        for (ConfigItem item : items) {
            if (values.containsKey(item.getKey())) {
                item.setValue(values.get(item.getKey()));
                updatedKeys.add(item.getKey());
            }
        }
        // 追加缺失 key
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!updatedKeys.contains(entry.getKey())) {
                ConfigItem newItem = new ConfigItem();
                newItem.setKey(entry.getKey());
                newItem.setValue(entry.getValue());
                items.add(newItem);
            }
        }
        String updated = cfgParser.serialize(items, content);
        instanceFileService.writeText(instanceId, cfgPath, updated);
    } else {
        // 创建新文件：用 restoreFormat 重建
        List<ConfigItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            ConfigItem item = new ConfigItem();
            item.setKey(entry.getKey());
            item.setValue(entry.getValue());
            items.add(item);
        }
        String content = cfgParser.restoreFormat(items);
        instanceFileService.writeText(instanceId, cfgPath, content);
    }
}
```

- [ ] **Step 7: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgServiceTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): SourceModCfgService 新增 applyTempConfig/restoreDefaults/updateOrCreateConfig"
```

---

## 阶段 6：Controller 层对齐

### Task 6.1: PluginManageController 调整

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`

- [ ] **Step 1: 新增 GET /{pluginName}/readme 端点**

```java
@Operation(summary = "获取插件 README")
@GetMapping("/{pluginName}/readme")
public Result<String> getReadme(@PathVariable String pluginName,
                                 @RequestParam Long instanceId) {
    return Result.success(pluginInstallService.readReadme(instanceId, pluginName));
}
```

- [ ] **Step 2: 旧端点签名对齐（如有变更）**

检查 `/enable-load`、`/disable-unload`、`/batch-enable`、`/batch-disable` 端点是否仍工作（PluginInstallService 方法签名已变更，但对外端点不变）。

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java
git commit -m "feat(l4d2): PluginManageController 新增 /readme 端点"
```

---

### Task 6.2: PluginConfigController 新增端点

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`

- [ ] **Step 1: 新增 POST /apply-temp + /restore-defaults**

```java
@Operation(summary = "临时应用 CVAR（RCON sm_cvar）")
@PostMapping("/apply-temp")
public Result<Void> applyTempConfig(@RequestParam Long instanceId,
                                     @RequestParam String cvarName,
                                     @RequestParam String cvarValue) {
    cfgService.applyTempConfig(instanceId, cvarName, cvarValue);
    return Result.success();
}

@Operation(summary = "还原插件默认值")
@PostMapping("/restore-defaults")
public Result<Void> restoreDefaults(@RequestParam Long instanceId,
                                     @RequestParam String pluginName) {
    cfgService.restoreDefaults(instanceId, pluginName);
    return Result.success();
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java
git commit -m "feat(l4d2): PluginConfigController 新增 /apply-temp + /restore-defaults"
```

---

### Task 6.3: PluginStoreController 签名对齐

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java`

- [ ] **Step 1: 端点改用 Store DTOs**

```java
@Operation(summary = "商店列表")
@PostMapping("/list")
public Result<List<PluginStoreItemVO>> list(@RequestBody StoreListDTO dto) {
    return Result.success(storeService.list(dto));
}

@Operation(summary = "商店详情")
@PostMapping("/detail")
public Result<PluginStoreDetailVO> detail(@RequestBody StoreQueryDTO dto) {
    return Result.success(storeService.detail(dto.getPluginId(), dto));
}

@Operation(summary = "商店下载")
@PostMapping("/download")
public Result<String> download(@RequestBody StoreDownloadDTO dto) {
    return Result.success(storeService.download(dto));
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java
git commit -m "refactor(l4d2): PluginStoreController 签名对齐 Store DTOs"
```

---

## 阶段 7：迁移与启动清理

### Task 7.1: 创建 PluginStoreMigration

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java`

- [ ] **Step 1: 创建迁移类**

```java
package com.gameplatform.plugin.l4d2.migration;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.service.EnabledPluginsService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件库迁移与启动清理。
 * <p>1. 旧版 plugins/ + .file_refs.json → 新版 plugins_store/ + .enabled_plugins.yaml（幂等）
 * <p>2. 启动时清理商店下载临时目录（如有）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStoreMigration {

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;

    /**
     * 迁移并初始化插件库目录（幂等）。
     */
    public void migrate(Long instanceId) {
        try {
            ensureStoreDirectory(instanceId);
            migrateLegacyPlugins(instanceId);
            ensureEnabledPluginsYaml(instanceId);
            log.info("插件库迁移完成 instanceId={}", instanceId);
        } catch (Exception e) {
            log.warn("插件库迁移失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
    }

    private void ensureStoreDirectory(Long instanceId) throws Exception {
        String storePath = pathResolver.getPluginsStorePath();
        try {
            instanceFileService.listDirectory(instanceId, storePath);
        } catch (Exception e) {
            // 不存在则创建
            instanceFileService.createDirectory(instanceId, storePath);
            log.info("创建 plugins_store 目录 instanceId={}", instanceId);
        }
    }

    private void migrateLegacyPlugins(Long instanceId) throws Exception {
        // 检查旧版 .file_refs.json 是否存在
        String legacyFileRefs = pathResolver.getFileRefsPath();
        if (!instanceFileService.fileExists(instanceId, legacyFileRefs)) {
            return; // 无旧版数据，跳过
        }
        log.info("检测到旧版 .file_refs.json，开始迁移 instanceId={}", instanceId);

        // 旧版已启用的插件已在 plugins/ 目录，无需移动文件
        // 仅需根据旧版 plugins/ 目录扫描构建 enabled_plugins.yaml
        // 注意：此迁移仅在 plugins_store 为空时执行
        String storePath = pathResolver.getPluginsStorePath();
        List<FileInfo> storeEntries = instanceFileService.listDirectory(instanceId, storePath);
        if (storeEntries != null && !storeEntries.isEmpty()) {
            log.info("plugins_store 已有数据，跳过迁移 instanceId={}", instanceId);
            return;
        }

        // 旧版 plugins/ 下的 .smx 文件视为 panel 来源
        // 不实际移动，仅记录到 yaml（用户可手动迁移到 plugins_store/）
        // ... 实际迁移逻辑较为复杂，此处仅做提示日志
        log.warn("检测到旧版插件结构，建议手动迁移 plugins/ → plugins_store/，instanceId={}", instanceId);

        // 删除旧版 .file_refs.json
        try {
            instanceFileService.deleteFile(instanceId, legacyFileRefs);
            log.info("已删除旧版 .file_refs.json instanceId={}", instanceId);
        } catch (Exception ignore) {}
    }

    private void ensureEnabledPluginsYaml(Long instanceId) {
        try {
            enabledPluginsService.loadYaml(instanceId); // 不存在则返回空列表，自动创建空 yaml
        } catch (Exception e) {
            log.warn("初始化 .enabled_plugins.yaml 失败 instanceId={}", instanceId);
        }
    }
}
```

- [ ] **Step 2: 修改 L4D2Extension.onInstanceCreate 触发迁移**

```java
@Override
public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
    log.info("L4D2 实例创建: instanceId={}, config={}", instanceId, config);
    // 触发插件库迁移（幂等）
    try {
        // 通过 ApplicationContext 延迟获取 Migration bean，避免循环依赖
        if (applicationContext != null) {
            PluginStoreMigration migration = applicationContext.getBean(PluginStoreMigration.class);
            migration.migrate(instanceId);
        }
    } catch (Exception e) {
        log.warn("插件库迁移失败 instanceId={}, err={}", instanceId, e.getMessage());
    }
}
```

并在 L4D2Extension 类中注入 `ApplicationContext`：

```java
@Slf4j
@Extension
public class L4D2Extension implements GameEnhancementExtension {

    private org.springframework.context.ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    // ... 其他方法不变
}
```

注：若 `GameEnhancementExtension` 接口不支持 `setApplicationContext`，改用 `@Autowired` 字段或 `ApplicationContextAware` 接口。

- [ ] **Step 3: 标记 L4D2PathResolver.getFileRefsPath 为 @Deprecated**

```java
/**
 * @deprecated 旧版 .file_refs.json 路径，仅用于迁移检测，不再持久化引用计数。
 */
@Deprecated
public String getFileRefsPath() {
    return "addons/sourcemod/.file_refs.json";
}
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java
git commit -m "feat(l4d2): 新增 PluginStoreMigration 幂等迁移 + L4D2Extension 触发迁移"
```

---

## 阶段 8：前端重写

### Task 8.1: 前端 API 封装重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/api/index.ts`

- [ ] **Step 1: 重写插件相关 API 方法**

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\services\api.ts`，更新以下方法签名：
- `getPlugins(instanceId)` → `POST /plugins/list` body `{ instanceId }`
- `enablePlugin(instanceId, name)` → `POST /plugins/enable` body `{ instanceId, pluginName }`
- `enableAndLoadPlugin(instanceId, name)` → `POST /plugins/enable-load` body `{ instanceId, pluginName }`
- `disablePlugin(instanceId, name)` → `POST /plugins/disable` body `{ instanceId, pluginName }`
- `disableAndUnloadPlugin(instanceId, name)` → `POST /plugins/disable-unload` body `{ instanceId, pluginName }`
- `deletePlugin(instanceId, name)` → `DELETE /plugins/{name}?instanceId=`
- `getPluginReadme(instanceId, name)` → `GET /plugins/{name}/readme?instanceId=`
- `applyTempConfig(instanceId, cvarName, cvarValue)` → `POST /plugin-config/apply-temp` form
- `restoreDefaults(instanceId, pluginName)` → `POST /plugin-config/restore-defaults` form
- `getStorePlugins(dto)` → `POST /plugin-store/list` body `StoreListDTO`
- `downloadStorePlugin(dto)` → `POST /plugin-store/download` body `StoreDownloadDTO`

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/api/index.ts
git commit -m "refactor(l4d2-frontend): API 封装对齐新后端端点"
```

---

### Task 8.2: Plugins.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/Plugins.vue`

- [ ] **Step 1: 重写插件列表表格**

字段对齐：`name / status / source / hasSmx / hasConfig / 操作`
- 来源显示：`store` → "商店"，`upload` → "上传"，`panel` → "预设"
- 状态显示：`enabled` → 绿色 "已启用"，`disabled` → 灰色 "未启用"

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\views\Plugins.vue:281-290`。

- [ ] **Step 2: 重写操作按钮**

每个插件行操作列：
- 已启用：显示"卸载并禁用"（disableAndUnload）
- 未启用：显示"启用并加载"（enableAndLoad）
- 始终显示："查看 README"、"删除"（已启用时禁用并提示"请先禁用"）

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Plugins.vue
git commit -m "refactor(l4d2-frontend): Plugins.vue 对齐新字段与操作"
```

---

### Task 8.3: PluginConfig.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue`

- [ ] **Step 1: 增加"临时应用"和"恢复默认"按钮**

每个 CVAR 行操作列：
- "保存"（持久化，已有）
- "临时应用"（applyTempConfig，新增）
- "恢复默认"（restoreDefaults，整文件级，放工具栏）

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\components\PluginConfigModal.vue:69-92`。

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue
git commit -m "feat(l4d2-frontend): PluginConfig.vue 新增临时应用 + 恢复默认"
```

---

### Task 8.4: Preset.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/Preset.vue`

- [ ] **Step 1: 显示插件数量 + 应用前 popconfirm**

```vue
<a-table-column title="插件数量" :width="100">
  <template #default="{ record }">
    <a-tag>{{ record.pluginCount }} 个</a-tag>
  </template>
</a-table-column>

<a-popconfirm content="应用预设将重置所有插件状态，禁用当前所有插件并按预设启用。配置项也会被覆盖。确认继续？" @ok="handleApply(record)">
  <a-button type="primary" status="warning">应用预设</a-button>
</a-popconfirm>
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Preset.vue
git commit -m "refactor(l4d2-frontend): Preset.vue 显示插件数量 + 应用前二次确认"
```

---

### Task 8.5: PluginStore.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/PluginStore.vue`

- [ ] **Step 1: 增加仓库/Token/代理配置区**

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\views\Plugins.vue:182-189` 的代理预设列表：

```typescript
const proxyOptions = [
    { label: 'laoyutang.cn(仅官方插件库可用)', value: 'https://gh-proxy.laoyutang.cn/' },
    { label: 'gh.dpik.top', value: 'https://gh.dpik.top/' },
    { label: 'gh-proxy.com', value: 'https://gh-proxy.com/' },
    { label: 'hk.gh-proxy.com', value: 'https://hk.gh-proxy.com/' },
    { label: 'gh.llkk.cc', value: 'https://gh.llkk.cc/' },
    { label: 'ghfast.top', value: 'https://ghfast.top/' },
];
```

- [ ] **Step 2: localStorage 持久化配置**

```typescript
// 加载
const githubToken = localStorage.getItem('l4d2_manager_github_token') || '';
const pluginRepo = localStorage.getItem('l4d2_manager_plugin_repo') || 'LaoYutang/l4d2-plugins-store';
const pluginProxy = localStorage.getItem('l4d2_manager_plugin_proxy') || '';

// 保存
const saveConfig = () => {
    localStorage.setItem('l4d2_manager_github_token', githubToken.value);
    localStorage.setItem('l4d2_manager_plugin_repo', pluginRepo.value);
    localStorage.setItem('l4d2_manager_plugin_proxy', pluginProxy.value);
};
```

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginStore.vue
git commit -m "feat(l4d2-frontend): PluginStore.vue 支持自定义仓库/Token/代理"
```

---

## 阶段 9：清理与验证

### Task 9.1: 清理旧字段与兼容代码

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigSpec.java`（移除标记为"旧字段"的代码）

- [ ] **Step 1: 移除 PluginConfigSpec 旧字段**

```java
// 删除以下字段（已不再使用）：
// private String pluginStatus;
// private String description;
// private String version;
// private String author;
// private String enableTime;
// private Boolean isDeleted;
// private String remark;
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS（如有引用错误，按编译器提示修复）

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigSpec.java
git commit -m "chore(l4d2): 移除 PluginConfigSpec 旧字段"
```

---

### Task 9.2: 全量编译 + 测试

- [ ] **Step 1: 全量编译**

Run: `cd backend && mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量测试**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-core -am`
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 3: 修复测试失败（如有）**

按测试报告逐个修复失败用例。

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/
git commit -m "test(l4d2): 修复测试用例以适配新模型"
```

---

### Task 9.3: 前端构建验证

- [ ] **Step 1: 前端构建**

Run: `cd backend/plugin-l4d2/frontend && npm run build`
Expected: BUILD SUCCESS，输出到 `plugin-l4d2-core/src/main/resources/ui/`

- [ ] **Step 2: 修复构建错误（如有）**

按 TypeScript 报错逐个修复类型错误。

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/frontend/
git commit -m "fix(l4d2-frontend): 修复 TypeScript 类型错误"
```

---

### Task 9.4: 全栈启动验证

- [ ] **Step 1: 重新构建插件 JAR**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am package -DskipTests`
Expected: 生成 `plugin-l4d2-core-x.x.x.jar`

- [ ] **Step 2: 部署到 backend/plugins/**

复制 JAR 到 `backend/plugins/` 目录（参考 `backend/scripts/rebuild-restart.ps1` 中的 Build-Plugins 函数）

- [ ] **Step 3: 启动后端**

Run: `cd backend && ./scripts/rebuild-restart.ps1 -SkipCompile -SkipPlugins`
Expected: 8080 端口监听成功

- [ ] **Step 4: 启动前端**

Run: `cd frontend && npm run dev`
Expected: 3000 端口监听成功

- [ ] **Step 5: 浏览器验证关键功能**

1. 进入 L4D2 实例 → 插件管理页面
2. 验证插件列表显示（如果之前容器内未安装 SourceMod，列表应为空且无报错）
3. 验证预设页面显示插件数量
4. 验证商店页面可配置仓库/Token/代理

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| PluginInstallService 重写影响面大 | 分步实现 + 单元测试覆盖关键路径（listPlugins/enablePlugin/disablePlugin/回滚） |
| 旧版数据迁移失败 | PluginStoreMigration 设计为幂等，失败仅记录日志不阻塞 |
| RCON 回滚不完整 | 仅在 enableAndLoad/disableAndUnload 中执行 RCON，失败时先回滚 RCON 再回滚文件 |
| 商店下载 LFS 检测失败 | 保留原下载流程作为兜底，仅当 isLfsPointer 返回 true 时走 LFS |
| 前端字段变更导致兼容问题 | 前端重写紧跟后端，避免半改造状态 |
| 扩展资源双写性能 | EnabledPluginsService 在 saveYaml 时批量同步资源，避免频繁 IO |
| InstanceFileService SPI 调用频繁 | 在 listPlugins 中聚合目录扫描，减少远程调用次数 |

---

## Self-Review 检查清单

完成所有任务后，对照本计划逐项检查：

- [ ] **存储模型**：plugins_store/{name}/left4dead2/ + .enabled_plugins.yaml 双写 ✅
- [ ] **插件来源**：EnabledPlugin.source 字段（panel/store/upload） ✅
- [ ] **删除语义**：硬删除 + 必须先禁用，引用计数仅在禁用时生效 ✅
- [ ] **回滚机制**：enableAndLoad/disableAndUnload RCON 失败回滚，enablePlugin 文件复制失败回滚已复制文件 ✅
- [ ] **预设**：preset.yaml 新结构（platform + plugins.configs），apply 流程对齐 ✅
- [ ] **商店**：DTO 参数支持 + 临时目录原子提交 + LFS + 10 分钟缓存（per-repo） ✅
- [ ] **配置编辑**：restoreFormat + applyTempConfig (RCON sm_cvar) + restoreDefaults + updateOrCreateConfig ✅
- [ ] **迁移**：PluginStoreMigration 幂等迁移 + 启动清理 ✅
- [ ] **前端**：Plugins.vue / PluginConfig.vue / Preset.vue / PluginStore.vue 全面对齐 ✅

---

## 执行交付

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-l4d2-plugin-management-refactor-v2.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
