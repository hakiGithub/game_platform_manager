# L4D2 Plugin Management Focused Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Realign L4D2 plugin management with the open source `l4d2-server-next` project across 7 dimensions — storage model, plugin sources, delete semantics, rollback mechanism, presets, store, and config editor — by rewriting the existing service layer to mirror the reference semantics while preserving our Spring Boot + remote-instance architecture.

**Architecture:** Plugin binaries live in `addons/sourcemod/plugins_store/{name}/left4dead2/...` (library area); enabling copies files into the game's `left4dead2/...` (game area). `.enabled_plugins.yaml` is the single source of truth (which plugins are enabled + their copied file list + source). `fileRefs` is an in-memory map rebuilt from yaml on startup that tracks shared file references; only when refs hit zero is the file deleted from the game area. RCON `sm plugins load/unload` is the runtime hot-reload path with two-stage rollback (unload already-loaded / reload already-unloaded). Presets only manipulate files (no RCON); the server picks up changes on next restart. Store uses GitHub Trees API + LFS Batch API with atomic temp-dir commit. Config editor preserves original structure on update and rebuilds full comments on restore.

**Tech Stack:** Java 17, Spring Boot 3.2.5, MyBatis-Plus, Jackson (YAML via `YAMLFactory`), InstanceFileService SPI (remote file ops), RconService (Source RCON protocol), PF4J plugin framework.

---

## Reference Baseline (l4d2-server-next → our project)

| Topic | Reference File (`D:\program\open_source\l4d2-server-next-master\backend`) | Our Target File |
|---|---|---|
| Storage Model | `logic/plugins.go` (EnablePlugin/DisablePlugin with concurrent copy + fileRefs) | `service/PluginInstallService.java` (rewrite) |
| Plugin Sources | `logic/plugins.go` `writePluginSource` + `plugin_sources` map | `service/PluginSourceService.java` (new) |
| Delete Semantics | `logic/plugins.go` `DeletePlugin` (reject if enabled + `os.RemoveAll`) | `service/PluginInstallService.deletePlugin` (rewrite) |
| Rollback Mechanism | `logic/plugins.go` `EnableAndLoadPlugin` / `DisableAndUnloadPlugin` + `rollbackLoadedSMXPlugins` / `rollbackUnloadedSMXPlugins` | `service/PluginInstallService.enableAndLoad` / `disableAndUnload` (rewrite) |
| Presets | `logic/preset.go` + `preset.yaml` (platform map + `plugins[].configs`) | `config/PresetConfig.java` + `vo/PresetDetailVO.java` + `service/PresetService.java` + `resources/preset.yaml` (rewrite) |
| Store | `logic/plugin_store.go` (GitHub Trees + LFS Batch + atomic temp dir + 3 concurrent + retry + dedup + README) | `service/PluginStoreService.java` + `util/GitHubApiClient.java` (enhance) |
| Config Editor | `logic/config_parser.go` + `logic/plugin_config.go` + `logic/plugin_export.go` (restore format, console blacklist, header filter, l4d2↔l4d alias) | `parser/SourceModCfgParser.java` + `service/SourceModCfgService.java` (enhance) |

### Key Architecture Differences (reference → our project)

1. **File system = remote**: Reference uses `os.Open`/`os.WriteFile` directly. We use `InstanceFileService` SPI that routes to SFTP (Native/LinuxGSM) or `docker exec`/`docker cp` (Docker). All file ops take `(instanceId, relativePath)`.
2. **No local `getStorePath()`**: Reference stores plugins at `./plugins/`. We store at `<instanceRoot>/addons/sourcemod/plugins_store/` resolved via `L4D2PathResolver`.
3. **Plugin source persistence**: Reference writes `plugin_sources` map into `plugins.yaml`. We embed `source` field directly in `.enabled_plugins.yaml`'s `enabled_plugins[].source` (already implemented in `EnabledPlugin`).
4. **RCON not always available**: Reference assumes RCON works. We make RCON optional — `enable()` (file-only) vs `enableAndLoad()` (file + RCON). Preset uses `enable()` only.
5. **YAML library**: Reference uses `viper`. We use Jackson `YAMLFactory` (already wired in `EnabledPluginsService`).

### Already-Completed Infrastructure (DO NOT recreate)

- `vo/EnabledPlugin.java` — name/source/enabledAt/files
- `service/EnabledPluginsService.java` — `.enabled_plugins.yaml` load/save/add/remove + ExtensionResource sync
- `service/FileRefsService.java` — in-memory `Map<Long, Map<String, Set<String>>>` with `normalizeRelPath`/`addRefs`/`removeRefs` (returns zeroed files)
- `resolver/L4D2PathResolver.java` — `getPluginsStorePath()` / `getPluginStorePath(name)` / `getPluginLeft4Dead2Path(name)` / `getEnabledPluginsYamlPath()` / `getPluginReadmePath(name)`
- `util/RconFailureDetector.java` — 10-marker failure detection
- `util/ZipSlipGuard.java` — `normalizeAndCheck` + `isMacOSJunk`
- `extension/EnabledPluginResource.java` + `EnabledPluginSpec.java` — for frontend fast query
- `vo/PluginListVO.java` — already has name/status/source/hasSmx/hasConfig/fileList/configFiles/enableTime/createTime/updateTime

---

## File Structure

### Create (new files)

- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginSourceService.java` — write/lookup plugin source in `.enabled_plugins.yaml`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java` — preset entry (name + configs)
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java` — per-plugin cfg override (name + values)
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginStoreRepoVO.java` — store repo + proxy + token params (DTO for store endpoints)
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java` — startup cleanup of `.download_temp/`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginSourceServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/ZipSlipGuardTest.java`

### Modify (existing files)

- `service/PluginInstallService.java` — full rewrite (storage model + sources + delete + rollback + concurrent copy + zip slip)
- `service/PresetService.java` — full rewrite (no RCON, new preset structure)
- `service/PluginStoreService.java` — enhance (atomic commit, dedup, retry, LFS size check, repo params)
- `util/GitHubApiClient.java` — add `getTree(repo, branch, forceRefresh)` overload + `parseLfsPointer` + `getLfsDownloadUrl(oid, size)` returning size-validated URL
- `parser/SourceModCfgParser.java` — add `restoreFormat(List<ConfigItem>)` + console blacklist + header filter
- `service/SourceModCfgService.java` — add `applyTempConfig`/`restoreDefaults`/`updateOrCreateConfig` + l4d2↔l4d alias candidates
- `config/PresetConfig.java` — change to platform map + presets list (matching reference)
- `vo/PresetDetailVO.java` — replace `enabledPlugins`/`disabledPlugins`/`configOverrides` with `plugins: List<PresetPlugin>`
- `src/main/resources/preset.yaml` — replace with reference structure
- `controller/PluginManageController.java` — adjust endpoint signatures
- `controller/PluginConfigController.java` — add `restore-defaults`/`apply-temp` endpoints
- `controller/PluginStoreController.java` — add `repo`/`proxy`/`token` params + `readme` endpoint
- `controller/PresetController.java` — adjust to new VO
- `frontend/src/views/Plugins.vue`, `PluginConfig.vue`, `Preset.vue`, `PluginStore.vue` — rewrite for new fields

### Delete (deprecated files)

- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetVO.java` — replaced by `PresetDetailVO` (list endpoint returns `PresetInfoVO` summary)

---

## Phase 1: Storage Model (Rewrite PluginInstallService)

**Goal:** Replace the old "move .smx between `plugins/` and `plugins/disabled/`" model with the reference's "copy files from `plugins_store/{name}/left4dead2/` to `left4dead2/`" model. Track copied files in `.enabled_plugins.yaml` for clean removal.

**Reference:** `logic/plugins.go` lines 498-629 (`EnablePlugin`) and 631-687 (`DisablePlugin`).

### Task 1.1: Create PluginSourceService

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginSourceService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginSourceServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginSourceServiceTest {

    private InstanceFileService fileService;
    private EnabledPluginsService enabledPluginsService;
    private PluginSourceService service;

    @BeforeEach
    void setup() {
        fileService = mock(InstanceFileService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        L4D2PathResolver resolver = new L4D2PathResolver();
        InstanceQueryService queryService = mock(InstanceQueryService.class);
        ObjectMapper mapper = new ObjectMapper();
        service = new PluginSourceService(fileService, resolver, enabledPluginsService, queryService, mapper);
    }

    @Test
    void writeSource_forUpload_addsEnabledPluginWithSource() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        service.writeSource(1L, "myplugin", "upload", List.of("addons/sourcemod/plugins/myplugin.smx"));
        ArgumentCaptor<List<EnabledPlugin>> captor = ArgumentCaptor.forClass(List.class);
        verify(enabledPluginsService).saveYaml(eq(1L), captor.capture());
        EnabledPlugin saved = captor.getValue().get(0);
        assertThat(saved.getName()).isEqualTo("myplugin");
        assertThat(saved.getSource()).isEqualTo("upload");
        assertThat(saved.getFiles()).containsExactly("addons/sourcemod/plugins/myplugin.smx");
    }

    @Test
    void writeSource_forStore_notEnabledYet_writesPluginYaml() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        service.writeSource(1L, "storeplugin", "store", List.of());
        verify(fileService).writeTextFile(eq(1L), eq("addons/sourcemod/plugins_store/storeplugin/plugin.yaml"), contains("source: store"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginSourceServiceTest -DfailIfNoTests=false`
Expected: FAIL with `cannot find symbol PluginSourceService`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件来源记录服务。
 *
 * <p>对齐 l4d2-server-next 的 writePluginSource：在 .enabled_plugins.yaml 中
 * 记录每个插件的 source（panel/store/upload）和文件清单。
 *
 * <p>三类调用入口：
 * <ul>
 *   <li>上传安装 → source=upload</li>
 *   <li>商店下载 → source=store（未启用，仅写 plugin.yaml）</li>
 *   <li>面板手动添加 → source=panel</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginSourceService {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;
    private final InstanceQueryService instanceQueryService;
    private final ObjectMapper yamlMapper;

    /**
     * 记录插件来源与文件清单。
     *
     * <p>策略：
     * <ul>
     *   <li>若插件已启用（出现在 .enabled_plugins.yaml 中）：更新其 source 与 files 字段</li>
     *   <li>否则：仅写入 plugins_store/{name}/plugin.yaml 作为元数据</li>
     * </ul>
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名
     * @param source     来源（panel/store/upload）
     * @param files      文件清单（相对 left4dead2/），可为空
     */
    public void writeSource(Long instanceId, String pluginName, String source, List<String> files) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "插件名不能为空");
        }
        List<EnabledPlugin> current = new ArrayList<>(enabledPluginsService.loadYaml(instanceId));
        EnabledPlugin target = current.stream()
                .filter(p -> pluginName.equals(p.getName()))
                .findFirst()
                .orElse(null);

        if (target != null) {
            target.setSource(source);
            if (files != null && !files.isEmpty()) {
                target.setFiles(files);
            }
            enabledPluginsService.saveYaml(instanceId, current);
        } else {
            writePluginYaml(instanceId, pluginName, source, files);
        }
    }

    /**
     * 仅写入 plugins_store/{name}/plugin.yaml（不启用）。
     */
    public void writePluginYaml(Long instanceId, String pluginName, String source, List<String> files) {
        String path = pathResolver.getPluginYamlPath(pluginName);
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("name", pluginName);
            root.put("source", source != null ? source : "panel");
            root.put("files", files != null ? files : List.of());
            root.put("created_at", LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            String yaml = yamlMapper.writeValueAsString(root);
            instanceFileService.writeTextFile(instanceId, path, yaml);
        } catch (Exception e) {
            log.warn("写入 plugin.yaml 失败 instanceId={}, pluginName={}, err={}",
                    instanceId, pluginName, e.getMessage());
        }
    }

    /**
     * 读取插件来源（先查 .enabled_plugins.yaml，再回退到 plugin.yaml）。
     */
    public String readSource(Long instanceId, String pluginName) {
        List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
        for (EnabledPlugin ep : enabled) {
            if (pluginName.equals(ep.getName())) {
                return ep.getSource() != null ? ep.getSource() : "panel";
            }
        }
        try {
            String yaml = instanceFileService.readTextFile(
                    instanceId, pathResolver.getPluginYamlPath(pluginName), StandardCharsets.UTF_8);
            if (yaml == null || yaml.isBlank()) return "panel";
            @SuppressWarnings("unchecked")
            Map<String, Object> m = yamlMapper.readValue(yaml, Map.class);
            Object s = m.get("source");
            return s != null ? s.toString() : "panel";
        } catch (Exception e) {
            return "panel";
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginSourceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginSourceService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginSourceServiceTest.java
git commit -m "feat(l4d2): add PluginSourceService for source tracking in .enabled_plugins.yaml"
```

### Task 1.2: Rewrite PluginInstallService — listPlugins (scan plugins_store)

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java`

**Goal:** Replace `listPlugins` to scan `plugins_store/{name}/` directories instead of `plugins/` and `plugins/disabled/`. Status is determined by checking `.enabled_plugins.yaml`.

- [ ] **Step 1: Write the failing test**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceTest {

    private PluginInstallService service;
    private InstanceQueryService instanceQueryService;
    private InstanceFileService instanceFileService;
    private EnabledPluginsService enabledPluginsService;
    private PluginSourceService pluginSourceService;
    private L4D2PathResolver pathResolver;

    @BeforeEach
    void setup() {
        instanceQueryService = mock(InstanceQueryService.class);
        instanceFileService = mock(InstanceFileService.class);
        RconService rconService = mock(RconService.class);
        FileRefsService fileRefsService = mock(FileRefsService.class);
        pathResolver = new L4D2PathResolver();
        enabledPluginsService = mock(EnabledPluginsService.class);
        pluginSourceService = mock(PluginSourceService.class);
        Charset gbk = GbkCodecUtil.gbk();
        InstanceVO vo = new InstanceVO();
        vo.setId(1L);
        vo.setHostId(10L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(vo);
        service = new PluginInstallService(
                instanceQueryService, instanceFileService, rconService, fileRefsService,
                pathResolver, enabledPluginsService, pluginSourceService, gbk);
    }

    @Test
    void listPlugins_scansPluginsStore_andMarksStatusFromYaml() {
        FileInfo storeDir = new FileInfo("myplugin", true, 0, "addons/sourcemod/plugins_store/myplugin");
        when(instanceFileService.listFiles(eq(1L), eq("addons/sourcemod/plugins_store")))
                .thenReturn(List.of(storeDir));
        when(instanceFileService.exists(eq(1L), eq("addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(true);
        when(instanceFileService.listFiles(eq(1L), eq("addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(List.of(new FileInfo("myplugin.smx", false, 1024, ".../myplugin.smx")));
        when(instanceFileService.exists(eq(1L), eq("addons/sourcemod/plugins_store/myplugin/left4dead2/cfg/sourcemod")))
                .thenReturn(false);
        EnabledPlugin enabled = new EnabledPlugin();
        enabled.setName("myplugin");
        enabled.setSource("upload");
        enabled.setEnabledAt(System.currentTimeMillis());
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(enabled));

        List<PluginListVO> result = service.listPlugins(1L);

        assertThat(result).hasSize(1);
        PluginListVO vo = result.get(0);
        assertThat(vo.getName()).isEqualTo("myplugin");
        assertThat(vo.getStatus()).isEqualTo("enabled");
        assertThat(vo.getSource()).isEqualTo("upload");
        assertThat(vo.getHasSmx()).isTrue();
        assertThat(vo.getHasConfig()).isFalse();
    }

    @Test
    void listPlugins_pluginNotInYaml_returnsDisabled() {
        FileInfo storeDir = new FileInfo("other", true, 0, "addons/sourcemod/plugins_store/other");
        when(instanceFileService.listFiles(eq(1L), eq("addons/sourcemod/plugins_store")))
                .thenReturn(List.of(storeDir));
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());

        List<PluginListVO> result = service.listPlugins(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("disabled");
        assertThat(result.get(0).getSource()).isEqualTo("panel");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: FAIL — `listPlugins` still uses old `scanSmxFiles(pluginsPath)` logic.

- [ ] **Step 3: Update PluginInstallService constructor + listPlugins**

Replace the existing fields + constructor + `listPlugins` method with the following. Keep all other methods for now (they will be rewritten in subsequent tasks).

```java
// === New fields ===
private final EnabledPluginsService enabledPluginsService;
private final PluginSourceService pluginSourceService;

// === New constructor (replaces @RequiredArgsConstructor) ===
public PluginInstallService(InstanceQueryService instanceQueryService,
                            InstanceFileService instanceFileService,
                            RconService rconService,
                            FileRefsService fileRefsService,
                            L4D2PathResolver pathResolver,
                            EnabledPluginsService enabledPluginsService,
                            PluginSourceService pluginSourceService,
                            Charset gbk) {
    this.instanceQueryService = instanceQueryService;
    this.instanceFileService = instanceFileService;
    this.rconService = rconService;
    this.fileRefsService = fileRefsService;
    this.pathResolver = pathResolver;
    this.enabledPluginsService = enabledPluginsService;
    this.pluginSourceService = pluginSourceService;
    this.gbk = gbk;
}
```

Remove `@RequiredArgsConstructor` annotation. Add `import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;`.

Replace `listPlugins` body:

```java
/**
 * 列出 plugins_store 下所有插件，状态来自 .enabled_plugins.yaml。
 *
 * <p>对齐 l4d2-server-next GetPlugins：扫描 plugins_store 子目录，
 * 与 enabled_plugins yaml 取并集，状态由 yaml 决定。
 */
public List<PluginListVO> listPlugins(Long instanceId) {
    requireInstance(instanceId);
    String storePath = pathResolver.getPluginsStorePath();
    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    java.util.Map<String, EnabledPlugin> enabledMap = new java.util.HashMap<>();
    for (EnabledPlugin ep : enabled) {
        enabledMap.put(ep.getName(), ep);
    }

    // 扫描磁盘子目录
    java.util.Map<String, PluginListVO> result = new java.util.LinkedHashMap<>();
    try {
        List<FileInfo> entries = instanceFileService.listFiles(instanceId, storePath);
        if (entries != null) {
            for (FileInfo entry : entries) {
                if (!entry.isDirectory()) continue;
                String name = entry.getName();
                if (".download_temp".equals(name) || ".export_temp".equals(name)) continue;
                result.put(name, buildStorePluginVO(instanceId, name, enabledMap.get(name)));
            }
        }
    } catch (Exception e) {
        log.warn("扫描 plugins_store 失败 instanceId={}, err={}", instanceId, e.getMessage());
    }

    // 补充 yaml 中存在但磁盘上不存在的插件（标记为 enabled 但 missing）
    for (EnabledPlugin ep : enabled) {
        if (!result.containsKey(ep.getName())) {
            PluginListVO vo = new PluginListVO();
            vo.setName(ep.getName());
            vo.setStatus("enabled");
            vo.setSource(ep.getSource() != null ? ep.getSource() : "panel");
            vo.setHasSmx(false);
            vo.setHasConfig(false);
            vo.setEnableTime(toLocalDateTime(ep.getEnabledAt()));
            result.put(ep.getName(), vo);
        }
    }
    return new java.util.ArrayList<>(result.values());
}

private PluginListVO buildStorePluginVO(Long instanceId, String name, EnabledPlugin ep) {
    PluginListVO vo = new PluginListVO();
    vo.setName(name);
    boolean isEnabled = ep != null;
    vo.setStatus(isEnabled ? "enabled" : "disabled");
    vo.setSource(ep != null && ep.getSource() != null ? ep.getSource() : pluginSourceService.readSource(instanceId, name));
    String pluginL4d2 = pathResolver.getPluginLeft4Dead2Path(name);
    String smxDir = pluginL4d2 + "/addons/sourcemod/plugins";
    String cfgDir = pluginL4d2 + "/cfg/sourcemod";
    vo.setHasSmx(hasSmxFiles(instanceId, smxDir));
    vo.setHasConfig(hasCfgFiles(instanceId, cfgDir));
    if (isEnabled) {
        vo.setEnableTime(toLocalDateTime(ep.getEnabledAt()));
    }
    vo.setCreateTime(LocalDateTime.now());
    vo.setUpdateTime(LocalDateTime.now());
    return vo;
}

private boolean hasSmxFiles(Long instanceId, String dir) {
    try {
        if (!instanceFileService.exists(instanceId, dir)) return false;
        List<FileInfo> files = instanceFileService.listFiles(instanceId, dir);
        if (files == null) return false;
        return files.stream().anyMatch(f -> !f.isDirectory() && f.getName() != null
                && f.getName().toLowerCase().endsWith(SMX_SUFFIX));
    } catch (Exception e) {
        return false;
    }
}

private boolean hasCfgFiles(Long instanceId, String dir) {
    try {
        if (!instanceFileService.exists(instanceId, dir)) return false;
        List<FileInfo> files = instanceFileService.listFiles(instanceId, dir);
        if (files == null) return false;
        return files.stream().anyMatch(f -> !f.isDirectory() && f.getName() != null
                && f.getName().toLowerCase().endsWith(".cfg"));
    } catch (Exception e) {
        return false;
    }
}

private LocalDateTime toLocalDateTime(Long epochMillis) {
    if (epochMillis == null) return null;
    return LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            java.time.ZoneId.systemDefault());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): rewrite listPlugins to scan plugins_store and use .enabled_plugins.yaml"
```

### Task 1.3: Rewrite PluginInstallService.enable (concurrent copy + fileRefs)

**Reference:** `logic/plugins.go` lines 498-629.

**Goal:** Replace old "move .smx from disabled to plugins" with: walk `plugins_store/{name}/left4dead2/`, copy each file to `left4dead2/{relPath}`, record in `.enabled_plugins.yaml`, update `fileRefs`.

- [ ] **Step 1: Write the failing test**

Append to `PluginInstallServiceTest.java`:

```java
@Test
void enablePlugin_walksStoreDir_copiesFilesToGame_andRecordsInYaml() throws Exception {
    when(instanceFileService.walkFiles(eq(1L), eq("addons/sourcemod/plugins_store/myplugin/left4dead2")))
            .thenReturn(List.of(
                    new FileInfo("addons/sourcemod/plugins/myplugin.smx", false, 100, ".../myplugin.smx"),
                    new FileInfo("cfg/sourcemod/myplugin.cfg", false, 50, ".../myplugin.cfg")));
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
    when(pluginSourceService.readSource(1L, "myplugin")).thenReturn("upload");

    service.enable(1L, "myplugin");

    verify(instanceFileService).writeBytes(eq(1L), eq("addons/sourcemod/plugins/myplugin.smx"), any());
    verify(instanceFileService).writeBytes(eq(1L), eq("cfg/sourcemod/myplugin.cfg"), any());
    verify(fileRefsService).addRefs(eq(1L), eq("myplugin"), org.mockito.internal.util.collections.Sets.newSet("addons/sourcemod/plugins/myplugin.smx", "cfg/sourcemod/myplugin.cfg"));
    verify(enabledPluginsService).saveYaml(eq(1L), org.mockito.ArgumentMatchers.argThat(list -> {
        @SuppressWarnings("unchecked")
        java.util.List<com.gameplatform.plugin.l4d2.vo.EnabledPlugin> l = (java.util.List<com.gameplatform.plugin.l4d2.vo.EnabledPlugin>) list;
        return l.size() == 1 && l.get(0).getName().equals("myplugin")
                && l.get(0).getFiles().size() == 2;
    }));
}

@Test
void enablePlugin_alreadyEnabled_throwsException() {
    EnabledPlugin existing = new EnabledPlugin();
    existing.setName("myplugin");
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(existing));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enable(1L, "myplugin"))
            .hasMessageContaining("已启用");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: FAIL — `enable` method doesn't exist yet.

- [ ] **Step 3: Add enable method to PluginInstallService**

Add at the top of the class (after constructor):

```java
/** 并发复制线程池（CPU 数为上限，对齐 l4d2-server-next ants.NewPool(runtime.NumCPU())） */
private final java.util.concurrent.ExecutorService copyExecutor =
        java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
/** 复制信号量，避免大量小文件同时挤占 SSH 通道 */
private final java.util.concurrent.Semaphore copySemaphore = new java.util.concurrent.Semaphore(8);

/**
 * 启用插件（仅文件操作，不调 RCON）。
 *
 * <p>对齐 l4d2-server-next EnablePlugin：
 * <ol>
 *   <li>校验未启用</li>
 *   <li>遍历 plugins_store/{name}/left4dead2/ 下所有文件</li>
 *   <li>并发复制到 left4dead2/{relPath}</li>
 *   <li>记录到 .enabled_plugins.yaml + 更新 fileRefs</li>
 *   <li>失败时回滚已复制的文件</li>
 * </ol>
 */
public void enable(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);

    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    if (enabled.stream().anyMatch(p -> pluginName.equals(p.getName()))) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "插件已启用: " + pluginName);
    }

    String pluginL4d2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
    if (!existsSafe(instanceId, pluginL4d2)) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件目录不存在或结构无效: " + pluginName);
    }

    List<FileInfo> filesToCopy;
    try {
        filesToCopy = instanceFileService.walkFiles(instanceId, pluginL4d2);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "遍历插件目录失败: " + e.getMessage(), e);
    }
    if (filesToCopy == null || filesToCopy.isEmpty()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件目录为空: " + pluginName);
    }

    List<String> copiedFiles = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.atomic.AtomicReference<Exception> firstErr = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();

    for (FileInfo file : filesToCopy) {
        if (file.isDirectory()) continue;
        String relPath = file.getPath();
        if (relPath == null || relPath.isBlank()) continue;
        // 归一化：去除前导 ./
        relPath = relPath.startsWith("./") ? relPath.substring(2) : relPath;

        futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                copySemaphore.acquire();
                try {
                    byte[] content = instanceFileService.readBytes(instanceId, pluginL4d2 + "/" + relPath);
                    instanceFileService.writeBytes(instanceId, relPath, content);
                    copiedFiles.add(relPath);
                } finally {
                    copySemaphore.release();
                }
            } catch (Exception e) {
                firstErr.compareAndSet(null, e);
            }
        }, copyExecutor));
    }
    java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

    if (firstErr.get() != null) {
        rollbackCopiedFiles(instanceId, copiedFiles);
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "启用插件 " + pluginName + " 失败: " + firstErr.get().getMessage(), firstErr.get());
    }

    // 记录到 yaml + 更新 fileRefs
    EnabledPlugin entry = new EnabledPlugin();
    entry.setName(pluginName);
    entry.setSource(pluginSourceService.readSource(instanceId, pluginName));
    entry.setEnabledAt(System.currentTimeMillis());
    entry.setFiles(new java.util.ArrayList<>(copiedFiles));
    enabledPluginsService.add(instanceId, entry);
    fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
    log.info("插件已启用: instanceId={}, pluginName={}, copiedFiles={}",
            instanceId, pluginName, copiedFiles.size());
}

private void rollbackCopiedFiles(Long instanceId, List<String> copiedFiles) {
    for (String relPath : copiedFiles) {
        try {
            instanceFileService.deleteFile(instanceId, relPath);
        } catch (Exception e) {
            log.warn("回滚删除文件失败 path={}, err={}", relPath, e.getMessage());
        }
    }
}

private boolean existsSafe(Long instanceId, String path) {
    try {
        return instanceFileService.exists(instanceId, path);
    } catch (Exception e) {
        return false;
    }
}
```

**Note:** `InstanceFileService` must expose `walkFiles(instanceId, path)` returning a flat list of `FileInfo` (including directories). Check that this method exists; if not, add it via a separate sub-task (see Task 1.3a below).

- [ ] **Step 4: Verify walkFiles exists on InstanceFileService SPI**

Run: `cd backend && grep -n "walkFiles" plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java`

If missing, add to `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java`:

```java
/**
 * 递归遍历目录下所有文件与子目录（相对路径）。
 * @param instanceId 实例 ID
 * @param relativePath 相对实例根目录的目录路径
 * @return FileInfo 列表（包含目录条目），路径为相对实例根目录
 */
java.util.List<FileInfo> walkFiles(Long instanceId, String relativePath);
```

And implement in `backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java` (Native via SFTP `ls -R` parsed; Docker via `find <workDir> -type f -o -type d` parsed).

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java
git commit -m "feat(l4d2): implement enable() with concurrent copy + fileRefs + rollback"
```

### Task 1.4: Rewrite PluginInstallService.disable (fileRefs-based removal)

**Reference:** `logic/plugins.go` lines 631-687.

- [ ] **Step 1: Write the failing test**

```java
@Test
void disablePlugin_removesZeroedFiles_keepsSharedFiles() {
    EnabledPlugin ep = new EnabledPlugin();
    ep.setName("myplugin");
    ep.setSource("upload");
    ep.setFiles(java.util.List.of("cfg/sourcemod/shared.cfg"));
    when(enabledPluginsService.loadYaml(1L)).thenReturn(new java.util.ArrayList<>(List.of(ep)));
    // fileRefsService.removeRefs returns the shared.cfg (zeroed)
    when(fileRefsService.removeRefs(1L, "myplugin")).thenReturn(java.util.List.of("cfg/sourcemod/shared.cfg"));

    service.disable(1L, "myplugin");

    verify(instanceFileService).deleteFile(1L, "left4dead2/cfg/sourcemod/shared.cfg");
    verify(enabledPluginsService).remove(1L, "myplugin");
}

@Test
void disablePlugin_notEnabled_throwsException() {
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.disable(1L, "missing"))
            .hasMessageContaining("未启用");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: FAIL — `disable` method doesn't exist.

- [ ] **Step 3: Add disable method**

```java
/**
 * 禁用插件（仅文件操作，不调 RCON）。
 *
 * <p>对齐 l4d2-server-next DisablePlugin：
 * <ol>
 *   <li>从 yaml 中找到插件的 files 列表</li>
 *   <li>对每个文件，从 fileRefs 移除本插件引用</li>
 *   <li>引用归零的文件，从游戏目录删除</li>
 *   <li>从 yaml 中移除插件条目</li>
 * </ol>
 */
public void disable(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);

    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    EnabledPlugin target = enabled.stream()
            .filter(p -> pluginName.equals(p.getName()))
            .findFirst()
            .orElseThrow(() -> new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "插件未启用: " + pluginName));

    // 移除引用，获取归零文件
    List<String> zeroedFiles = fileRefsService.removeRefs(instanceId, pluginName);

    // 删除归零的共享文件
    String gamePath = pathResolver.getGamePath();
    for (String relPath : zeroedFiles) {
        try {
            instanceFileService.deleteFile(instanceId, gamePath + "/" + relPath);
        } catch (Exception e) {
            log.warn("删除共享文件失败 instanceId={}, path={}, err={}", instanceId, relPath, e.getMessage());
        }
    }

    // 从 yaml 中移除
    enabledPluginsService.remove(instanceId, pluginName);
    log.info("插件已禁用: instanceId={}, pluginName={}, removedFiles={}",
            instanceId, pluginName, zeroedFiles.size());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "feat(l4d2): implement disable() with fileRefs-based shared file removal"
```

### Task 1.5: Rewrite installFromUpload to write to plugins_store (not directly to plugins/)

**Reference:** `logic/plugins.go` `UploadPlugin` (lines 244-384) — supports single-plugin zip (root is `left4dead2/`) and multi-plugin zip (multiple `pluginName/left4dead2/`).

- [ ] **Step 1: Write the failing test**

```java
@Test
void installFromUpload_singlePluginZip_extractsToPluginsStore() throws Exception {
    // 准备一个临时 zip，结构：left4dead2/addons/sourcemod/plugins/foo.smx
    java.io.File tempZip = buildSinglePluginZip();
    org.springframework.web.multipart.MultipartFile mf = new org.springframework.mock.web.MockMultipartFile(
            "file", "foo.zip", "application/zip", new java.io.FileInputStream(tempZip));

    when(instanceFileService.exists(eq(1L), eq("addons/sourcemod/plugins_store/foo"))).thenReturn(false);

    com.gameplatform.plugin.l4d2.vo.PluginListVO vo = service.installFromUpload(1L, mf);

    assertThat(vo.getName()).isEqualTo("foo");
    assertThat(vo.getSource()).isEqualTo("upload");
    verify(pluginSourceService).writeSource(eq(1L), eq("foo"), eq("upload"), anyList());
    verify(instanceFileService).uploadLocalFile(eq(1L),
            eq("addons/sourcemod/plugins_store/foo/left4dead2/addons/sourcemod/plugins/foo.smx"),
            anyString());
}

private java.io.File buildSinglePluginZip() throws Exception {
    java.io.File tmp = java.io.File.createTempFile("test-plugin-", ".zip");
    try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(tmp))) {
        java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("left4dead2/addons/sourcemod/plugins/foo.smx");
        zos.putNextEntry(e);
        zos.write(new byte[]{1, 2, 3, 4});
        zos.closeEntry();
    }
    return tmp;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: FAIL — current `installFromUpload` writes directly to `plugins/`, not `plugins_store/`.

- [ ] **Step 3: Rewrite installFromUpload + installFromLocalFile**

Replace the existing methods with:

```java
/**
 * 上传安装：保存到临时文件 → 解压到 plugins_store/{name}/left4dead2/。
 *
 * <p>对齐 l4d2-server-next UploadPlugin：
 * <ul>
 *   <li>单插件 zip（根是 left4dead2/）→ 解压到 plugins_store/{zipBaseName}/left4dead2/</li>
 *   <li>多插件 zip（每个根目录是 pluginName/left4dead2/）→ 分别解压到 plugins_store/{pluginName}/</li>
 *   <li>.smx 单文件 → 上传到 plugins_store/{baseName}/left4dead2/addons/sourcemod/plugins/</li>
 *   <li>.vpk 地图 → 上传到 left4dead2/addons/（地图不走插件库）</li>
 * </ul>
 */
public PluginListVO installFromUpload(Long instanceId, MultipartFile file) {
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "上传文件名为空");
    }
    try {
        File tempFile = File.createTempFile("l4d2-upload-", "-" + sanitizeFilename(originalName));
        file.transferTo(tempFile);
        try {
            return installFromLocalFile(instanceId, tempFile, originalName);
        } finally {
            if (!tempFile.delete()) tempFile.deleteOnExit();
        }
    } catch (L4D2PluginException e) {
        throw e;
    } catch (Exception e) {
        log.error("上传安装失败 instanceId={}, fileName={}", instanceId, originalName, e);
        throw new L4D2PluginException(L4D2PluginException.FILE, "上传安装失败: " + e.getMessage(), e);
    }
}

/**
 * 从本地文件安装（供 PluginStoreService 调用）。
 *
 * @param instanceId  实例 ID
 * @param localFile   本地文件
 * @param originalName 原始文件名（用于推导 pluginName，可为 null 时取 localFile.getName()）
 */
public PluginListVO installFromLocalFile(Long instanceId, File localFile) {
    return installFromLocalFile(instanceId, localFile, localFile.getName());
}

public PluginListVO installFromLocalFile(Long instanceId, File localFile, String originalName) {
    requireInstance(instanceId);
    String fileName = originalName != null ? originalName : localFile.getName();
    byte[] header = readFileHeader(localFile, 4);

    // VPK 地图：直接上传到 addons/
    if (ArchiveExtractUtil.isVpkFile(header)) {
        String addonsPath = pathResolver.getAddonsPath();
        String targetPath = addonsPath + "/" + localFile.getName();
        instanceFileService.uploadLocalFile(instanceId, targetPath, localFile.getAbsolutePath());
        PluginListVO vo = new PluginListVO();
        vo.setName(stripExtension(fileName));
        vo.setSource("upload");
        vo.setStatus("disabled");
        return vo;
    }

    String lowerName = fileName.toLowerCase();
    if (lowerName.endsWith(SMX_SUFFIX)) {
        // 单 .smx 文件 → 上传到 plugins_store/{baseName}/left4dead2/addons/sourcemod/plugins/
        String pluginName = stripExtension(fileName);
        String targetDir = pathResolver.getPluginLeft4Dead2Path(pluginName)
                + "/addons/sourcemod/plugins";
        String targetPath = targetDir + "/" + localFile.getName();
        instanceFileService.uploadLocalFile(instanceId, targetPath, localFile.getAbsolutePath());
        pluginSourceService.writeSource(instanceId, pluginName, "upload", List.of(
                "addons/sourcemod/plugins/" + localFile.getName()));
        return buildInstallResultVO(pluginName, "upload");
    }

    // ZIP/7z：解压并处理
    File tempDir = createTempDir("l4d2-extract-");
    try {
        extractArchive(localFile, tempDir);
        return processExtractedArchiveToStore(instanceId, tempDir, fileName);
    } finally {
        deleteRecursive(tempDir);
    }
}

private PluginListVO processExtractedArchiveToStore(Long instanceId, File extractRoot, String zipFileName) {
    // 检测是单插件还是多插件
    File[] children = extractRoot.listFiles();
    if (children == null || children.length == 0) {
        throw new L4D2PluginException(L4D2PluginException.FILE, "解压后目录为空");
    }

    // 单插件：根目录直接是 left4dead2/
    File singleL4d2 = null;
    for (File c : children) {
        if (c.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(c.getName())) {
            singleL4d2 = c;
            break;
        }
    }
    if (singleL4d2 != null) {
        String pluginName = stripExtension(zipFileName);
        copyToPluginsStore(instanceId, singleL4d2, pluginName);
        pluginSourceService.writeSource(instanceId, pluginName, "upload", listFilesRelative(singleL4d2));
        return buildInstallResultVO(pluginName, "upload");
    }

    // 多插件：每个根目录是 pluginName/left4dead2/
    PluginListVO last = null;
    for (File c : children) {
        if (!c.isDirectory()) {
            // 根目录下的 .md 文档：跳过
            if (isMarkdownFile(c.getName())) continue;
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "无效的归档结构，根目录下不应有非 markdown 文件: " + c.getName());
        }
        File l4d2 = findChildByName(c, LEFT_4_DEAD_2);
        if (l4d2 == null) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "插件目录缺少 left4dead2 子目录: " + c.getName());
        }
        copyToPluginsStore(instanceId, l4d2, c.getName());
        pluginSourceService.writeSource(instanceId, c.getName(), "upload", listFilesRelative(l4d2));
        last = buildInstallResultVO(c.getName(), "upload");
    }
    return last;
}

private void copyToPluginsStore(Long instanceId, File localL4d2Dir, String pluginName) {
    String remoteStoreL4d2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
    // 检查是否已存在
    if (existsSafe(instanceId, remoteStoreL4d2)) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件已存在: " + pluginName + "，请先删除");
    }
    try (Stream<Path> stream = Files.walk(localL4d2Dir.toPath())) {
        List<Path> files = stream.filter(Files::isRegularFile).toList();
        for (Path filePath : files) {
            String relPath = localL4d2Dir.toPath().relativize(filePath).toString().replace('\\', '/');
            String remotePath = remoteStoreL4d2 + "/" + relPath;
            instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
        }
    } catch (IOException e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "复制到 plugins_store 失败: " + e.getMessage(), e);
    }
}

private List<String> listFilesRelative(File rootDir) {
    List<String> result = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(rootDir.toPath())) {
        stream.filter(Files::isRegularFile).forEach(p -> {
            String rel = rootDir.toPath().relativize(p).toString().replace('\\', '/');
            result.add(rel);
        });
    } catch (IOException ignored) {}
    return result;
}

private File findChildByName(File parent, String name) {
    File[] children = parent.listFiles();
    if (children == null) return null;
    for (File c : children) {
        if (c.isDirectory() && name.equalsIgnoreCase(c.getName())) return c;
    }
    return null;
}

private boolean isMarkdownFile(String name) {
    return name != null && name.toLowerCase().endsWith(".md");
}

private PluginListVO buildInstallResultVO(String pluginName, String source) {
    PluginListVO vo = new PluginListVO();
    vo.setName(pluginName);
    vo.setStatus("disabled");
    vo.setSource(source);
    vo.setCreateTime(LocalDateTime.now());
    vo.setUpdateTime(LocalDateTime.now());
    return vo;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): installFromUpload extracts to plugins_store/{name}/left4dead2"
```

---

## Phase 2: Plugin Sources

**Goal:** All three entry points (upload / store download / panel add) record `source` consistently. Read source for display in `listPlugins` already implemented in Task 1.2.

### Task 2.1: PluginStoreService records source=store on download success

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`

**Reference:** `logic/plugin_store.go` line 470 `writePluginSource(t.name, "store")` after `os.Rename(t.tempDir, t.finalDir)`.

- [ ] **Step 1: Inject PluginSourceService into PluginStoreService**

Change constructor:

```java
private final GitHubApiClient gitHubApiClient;
private final ExternalHttpClient httpClient;
private final PluginInstallService pluginInstallService;
private final L4D2Config config;
private final PluginSourceService pluginSourceService;

// remove @RequiredArgsConstructor, add explicit constructor
public PluginStoreService(GitHubApiClient gitHubApiClient,
                          ExternalHttpClient httpClient,
                          PluginInstallService pluginInstallService,
                          L4D2Config config,
                          PluginSourceService pluginSourceService) {
    this.gitHubApiClient = gitHubApiClient;
    this.httpClient = httpClient;
    this.pluginInstallService = pluginInstallService;
    this.config = config;
    this.pluginSourceService = pluginSourceService;
}
```

- [ ] **Step 2: After successful download (in `runDownload`, before `task.setStatus(STATUS_COMPLETED)`), call writeSource**

Locate `task.setStatus(STATUS_INSTALLING)` and replace the install flow:

```java
task.setStatus(STATUS_INSTALLING);
// 对齐 l4d2-server-next：商店下载完成后直接写入 plugins_store，
// 不立即安装到游戏目录（保持禁用状态，由用户手动启用）。
pluginInstallService.installFromLocalFile(dto.getInstanceId(), tempFile, dto.getPluginId() + ".zip");

// 记录来源 = store
pluginSourceService.writeSource(dto.getInstanceId(), dto.getPluginId(), "store", List.of());

if (tempFile != null) tempFile.delete();
```

**Note:** If `PluginInstallService.installFromLocalFile` was changed to take `(instanceId, file, originalName)` in Task 1.5, the call still works because we kept the 2-arg overload delegating to the 3-arg one.

- [ ] **Step 3: Verify build**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "feat(l4d2): store download records source=store via PluginSourceService"
```

### Task 2.2: listPlugins enriches with source from yaml/plugin.yaml (already done in Task 1.2)

**Verification only — no new code.**

- [ ] **Step 1: Verify `PluginInstallServiceTest.listPlugins_*` tests pass**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#listPlugins_*`
Expected: PASS

### Task 2.3: PluginInstallService.deletePlugin preserves source cleanup

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`

**Reference:** `logic/plugins.go` `DeletePlugin` cleans `plugin_sources` map after `os.RemoveAll`.

- [ ] **Step 1: Add source cleanup to deletePlugin (final implementation in Task 3.1)**

This is covered by Task 3.1's full deletePlugin rewrite.

---

## Phase 3: Delete Semantics

**Goal:** `deletePlugin` rejects enabled plugins (must disable first), then removes the entire `plugins_store/{name}/` directory and cleans up source metadata. Aligns with `logic/plugins.go` `DeletePlugin` lines 830-863.

### Task 3.1: Rewrite deletePlugin

- [ ] **Step 1: Write the failing test**

Append to `PluginInstallServiceTest.java`:

```java
@Test
void deletePlugin_whenEnabled_throwsException() {
    EnabledPlugin ep = new EnabledPlugin();
    ep.setName("enabled-plugin");
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(ep));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deletePlugin(1L, "enabled-plugin"))
            .hasMessageContaining("请先禁用");
}

@Test
void deletePlugin_whenDisabled_removesStoreDir_andCleansSource() {
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());

    service.deletePlugin(1L, "disabled-plugin");

    verify(instanceFileService).deleteFile(1L, "addons/sourcemod/plugins_store/disabled-plugin");
    // plugin.yaml 也应被清理（随目录删除，无需单独调用 deleteFile）
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#deletePlugin_*`
Expected: FAIL — current `deletePlugin` removes `.smx` from `plugins/` and `disabled/`.

- [ ] **Step 3: Replace deletePlugin body**

```java
/**
 * 删除插件。
 *
 * <p>对齐 l4d2-server-next DeletePlugin：
 * <ol>
 *   <li>如果插件已启用 → 拒绝删除（必须先禁用）</li>
 *   <li>删除 plugins_store/{name}/ 整个目录</li>
 * </ol>
 *
 * <p>注意：不删除游戏目录中已复制的文件（这些由 disable 处理）。
 */
public void deletePlugin(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);

    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    if (enabled.stream().anyMatch(p -> pluginName.equals(p.getName()))) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件已启用，请先禁用再删除: " + pluginName);
    }

    String storeDir = pathResolver.getPluginStorePath(pluginName);
    try {
        instanceFileService.deleteFile(instanceId, storeDir);
    } catch (Exception e) {
        log.warn("删除 plugins_store 目录失败 instanceId={}, pluginName={}, err={}",
                instanceId, pluginName, e.getMessage());
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "删除插件目录失败: " + e.getMessage(), e);
    }
    log.info("插件已删除: instanceId={}, pluginName={}", instanceId, pluginName);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#deletePlugin_*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): deletePlugin rejects enabled plugins + removes plugins_store dir"
```

### Task 3.2: Verify `InstanceFileService.deleteFile` supports directories

**Files:**
- Verify: `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java`

- [ ] **Step 1: Check existing deleteFile contract**

Run: `cd backend && grep -n "deleteFile" plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java`

If the contract doesn't clarify directory support, add a Javadoc note:

```java
/**
 * 删除文件或目录。若为目录，递归删除其下所有内容。
 * @param instanceId 实例 ID
 * @param relativePath 相对实例根目录的路径
 */
void deleteFile(Long instanceId, String relativePath);
```

- [ ] **Step 2: Verify InstanceFileServiceImpl handles directories**

Check `backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java`:
- Native: SFTP `rm -rf <path>`
- Docker: `docker exec <container> rm -rf <path>`

If only file deletion is implemented, extend to support recursive directory deletion.

- [ ] **Step 3: Commit (if changes made)**

```bash
git add backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java
git commit -m "feat(spi): InstanceFileService.deleteFile explicitly supports recursive directory deletion"
```

---

## Phase 4: Rollback Mechanism

**Goal:** `enableAndLoad` (file copy + RCON load) and `disableAndUnload` (RCON unload + file removal) with two-stage rollback. Aligns with `logic/plugins.go` `EnableAndLoadPlugin` (lines 689-718) and `DisableAndUnloadPlugin` (lines 720-751).

### Task 4.1: Implement enableAndLoad with RCON rollback

- [ ] **Step 1: Write the failing test**

```java
@Test
void enableAndLoad_rconLoadFails_rollsBackUnloadedAndDisables() throws Exception {
    // 模拟 walkFiles 返回 2 个 smx
    when(instanceFileService.walkFiles(eq(1L), anyString())).thenReturn(List.of(
            new FileInfo("addons/sourcemod/plugins/foo.smx", false, 100, "..."),
            new FileInfo("addons/sourcemod/plugins/bar.smx", false, 100, "...")));
    when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
    when(pluginSourceService.readSource(1L, "myplugin")).thenReturn("upload");
    // 第一个 smx load 成功，第二个失败
    when(rconService.executeCommand(eq(1L), eq("sm plugins load foo"))).thenReturn("[SM] Loaded foo");
    when(rconService.executeCommand(eq(1L), eq("sm plugins load bar"))).thenReturn("[SM] Plugin not found");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enableAndLoad(1L, "myplugin"))
            .hasMessageContaining("RCON 加载失败");

    // 验证：已加载的 foo 被卸载，插件被禁用（yaml 中无 myplugin）
    verify(rconService).executeCommand(1L, "sm plugins unload foo");
    verify(enabledPluginsService).remove(eq(1L), eq("myplugin"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#enableAndLoad_*`
Expected: FAIL — `enableAndLoad` not yet rewritten.

- [ ] **Step 3: Replace enableAndLoad body**

```java
/**
 * 启用并通过 RCON 加载插件（带失败回滚）。
 *
 * <p>对齐 l4d2-server-next EnableAndLoadPlugin：
 * <ol>
 *   <li>调用 enable() 复制文件到游戏目录</li>
 *   <li>列出插件的所有 .smx 文件（按 pluginID 路径）</li>
 *   <li>逐个 RCON load，记录已成功的</li>
 *   <li>失败时：逆序 unload 已成功的，再调用 disable() 移除文件</li>
 * </ol>
 */
public void enableAndLoad(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);

    // 1. 启用（文件复制）
    enable(instanceId, pluginName);

    // 2. 列出所有 .smx 文件（相对路径，按字典序）
    String pluginL4d2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
    List<FileInfo> allFiles;
    try {
        allFiles = instanceFileService.walkFiles(instanceId, pluginL4d2);
    } catch (Exception e) {
        // walk 失败，回滚 enable
        disable(instanceId, pluginName);
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "列出 .smx 文件失败: " + e.getMessage(), e);
    }
    List<String> smxPluginIds = allFiles.stream()
            .filter(f -> !f.isDirectory())
            .map(FileInfo::getPath)
            .filter(p -> p != null && p.toLowerCase().endsWith(SMX_SUFFIX))
            .map(p -> {
                // 提取 plugin ID：从 addons/sourcemod/plugins/ 之后的相对路径，去 .smx 后缀
                String path = p.startsWith("./") ? p.substring(2) : p;
                int idx = path.toLowerCase().indexOf("addons/sourcemod/plugins/");
                String rel = idx >= 0 ? path.substring(idx + "addons/sourcemod/plugins/".length()) : path;
                return rel.substring(0, rel.length() - SMX_SUFFIX.length());
            })
            .sorted()
            .toList();

    if (smxPluginIds.isEmpty()) {
        log.warn("插件无 .smx 文件，仅启用文件: instanceId={}, pluginName={}", instanceId, pluginName);
        return;
    }

    // 3. 逐个 RCON load
    List<String> loadedIds = new java.util.ArrayList<>();
    for (String pluginId : smxPluginIds) {
        try {
            String cmd = "sm plugins load " + quoteSourceModArg(pluginId);
            String output = rconService.executeCommand(instanceId, cmd);
            if (RconFailureDetector.isFailed(output)) {
                // 回滚：逆序 unload 已加载的
                rollbackLoadedSmx(instanceId, loadedIds);
                // 禁用插件（移除文件）
                disable(instanceId, pluginName);
                throw new L4D2PluginException(L4D2PluginException.RCON,
                        "RCON 加载失败: " + pluginId + ", 输出: " + output);
            }
            loadedIds.add(pluginId);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            rollbackLoadedSmx(instanceId, loadedIds);
            disable(instanceId, pluginName);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 加载异常: " + pluginId + ", " + e.getMessage(), e);
        }
    }
    log.info("插件已启用并加载: instanceId={}, pluginName={}, loadedSmx={}",
            instanceId, pluginName, loadedIds);
}

private void rollbackLoadedSmx(Long instanceId, List<String> loadedIds) {
    // 逆序 unload
    for (int i = loadedIds.size() - 1; i >= 0; i--) {
        try {
            rconService.executeCommand(instanceId, "sm plugins unload " + quoteSourceModArg(loadedIds.get(i)));
        } catch (Exception e) {
            log.warn("回滚 unload 失败 pluginId={}, err={}", loadedIds.get(i), e.getMessage());
        }
    }
}

private String quoteSourceModArg(String value) {
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#enableAndLoad_*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "feat(l4d2): enableAndLoad with RCON failure rollback (unload loaded smx + disable)"
```

### Task 4.2: Implement disableAndUnload with RCON rollback

**Reference:** `logic/plugins.go` `DisableAndUnloadPlugin` lines 720-751 — unload in reverse order, rollback reloads if any unload fails.

- [ ] **Step 1: Write the failing test**

```java
@Test
void disableAndUnload_rconUnloadFails_rollsBackReloaded() throws Exception {
    EnabledPlugin ep = new EnabledPlugin();
    ep.setName("myplugin");
    ep.setSource("upload");
    ep.setFiles(List.of("addons/sourcemod/plugins/foo.smx"));
    when(enabledPluginsService.loadYaml(1L)).thenReturn(new java.util.ArrayList<>(List.of(ep)));
    when(instanceFileService.walkFiles(eq(1L), anyString())).thenReturn(List.of(
            new FileInfo("addons/sourcemod/plugins/foo.smx", false, 100, "...")));
    when(rconService.executeCommand(eq(1L), eq("sm plugins unload foo")))
            .thenReturn("[SM] Failed to unload");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.disableAndUnload(1L, "myplugin"))
            .hasMessageContaining("RCON 卸载失败");

    // 验证：reload 已被调用（虽然这次没有成功 unload 的，但应保持启用）
    verify(enabledPluginsService, org.mockito.Mockito.never()).remove(eq(1L), eq("myplugin"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#disableAndUnload_*`
Expected: FAIL — `disableAndUnload` not yet rewritten.

- [ ] **Step 3: Replace disableAndUnload body**

```java
/**
 * 通过 RCON 卸载并禁用插件（带失败回滚）。
 *
 * <p>对齐 l4d2-server-next DisableAndUnloadPlugin：
 * <ol>
 *   <li>列出所有 .smx 文件</li>
 *   <li>逆序 RCON unload，记录已成功的</li>
 *   <li>失败时：reload 已成功的，保持启用状态</li>
 *   <li>成功后调用 disable() 移除文件</li>
 * </ol>
 */
public void disableAndUnload(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);

    // 必须已启用
    List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
    if (enabled.stream().noneMatch(p -> pluginName.equals(p.getName()))) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件未启用: " + pluginName);
    }

    // 列出 .smx
    String pluginL4d2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
    List<FileInfo> allFiles;
    try {
        allFiles = instanceFileService.walkFiles(instanceId, pluginL4d2);
    } catch (Exception e) {
        log.warn("列出 .smx 失败，直接禁用: instanceId={}, pluginName={}, err={}",
                instanceId, pluginName, e.getMessage());
        disable(instanceId, pluginName);
        return;
    }
    List<String> smxPluginIds = allFiles.stream()
            .filter(f -> !f.isDirectory())
            .map(FileInfo::getPath)
            .filter(p -> p != null && p.toLowerCase().endsWith(SMX_SUFFIX))
            .map(p -> {
                String path = p.startsWith("./") ? p.substring(2) : p;
                int idx = path.toLowerCase().indexOf("addons/sourcemod/plugins/");
                String rel = idx >= 0 ? path.substring(idx + "addons/sourcemod/plugins/".length()) : path;
                return rel.substring(0, rel.length() - SMX_SUFFIX.length());
            })
            .sorted()
            .toList();

    if (smxPluginIds.isEmpty()) {
        log.warn("插件无 .smx 文件，直接禁用: instanceId={}, pluginName={}", instanceId, pluginName);
        disable(instanceId, pluginName);
        return;
    }

    // 逆序 unload
    List<String> unloadedIds = new java.util.ArrayList<>();
    for (int i = smxPluginIds.size() - 1; i >= 0; i--) {
        String pluginId = smxPluginIds.get(i);
        try {
            String cmd = "sm plugins unload " + quoteSourceModArg(pluginId);
            String output = rconService.executeCommand(instanceId, cmd);
            if (RconFailureDetector.isFailed(output)) {
                // 回滚：reload 已 unload 的（正序）
                rollbackUnloadedSmx(instanceId, unloadedIds);
                throw new L4D2PluginException(L4D2PluginException.RCON,
                        "RCON 卸载失败: " + pluginId + ", 输出: " + output);
            }
            unloadedIds.add(pluginId);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            rollbackUnloadedSmx(instanceId, unloadedIds);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 卸载异常: " + pluginId + ", " + e.getMessage(), e);
        }
    }

    // 全部 unload 成功，禁用文件
    disable(instanceId, pluginName);
    log.info("插件已卸载并禁用: instanceId={}, pluginName={}", instanceId, pluginName);
}

private void rollbackUnloadedSmx(Long instanceId, List<String> unloadedIds) {
    // 正序 reload
    for (String pluginId : unloadedIds) {
        try {
            rconService.executeCommand(instanceId, "sm plugins load " + quoteSourceModArg(pluginId));
        } catch (Exception e) {
            log.warn("回滚 reload 失败 pluginId={}, err={}", pluginId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#disableAndUnload_*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "feat(l4d2): disableAndUnload with RCON failure rollback (reload unloaded smx)"
```

### Task 4.3: Rewrite disableAllPlugins + enablePlatformPlugins

**Goal:** Replace old "move all .smx to disabled" logic. `disableAllPlugins` now iterates yaml + calls `disable()` for each. `enablePlatformPlugins` is replaced by `enable()` calls (no RCON) since preset does file-only.

- [ ] **Step 1: Replace disableAllPlugins**

```java
/**
 * 禁用所有已启用插件（供 PresetService 调用）。
 *
 * <p>对齐 l4d2-server-next ApplyPreset 第 2 步：获取当前所有启用插件，逐个 disable。
 */
public void disableAllPlugins(Long instanceId) {
    requireInstance(instanceId);
    List<EnabledPlugin> enabled = new java.util.ArrayList<>(enabledPluginsService.loadYaml(instanceId));
    int count = 0;
    for (EnabledPlugin ep : enabled) {
        try {
            disable(instanceId, ep.getName());
            count++;
        } catch (Exception e) {
            log.warn("禁用插件失败 instanceId={}, pluginName={}, err={}",
                    instanceId, ep.getName(), e.getMessage());
        }
    }
    log.info("已禁用所有插件: instanceId={}, count={}/{}", instanceId, count, enabled.size());
}

/**
 * 启用预设中指定的插件（不调 RCON，仅文件复制）。
 *
 * <p>对齐 l4d2-server-next ApplyPreset 第 3 步：逐个 EnablePlugin。
 */
public void enablePlugins(Long instanceId, List<String> pluginNames) {
    if (pluginNames == null) return;
    for (String name : pluginNames) {
        try {
            enable(instanceId, name);
        } catch (Exception e) {
            log.warn("启用插件失败 instanceId={}, pluginName={}, err={}",
                    instanceId, name, e.getMessage());
        }
    }
}

/** 旧方法保留兼容期 */
@Deprecated
public void enablePlatformPlugins(Long instanceId, String platform) {
    // 不再支持：预设的 platform 字段已废弃
    log.warn("enablePlatformPlugins 已废弃，请使用 enablePlugins");
}
```

- [ ] **Step 2: Update callers (PresetService)**

Will be done in Phase 5.

- [ ] **Step 3: Verify build**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git commit -m "refactor(l4d2): disableAllPlugins/enablePlugins use new enable/disable (no RCON)"
```

### Task 4.4: Update listEnabledPlugins

- [ ] **Step 1: Replace listEnabledPlugins**

```java
/**
 * 列出已启用插件名（从 yaml 读取）。
 */
public List<String> listEnabledPlugins(Long instanceId) {
    return enabledPluginsService.loadYaml(instanceId).stream()
            .map(EnabledPlugin::getName)
            .toList();
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git commit -m "refactor(l4d2): listEnabledPlugins reads from .enabled_plugins.yaml"
```

---

## Phase 5: Presets

**Goal:** Replace current preset structure with the reference's `platform` map + `preset[].plugins[].configs` structure. PresetService.apply does file-only operations (no RCON), matching the reference's behavior of manipulating files only — server picks up changes on next restart.

**Reference:** `logic/preset.go` + `preset.yaml`.

### Task 5.1: Create PresetPlugin and PresetPluginConfig VOs

- [ ] **Step 1: Create PresetPlugin.java**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设中的插件条目（对齐 l4d2-server-next PresetPlugin）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PresetPlugin implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 该插件需应用的 cfg 覆盖（可为空） */
    private List<PresetPluginConfig> configs = new ArrayList<>();
}
```

- [ ] **Step 2: Create PresetPluginConfig.java**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预设中单个插件的 cfg 覆盖（对齐 l4d2-server-next PresetPluginConfig）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PresetPluginConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** cfg 文件名（如 l4d2_tank_health.cfg） */
    private String name;

    /** CVAR 键值对 */
    private Map<String, String> values = new LinkedHashMap<>();
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java
git commit -m "feat(l4d2): add PresetPlugin and PresetPluginConfig VOs"
```

### Task 5.2: Rewrite PresetDetailVO and PresetConfig

- [ ] **Step 1: Replace PresetDetailVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * L4D2 预设详情 VO（对齐 l4d2-server-next Preset）。
 *
 * <p>移除了旧版的 gameMode/maxPlayers/disabledPlugins/configOverrides 字段，
 * 采用更简单的 name + desc + plugins[] 结构。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Data
public class PresetDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String desc;
    private List<PresetPlugin> plugins = new ArrayList<>();

    /** 摘要信息：插件数量（仅用于列表展示，详情接口不返回） */
    public int getPluginCount() {
        return plugins != null ? plugins.size() : 0;
    }
}
```

- [ ] **Step 2: Replace PresetConfig**

```java
package com.gameplatform.plugin.l4d2.config;

import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * preset.yaml 根配置类（对齐 l4d2-server-next PresetConfig）。
 *
 * <p>结构：
 * <pre>
 * platform:
 *   linux: 平台插件名
 *   windows: 平台插件名
 * preset:
 *   - id: multi-versus
 *     name: 多特战役
 *     desc: ...
 *     plugins:
 *       - name: 必选-功能类插件
 *         configs:
 *           - name: foo.cfg
 *             values:
 *               key: "value"
 * </pre>
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Data
public class PresetConfig {

    /** 平台插件映射：linux/windows → 平台插件名（启用预设时最先启用） */
    private Map<String, String> platform = new LinkedHashMap<>();

    /** 预设列表 */
    private List<PresetDetailVO> preset;
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java
git commit -m "refactor(l4d2): rewrite PresetDetailVO/PresetConfig to match l4d2-server-next"
```

### Task 5.3: Replace preset.yaml

- [ ] **Step 1: Replace preset.yaml content**

Replace `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml` with the structure from `D:\program\open_source\l4d2-server-next-master\backend\preset.yaml` (full content):

```yaml
platform:
  linux: 1.11插件平台linux版(6968-1155)(必须先启用这个)
  windows: 1.11插件平台windows版(6968-1155)(必须先启用这个)
preset:
  - id: multi-versus
    name: 多特战役
    desc: 标准的多人多特玩法，neko多特以及一些优化体验插件
    plugins:
      - name: 必选-功能类插件(left4dhooks)(v1.155)(SilverShot)
      - name: 必选-功能类插件(原生键值函数库)(v0.4)(fdxx)
      - name: 必选-功能类插件(WeaponHandling)(v1.0.6)(Lux)
      - name: 必选-修复类(v2.8b)(解决linux系统CFG不加载)(SilverShot, Peace-Maker)
      - name: 自选-8角色共存(v1.9.10)(DeatChaos25, Mi123456 & Merudo, Lux, SilverShot)
        configs:
          - name: survivor_chat_select.cfg
            values:
              l4d_csm_admin_flags: ""
      - name: 自选-多人插件superversus1.8.15.5改(v1.11.8)
        configs:
          - name: bots.cfg
            values:
              bots_give_slot0: "2079"
              bots_give_slot1: "10960"
              bots_give_type: "2"
  - id: fun-versus
    name: 娱乐多特战役
    desc: 娱乐多特，比标准多特增加了杀特回血与自动复活，适合娱乐
    plugins:
      - name: 必选-功能类插件(left4dhooks)(v1.155)(SilverShot)
      - name: 必选-功能类插件(原生键值函数库)(v0.4)(fdxx)
      - name: 自选-幸存者自动复活(v1.3.3)(sorallll)
        configs:
          - name: survivor_auto_respawn.cfg
            values:
              sar_give_type: "2"
              sar_respawn_survivor_limit: "5"
```

(Note: This is a trimmed version. The full preset.yaml from the reference has 100+ plugin entries; copy them all when implementing.)

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml
git commit -m "feat(l4d2): replace preset.yaml with l4d2-server-next structure"
```

### Task 5.4: Rewrite PresetService (no RCON)

- [ ] **Step 1: Write the failing test**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.PresetConfig;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.l4d2.vo.PresetPlugin;
import com.gameplatform.plugin.l4d2.vo.PresetPluginConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PresetServiceTest {

    private PluginInstallService pluginInstallService;
    private SourceModCfgService cfgService;
    private PresetService service;

    @BeforeEach
    void setup() {
        pluginInstallService = mock(PluginInstallService.class);
        cfgService = mock(SourceModCfgService.class);
        service = new PresetService(pluginInstallService, cfgService);
    }

    @Test
    void apply_disablesAllThenEnablesPlatformThenPluginsThenAppliesConfigs() {
        PresetDetailVO preset = new PresetDetailVO();
        preset.setId("test");
        preset.setName("Test");
        preset.setDesc("Test preset");
        PresetPlugin p1 = new PresetPlugin();
        p1.setName("plugin1");
        PresetPluginConfig cfg = new PresetPluginConfig();
        cfg.setName("plugin1.cfg");
        cfg.setValues(Map.of("key1", "value1"));
        p1.setConfigs(List.of(cfg));
        preset.setPlugins(List.of(p1));

        // 注入预设
        service.setPresets(List.of(preset));

        service.apply(1L, "test");

        // 1. 禁用所有
        verify(pluginInstallService).disableAllPlugins(1L);
        // 2. 启用插件（无 platform 配置时不调）
        verify(pluginInstallService).enablePlugins(eq(1L), eq(List.of("plugin1")));
        // 3. 应用 cfg 覆盖（不调 RCON）
        verify(cfgService).updateOrCreateConfig(eq(1L), eq("plugin1.cfg"), eq(Map.of("key1", "value1")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest`
Expected: FAIL — `PresetService` still has old apply logic.

- [ ] **Step 3: Replace PresetService**

```java
package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.plugin.l4d2.config.PresetConfig;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.l4d2.vo.PresetPlugin;
import com.gameplatform.plugin.l4d2.vo.PresetPluginConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * L4D2 预设服务（对齐 l4d2-server-next ApplyPreset，不调 RCON）。
 *
 * <p>应用流程：
 * <ol>
 *   <li>禁用所有已启用插件</li>
 *   <li>启用平台插件（从 platform.linux/windows 选取，运行时确定）</li>
 *   <li>启用预设中其他插件</li>
 *   <li>应用 cfg 覆盖（写文件，不调 RCON）</li>
 * </ol>
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Slf4j
@Service
public class PresetService {

    private final PluginInstallService pluginInstallService;
    private final SourceModCfgService cfgService;
    private List<PresetDetailVO> presets;

    public PresetService(PluginInstallService pluginInstallService, SourceModCfgService cfgService) {
        this.pluginInstallService = pluginInstallService;
        this.cfgService = cfgService;
    }

    @PostConstruct
    public void loadPresetYaml() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("preset.yaml")) {
            if (is == null) {
                log.error("preset.yaml not found in classpath");
                presets = Collections.emptyList();
                return;
            }
            PresetConfig config = mapper.readValue(is, PresetConfig.class);
            presets = config.getPreset() != null ? config.getPreset() : Collections.emptyList();
            log.info("Loaded {} presets from preset.yaml", presets.size());
        } catch (Exception e) {
            log.error("Failed to load preset.yaml", e);
            presets = Collections.emptyList();
        }
    }

    /** 测试用：注入预设 */
    void setPresets(List<PresetDetailVO> presets) {
        this.presets = presets;
    }

    public List<PresetDetailVO> list() {
        return presets;
    }

    public PresetDetailVO detail(String presetId) {
        return presets.stream()
                .filter(p -> presetId.equals(p.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 应用预设（不调 RCON，仅文件操作 + cfg 写入）。
     */
    public void apply(Long instanceId, String presetId) {
        PresetDetailVO preset = detail(presetId);
        if (preset == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "预设不存在: " + presetId);
        }
        log.info("Applying preset {} to instance {}", presetId, instanceId);

        // 1. 禁用所有
        pluginInstallService.disableAllPlugins(instanceId);

        // 2. 启用平台插件（如果有）
        String platformPlugin = resolvePlatformPlugin();
        if (platformPlugin != null) {
            try {
                pluginInstallService.enable(instanceId, platformPlugin);
            } catch (Exception e) {
                log.error("启用平台插件失败: {}", platformPlugin, e);
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "启用平台插件失败: " + platformPlugin + ", " + e.getMessage(), e);
            }
        }

        // 3. 启用预设插件（不含平台插件）
        List<String> pluginNames = preset.getPlugins().stream()
                .map(PresetPlugin::getName)
                .filter(n -> !n.equals(platformPlugin))
                .toList();
        pluginInstallService.enablePlugins(instanceId, pluginNames);

        // 4. 应用 cfg 覆盖
        if (preset.getPlugins() != null) {
            for (PresetPlugin plugin : preset.getPlugins()) {
                if (plugin.getConfigs() == null) continue;
                for (PresetPluginConfig cfg : plugin.getConfigs()) {
                    try {
                        cfgService.updateOrCreateConfig(instanceId, cfg.getName(), cfg.getValues());
                    } catch (Exception e) {
                        log.warn("应用 cfg 覆盖失败 preset={}, cfg={}, err={}",
                                presetId, cfg.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 解析平台插件名：根据 JVM 运行平台选择 linux/windows 对应的插件。
     * 若平台不在 linux/windows 之列，返回 null（不启用平台插件）。
     */
    private String resolvePlatformPlugin() {
        // 加载配置获取 platform map
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("preset.yaml")) {
                if (is == null) return null;
                PresetConfig config = mapper.readValue(is, PresetConfig.class);
                if (config.getPlatform() == null) return null;
                String osName = System.getProperty("os.name").toLowerCase();
                String key = osName.contains("win") ? "windows" : "linux";
                return config.getPlatform().get(key);
            }
        } catch (Exception e) {
            log.warn("解析平台插件失败: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java
git commit -m "refactor(l4d2): rewrite PresetService to no-RCON model matching l4d2-server-next"
```

---

## Phase 6: Store

**Goal:** Enhance `PluginStoreService` and `GitHubApiClient` with: parameterized repo/proxy/token, LFS pointer parsing + size validation, atomic temp-dir commit, task dedup, retry, README endpoint.

**Reference:** `logic/plugin_store.go`.

### Task 6.1: Enhance GitHubApiClient with LFS parsing + treeCache

- [ ] **Step 1: Add LFS pointer parsing + tree cache**

Add to `GitHubApiClient.java`:

```java
private static final long TREE_CACHE_TTL_MS = 10 * 60 * 1000;
private final Map<String, CachedTree> treeCache = new java.util.concurrent.ConcurrentHashMap<>();

private record CachedTree(List<TreeEntry> tree, long fetchedAt) {}

/**
 * LFS 指针记录（对齐 l4d2-server-next gitLFSPointer）。
 */
public record LfsPointer(String oid, long size) {}

/**
 * 解析 LFS 指针文件内容。
 *
 * <p>格式：
 * <pre>
 * version https://git-lfs.github.com/spec/v1
 * oid sha256:abc123...
 * size 12345
 * </pre>
 *
 * @param content 文件内容
 * @return LfsPointer 或 null（不是 LFS 指针时）
 */
public LfsPointer parseLfsPointer(String content) {
    if (content == null || !content.startsWith(LFS_POINTER_PREFIX)) {
        return null;
    }
    String oid = null;
    long size = -1;
    for (String line : content.split("\n", -1)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("oid sha256:")) {
            oid = trimmed.substring("oid sha256:".length()).trim();
        } else if (trimmed.startsWith("size ")) {
            try {
                size = Long.parseLong(trimmed.substring("size ".length()).trim());
            } catch (NumberFormatException ignored) {}
        }
    }
    if (oid == null || size < 0) return null;
    return new LfsPointer(oid, size);
}

/**
 * 获取仓库目录树（带缓存，10 分钟 TTL）。
 */
public List<TreeEntry> getTreeCached() {
    L4D2Config.PluginStore ps = config.getPluginStore();
    String key = ps.getRepo() + "/" + ps.getBranch();
    CachedTree cached = treeCache.get(key);
    if (cached != null && (System.currentTimeMillis() - cached.fetchedAt()) < TREE_CACHE_TTL_MS) {
        return cached.tree();
    }
    List<TreeEntry> fresh = getTree();
    treeCache.put(key, new CachedTree(fresh, System.currentTimeMillis()));
    return fresh;
}

/**
 * 强制刷新目录树缓存。
 */
public void invalidateCache() {
    treeCache.clear();
}
```

- [ ] **Step 2: Add downloadLfsObjectWithSizeCheck method**

```java
/**
 * 下载 LFS 对象并校验文件大小。
 *
 * @param oid    LFS OID
 * @param expectedSize 期望大小（字节）
 * @param target 目标文件
 */
public void downloadLfsObjectWithSizeCheck(String oid, long expectedSize, File target) {
    if (oid == null || oid.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "LFS OID 不能为空");
    }
    Map<String, String> urls = batchLfsObjects(List.of(oid));
    String url = urls.get(oid);
    if (url == null) {
        throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                "LFS 对象不存在或无可下载链接: " + oid);
    }
    File downloaded = httpClient.download(url, target.getName(), null, null, null);
    if (downloaded == null) {
        throw new L4D2PluginException(L4D2PluginException.NETWORK, "LFS 下载失败: " + oid);
    }
    if (!downloaded.getAbsolutePath().equals(target.getAbsolutePath())) {
        try {
            Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "移动下载文件失败: " + e.getMessage(), e);
        }
    }
    // 校验大小
    long actualSize = target.length();
    if (actualSize != expectedSize) {
        target.delete();
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "LFS 文件大小不匹配，期望 " + expectedSize + "，实际 " + actualSize);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java
git commit -m "feat(l4d2): GitHubApiClient adds LFS pointer parsing + tree cache + size check"
```

### Task 6.2: Enhance PluginStoreService with atomic commit + dedup + retry

- [ ] **Step 1: Add task dedup + atomic commit + LFS size check**

Modify `PluginStoreService.runDownload` to:

```java
private void runDownload(PluginStoreDownloadTaskVO task, PluginStoreDownloadDTO dto) {
    try {
        if (isCancelled(task)) return;
        downloadSemaphore.acquire();
        try {
            // 1. dedup check：检查是否已有相同 pluginId 的活动任务
            if (hasActiveTaskForPlugin(dto.getPluginId(), task.getTaskId())) {
                task.setStatus(STATUS_FAILED);
                task.setError("插件 " + dto.getPluginId() + " 正在下载");
                task.setFinishedAt(LocalDateTime.now());
                return;
            }
            if (isCancelled(task)) return;
            task.setStatus(STATUS_DOWNLOADING);

            // 2. 获取 plugin.zip 的 LFS 指针（通过 GitHub blob API）
            String zipPath = dto.getPluginId() + "/" + PLUGIN_ZIP_FILE;
            TreeEntry zipEntry = gitHubApiClient.getTreeCached().stream()
                    .filter(e -> zipPath.equals(e.path()))
                    .findFirst()
                    .orElse(null);
            if (zipEntry == null) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "插件不存在 " + PLUGIN_ZIP_FILE + ": " + dto.getPluginId());
            }
            task.setTotalBytes(zipEntry.size());

            String blob = gitHubApiClient.getBlobContent(zipEntry.sha());
            if (blob == null) {
                throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                        "无法获取 plugin.zip 内容: " + dto.getPluginId());
            }
            GitHubApiClient.LfsPointer pointer = gitHubApiClient.parseLfsPointer(blob);
            if (pointer == null) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "plugin.zip 不是 LFS 指针（暂不支持非 LFS 下载）");
            }

            if (isCancelled(task)) return;

            // 3. 下载 LFS 对象到临时文件（含大小校验 + 重试 3 次，每次间隔 1s）
            File tempFile = downloadWithRetry(pointer, task, dto);
            if (isCancelled(task)) {
                if (tempFile != null) tempFile.delete();
                return;
            }

            // 4. 安装到 plugins_store（通过 installFromLocalFile）
            task.setStatus(STATUS_INSTALLING);
            pluginInstallService.installFromLocalFile(dto.getInstanceId(), tempFile, dto.getPluginId() + ".zip");
            pluginSourceService.writeSource(dto.getInstanceId(), dto.getPluginId(), "store", List.of());

            if (tempFile != null) tempFile.delete();
            task.setStatus(STATUS_COMPLETED);
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            log.info("插件下载完成: taskId={}, pluginId={}", task.getTaskId(), dto.getPluginId());
        } finally {
            downloadSemaphore.release();
        }
    } catch (Exception e) {
        log.error("插件下载失败: taskId={}, pluginId={}", task.getTaskId(), dto.getPluginId(), e);
        if (!isCancelled(task)) {
            task.setStatus(STATUS_FAILED);
            task.setError(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
        }
    }
}

private boolean hasActiveTaskForPlugin(String pluginId, String excludeTaskId) {
    return tasks.values().stream().anyMatch(t ->
            !t.getTaskId().equals(excludeTaskId)
                    && pluginId.equals(t.getPluginId())
                    && !isTerminal(t));
}

private File downloadWithRetry(GitHubApiClient.LfsPointer pointer,
                               PluginStoreDownloadTaskVO task,
                               PluginStoreDownloadDTO dto) {
    int maxRetries = 3;
    Exception lastErr = null;
    for (int i = 0; i < maxRetries; i++) {
        if (isCancelled(task)) return null;
        try {
            File tempFile = File.createTempFile("l4d2-store-", "-" + dto.getPluginId() + ".zip");
            gitHubApiClient.downloadLfsObjectWithSizeCheck(pointer.oid(), pointer.size(), tempFile);
            return tempFile;
        } catch (Exception e) {
            lastErr = e;
            log.warn("下载 LFS 对象失败 retry={}/{}, oid={}, err={}",
                    i + 1, maxRetries, pointer.oid(), e.getMessage());
            if (i < maxRetries - 1) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    throw new L4D2PluginException(L4D2PluginException.NETWORK,
            "LFS 下载失败（重试 " + maxRetries + " 次）: " + lastErr.getMessage(), lastErr);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "feat(l4d2): PluginStoreService adds dedup + LFS size check + 3x retry"
```

### Task 6.3: Add README endpoint

- [ ] **Step 1: Verify readme() method exists in PluginStoreService (already there)**

Run: `cd backend && grep -n "public String readme" plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`

Expected: exists.

- [ ] **Step 2: Add README endpoint to PluginStoreController**

Will be done in Phase 8 (Controllers).

### Task 6.4: Create PluginStoreMigration (cleanup .download_temp on startup)

**Reference:** `logic/plugin_store.go` `CleanDownloadTemp` (lines 321-326).

- [ ] **Step 1: Create PluginStoreMigration.java**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件商店启动时清理临时目录。
 *
 * <p>对齐 l4d2-server-next CleanDownloadTemp：启动时整体清空 .download_temp/，
 * 删除上次运行残留。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStoreMigration {

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanDownloadTempOnStartup() {
        try {
            List<InstanceVO> instances = instanceQueryService.listAllInstances();
            if (instances == null) return;
            for (InstanceVO instance : instances) {
                Long instanceId = instance.getId();
                try {
                    String tempPath = pathResolver.getPluginsStorePath() + "/.download_temp";
                    instanceFileService.deleteFile(instanceId, tempPath);
                    log.info("已清理插件商店临时目录: instanceId={}", instanceId);
                } catch (Exception e) {
                    log.debug("清理临时目录跳过 instanceId={}, err={}", instanceId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("启动清理插件商店临时目录失败: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Verify InstanceQueryService has listAllInstances**

If missing, add `List<InstanceVO> listAllInstances()` to SPI and implement in core.

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java
git commit -m "feat(l4d2): PluginStoreMigration cleans .download_temp on startup"
```

---

## Phase 7: Config Editor

**Goal:** Enhance `SourceModCfgParser` with `restoreFormat` (full comment output), console command blacklist, file header filter. Enhance `SourceModCfgService` with `applyTempConfig` (RCON), `restoreDefaults`, `updateOrCreateConfig`, l4d2↔l4d alias candidates.

**Reference:** `logic/config_parser.go` + `logic/plugin_config.go`.

### Task 7.1: Enhance SourceModCfgParser

- [ ] **Step 1: Write the failing test**

```java
package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceModCfgParserTest {

    private final SourceModCfgParser parser = new SourceModCfgParser();

    @Test
    void parse_skipsConsoleCommands() {
        String content = """
                // SourceMod config
                exec sourcemod.cfg
                sm plugins reload
                "l4d2_health" "100"
                """;
        List<ConfigItem> items = parser.parse(content);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getKey()).isEqualTo("l4d2_health");
    }

    @Test
    void parse_skipsFileHeaders() {
        String content = """
                // This file was auto-generated by SourceMod
                // ConVars for plugin "foo.smx"
                "foo_value" "1"
                """;
        List<ConfigItem> items = parser.parse(content);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getKey()).isEqualTo("foo_value");
        assertThat(items.get(0).getDescription()).doesNotContain("auto-generated", "ConVars");
    }

    @Test
    void restoreFormat_writesFullCommentsAndValue() {
        ConfigItem item = new ConfigItem();
        item.setKey("l4d2_health");
        item.setValue("150");
        item.setDefaultValue("100");
        item.setMin(1.0);
        item.setMax(1000.0);
        item.setDescription("Player health");
        String output = parser.restoreFormat(List.of(item));
        assertThat(output).contains("// Player health");
        assertThat(output).contains("// -");
        assertThat(output).contains("// Default: \"100\"");
        assertThat(output).contains("// Minimum: \"1.0\"");
        assertThat(output).contains("// Maximum: \"1000.0\"");
        assertThat(output).contains("\"l4d2_health\" \"150\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest`
Expected: FAIL — console blacklist and restoreFormat not implemented.

- [ ] **Step 3: Rewrite SourceModCfgParser**

```java
package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SourceMod cfg 文件解析器（对齐 l4d2-server-next config_parser.go）。
 *
 * <p>新增特性：
 * <ul>
 *   <li>控制台命令黑名单（sm/exec/meta/rcon）：不作为 cvar 解析</li>
 *   <li>文件头过滤：跳过 "This file was auto-generated" / "ConVars for plugin" 等</li>
 *   <li>restoreFormat：从 ConfigItem 列表生成完整带注释的 cfg 文件</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Component
public class SourceModCfgParser {

    /** 兼容多种格式的 KV 正则：支持 "key" "value" / key "value" / key value */
    private static final Pattern KV_PATTERN =
            Pattern.compile("^\"?([a-zA-Z0-9_]+)\"?\\s+\"?([^\"]*)\"?\\s*(?://\\s*(.*))?");

    private static final Pattern DEFAULT_PATTERN = Pattern.compile("(?i)Default:\\s*\"?([^\"]*)\"?");
    private static final Pattern MIN_PATTERN = Pattern.compile("(?i)Min(?:imum)?:\\s*\"?([^\"]*)\"?");
    private static final Pattern MAX_PATTERN = Pattern.compile("(?i)Max(?:imum)?:\\s*\"?([^\"]*)\"?");

    /** 控制台命令黑名单（不作为 cvar 名） */
    private static final Set<String> CONSOLE_CMDS = Set.of("sm", "exec", "meta", "rcon");

    /** 文件头标记（描述中跳过） */
    private static final List<String> HEADER_MARKERS = List.of(
            "this file was auto-generated",
            "convars for plugin"
    );

    public List<ConfigItem> parse(String content) {
        List<ConfigItem> items = new ArrayList<>();
        if (content == null) return items;
        String[] lines = content.split("\n");
        List<String> commentBuffer = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                commentBuffer.clear();
                continue;
            }
            if (line.startsWith("//")) {
                commentBuffer.add(line);
                continue;
            }
            Matcher m = KV_PATTERN.matcher(line);
            if (!m.matches()) {
                commentBuffer.clear();
                continue;
            }
            String name = m.group(1);
            String value = m.group(2);
            // 跳过控制台命令
            if (CONSOLE_CMDS.contains(name.toLowerCase())) {
                commentBuffer.clear();
                continue;
            }
            ConfigItem item = new ConfigItem();
            item.setKey(name);
            item.setValue(value);
            item.setLineNumber(i + 1);
            // 从注释解析元数据
            List<String> descLines = new ArrayList<>();
            for (String comment : commentBuffer) {
                String clean = comment.replaceAll("^//\\s*", "").trim();
                Matcher dm = DEFAULT_PATTERN.matcher(clean);
                if (dm.find()) {
                    item.setDefaultValue(dm.group(1));
                    continue;
                }
                Matcher mn = MIN_PATTERN.matcher(clean);
                if (mn.find()) {
                    try { item.setMin(Double.parseDouble(dm.group(1))); }
                    catch (NumberFormatException ignored) {}
                    continue;
                }
                Matcher mx = MAX_PATTERN.matcher(clean);
                if (mx.find()) {
                    try { item.setMax(Double.parseDouble(mx.group(1))); }
                    catch (NumberFormatException ignored) {}
                    continue;
                }
                if (clean.equals("-")) continue;
                // 跳过文件头
                String lowerClean = clean.toLowerCase();
                if (HEADER_MARKERS.stream().anyMatch(lowerClean::contains)) continue;
                descLines.add(clean);
            }
            if (!descLines.isEmpty()) {
                item.setDescription(String.join("\n", descLines));
            }
            items.add(item);
            commentBuffer.clear();
        }
        return items;
    }

    public String serialize(List<ConfigItem> items, String originalContent) {
        if (originalContent == null) originalContent = "";
        String[] lines = originalContent.split("\n", -1);
        for (ConfigItem item : items) {
            int idx = item.getLineNumber() - 1;
            if (idx < 0 || idx >= lines.length) continue;
            String line = lines[idx];
            Matcher m = KV_PATTERN.matcher(line.trim());
            if (m.matches() && !CONSOLE_CMDS.contains(m.group(1).toLowerCase())) {
                String prefix = line.substring(0, line.indexOf('"') < 0 ? 0 : line.indexOf('"'));
                String comment = m.group(3) != null ? " // " + m.group(3) : "";
                lines[idx] = prefix + "\"" + item.getKey() + "\" \"" + item.getValue() + "\"" + comment;
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 从 ConfigItem 列表生成完整的带注释 cfg 文件（对齐 l4d2-server-next RestoreSourceModConfig）。
     *
     * <p>格式：
     * <pre>
     * // Description line 1
     * // Description line 2
     * // -
     * // Default: "100"
     * // Minimum: "1"
     * // Maximum: "1000"
     * "key" "value"
     * </pre>
     */
    public String restoreFormat(List<ConfigItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            ConfigItem item = items.get(i);
            if (i > 0) sb.append("\n\n");
            // Description
            if (item.getDescription() != null && !item.getDescription().isBlank()) {
                for (String line : item.getDescription().split("\n")) {
                    sb.append("// ").append(line).append("\n");
                }
            }
            // Separator
            sb.append("// -\n");
            // Default
            if (item.getDefaultValue() != null && !item.getDefaultValue().isBlank()) {
                sb.append("// Default: \"").append(item.getDefaultValue()).append("\"\n");
            }
            // Min
            if (item.getMin() != null) {
                sb.append("// Minimum: \"").append(formatNumber(item.getMin())).append("\"\n");
            }
            // Max
            if (item.getMax() != null) {
                sb.append("// Maximum: \"").append(formatNumber(item.getMax())).append("\"\n");
            }
            // Value
            sb.append("\"").append(item.getKey()).append("\" \"").append(item.getValue()).append("\"\n");
        }
        return sb.toString();
    }

    private String formatNumber(Double d) {
        if (d == null) return "";
        if (d == d.longValue()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(d);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): SourceModCfgParser adds console blacklist + header filter + restoreFormat"
```

### Task 7.2: Add l4d2↔l4d alias candidate paths

- [ ] **Step 1: Modify SourceModCfgService.getCandidatePaths**

Replace existing method:

```java
/**
 * 候选 cfg 路径推导（对齐 l4d2-server-next getPluginConfigCandidates）。
 *
 * <p>规则：
 * <ul>
 *   <li>扫描 plugins_store/{name}/left4dead2/addons/sourcemod/plugins/ 下的 .smx 文件</li>
 *   <li>对每个 .smx，生成 {baseName}.cfg 候选</li>
 *   <li>若 baseName 以 l4d2_ 开头，额外生成 l4d_{rest}.cfg 候选（反之亦然）</li>
 *   <li>同时扫描 plugins_store/{name}/left4dead2/cfg/sourcemod/ 下实际存在的 .cfg</li>
 * </ul>
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 * @return 候选 cfg 文件名集合（不含路径前缀）
 */
public Set<String> getCandidateConfigNames(Long instanceId, String pluginName) {
    Set<String> candidates = new java.util.LinkedHashSet<>();
    String pluginSmxDir = pathResolver.getPluginLeft4Dead2Path(pluginName)
            + "/addons/sourcemod/plugins";
    // 扫描 .smx
    try {
        if (existsSafe(instanceId, pluginSmxDir)) {
            List<FileInfo> files = instanceFileService.listFiles(instanceId, pluginSmxDir);
            if (files != null) {
                for (FileInfo f : files) {
                    if (f.isDirectory()) continue;
                    String fname = f.getName();
                    if (fname == null || !fname.toLowerCase().endsWith(".cfg")) {
                        // .smx 推导
                        if (fname != null && fname.toLowerCase().endsWith(".smx")) {
                            String base = fname.substring(0, fname.length() - 4);
                            candidates.add(base + ".cfg");
                            if (base.startsWith("l4d2_")) {
                                candidates.add("l4d_" + base.substring(5) + ".cfg");
                            } else if (base.startsWith("l4d_")) {
                                candidates.add("l4d2_" + base.substring(4) + ".cfg");
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        log.debug("扫描 smx 目录失败: {}", e.getMessage());
    }
    // 扫描 cfg/sourcemod/
    String pluginCfgDir = pathResolver.getPluginLeft4Dead2Path(pluginName)
            + "/cfg/sourcemod";
    try {
        if (existsSafe(instanceId, pluginCfgDir)) {
            List<FileInfo> files = instanceFileService.listFiles(instanceId, pluginCfgDir);
            if (files != null) {
                for (FileInfo f : files) {
                    if (!f.isDirectory() && f.getName() != null
                            && f.getName().toLowerCase().endsWith(".cfg")) {
                        candidates.add(f.getName());
                    }
                }
            }
        }
    } catch (Exception e) {
        log.debug("扫描 cfg 目录失败: {}", e.getMessage());
    }
    return candidates;
}
```

Keep the old `getCandidatePaths(String pluginName)` for backward compatibility but add l4d2↔l4d alias:

```java
public List<String> getCandidatePaths(String pluginName) {
    List<String> paths = new java.util.ArrayList<>();
    paths.add(CFG_SOURCEMOD_PREFIX + pluginName + ".cfg");
    paths.add(PLUGINS_PREFIX + pluginName + ".cfg");
    // l4d2 ↔ l4d 别名
    if (pluginName.startsWith("l4d2_")) {
        String alias = "l4d_" + pluginName.substring(5);
        paths.add(CFG_SOURCEMOD_PREFIX + alias + ".cfg");
        paths.add(PLUGINS_PREFIX + alias + ".cfg");
    } else if (pluginName.startsWith("l4d_")) {
        String alias = "l4d2_" + pluginName.substring(4);
        paths.add(CFG_SOURCEMOD_PREFIX + alias + ".cfg");
        paths.add(PLUGINS_PREFIX + alias + ".cfg");
    }
    return paths;
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git commit -m "feat(l4d2): SourceModCfgService adds l4d2<->l4d alias candidates + store-based scanning"
```

### Task 7.3: Add updateOrCreateConfig + restoreDefaults + applyTempConfig

- [ ] **Step 1: Add three methods to SourceModCfgService**

```java
/**
 * 更新或创建 cfg 文件（对齐 l4d2-server-next UpdateOrCreateSourceModConfig）。
 *
 * <p>若文件存在：仅更新已有 cvar 值，缺失的 cvar 追加到末尾。
 * 若文件不存在：调用 restoreFormat 生成完整带注释的 cfg。
 *
 * @param instanceId 实例 ID
 * @param cfgName    cfg 文件名（如 l4d2_tank_health.cfg）
 * @param updates    cvar 键值对
 */
public void updateOrCreateConfig(Long instanceId, String cfgName, java.util.Map<String, String> updates) {
    if (cfgName == null || cfgName.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cfg 文件名不能为空");
    }
    if (cfgName.contains("/") || cfgName.contains("\\")) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "无效的 cfg 文件名: " + cfgName);
    }
    String relPath = pathResolver.getSourceModCfgPath() + "/" + cfgName;
    if (existsSafe(instanceId, relPath)) {
        // 文件存在：读取 + 解析 + 更新值 + 写回
        try {
            String content = instanceFileService.readTextFile(instanceId, relPath, gbk);
            List<ConfigItem> items = cfgParser.parse(content);
            java.util.Set<String> updatedKeys = new java.util.HashSet<>();
            for (ConfigItem item : items) {
                String newVal = updates.get(item.getKey());
                if (newVal != null) {
                    item.setValue(newVal);
                    updatedKeys.add(item.getKey());
                }
            }
            // 追加缺失的 cvar
            for (java.util.Map.Entry<String, String> entry : updates.entrySet()) {
                if (!updatedKeys.contains(entry.getKey())) {
                    ConfigItem newItem = new ConfigItem();
                    newItem.setKey(entry.getKey());
                    newItem.setValue(entry.getValue());
                    items.add(newItem);
                }
            }
            String serialized = cfgParser.serialize(items, content);
            instanceFileService.writeTextFile(instanceId, relPath, serialized);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "更新 cfg 失败: " + e.getMessage(), e);
        }
    } else {
        // 文件不存在：用 restoreFormat 创建
        List<ConfigItem> items = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : updates.entrySet()) {
            ConfigItem item = new ConfigItem();
            item.setKey(entry.getKey());
            item.setValue(entry.getValue());
            items.add(item);
        }
        String content = cfgParser.restoreFormat(items);
        try {
            instanceFileService.writeTextFile(instanceId, relPath, content);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "创建 cfg 失败: " + e.getMessage(), e);
        }
    }
    log.info("cfg 已更新或创建: instanceId={}, cfgName={}", instanceId, cfgName);
}

/**
 * 恢复 cfg 到默认值（对齐 l4d2-server-next RestoreSourceModConfig）。
 *
 * <p>对每个 cvar，将 value 重置为 defaultValue（若有）。
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 */
public void restoreDefaults(Long instanceId, String pluginName) {
    PluginConfigResource resource = getConfig(instanceId, pluginName);
    if (resource == null || resource.getSpec() == null || resource.getSpec().getItems() == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "未找到插件配置: " + pluginName);
    }
    List<ConfigItem> items = resource.getSpec().getItems();
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().isBlank()) {
            item.setValue(item.getDefaultValue());
        }
    }
    updateConfig(instanceId, pluginName, items);
    log.info("cfg 已恢复默认值: instanceId={}, pluginName={}", instanceId, pluginName);
}

/**
 * 通过 RCON 临时应用配置（不写文件）。
 *
 * <p>对齐 l4d2-server-next 的 RCON 临时设置场景：游戏中立即生效，但重启后失效。
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 * @param items      要应用的配置项
 */
public void applyTempConfig(Long instanceId, String pluginName, List<ConfigItem> items) {
    requireInstance(instanceId);
    if (items == null || items.isEmpty()) return;
    RconService rconService = getRconService();
    if (rconService == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "RCON 服务不可用");
    }
    for (ConfigItem item : items) {
        try {
            String cmd = item.getKey() + " " + item.getValue();
            rconService.executeCommand(instanceId, cmd);
        } catch (Exception e) {
            log.warn("RCON 临时设置失败 cvar={}, err={}", item.getKey(), e.getMessage());
        }
    }
    log.info("临时配置已应用: instanceId={}, pluginName={}, count={}",
            instanceId, pluginName, items.size());
}

// RconService 注入（懒加载，避免循环依赖）
private RconService rconServiceInstance;

@Autowired
public void setRconService(RconService rconService) {
    this.rconServiceInstance = rconService;
}

private RconService getRconService() {
    return rconServiceInstance;
}
```

Add imports:
```java
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Set;
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git commit -m "feat(l4d2): SourceModCfgService adds updateOrCreateConfig + restoreDefaults + applyTempConfig"
```

---

## Phase 8: Controllers + Migration

### Task 8.1: Adjust PluginManageController

- [ ] **Step 1: Remove deprecated toggle endpoint, align enable-load/disable-unload**

Update `PluginManageController.java`:

```java
// 移除 toggle 端点（已废弃）
// enable-load 保持不变（已存在）
// disable-unload 保持不变（已存在）
// 新增：enable-only (仅文件，不调 RCON)
@Operation(summary = "仅启用插件（不调 RCON）", description = "仅复制文件到游戏目录，不通过 RCON 加载")
@PostMapping("/enable")
public Result<Void> enable(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件名称") @RequestParam String pluginName) {
    log.info("仅启用插件（无 RCON）, instanceId: {}, pluginName: {}", instanceId, pluginName);
    pluginInstallService.enable(instanceId, pluginName);
    return Result.success();
}

// 新增：disable-only
@Operation(summary = "仅禁用插件（不调 RCON）", description = "仅从游戏目录移除文件，不通过 RCON 卸载")
@PostMapping("/disable")
public Result<Void> disable(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件名称") @RequestParam String pluginName) {
    log.info("仅禁用插件（无 RCON）, instanceId: {}, pluginName: {}", instanceId, pluginName);
    pluginInstallService.disable(instanceId, pluginName);
    return Result.success();
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java
git commit -m "feat(l4d2): PluginManageController adds enable/disable (no RCON) endpoints"
```

### Task 8.2: Enhance PluginConfigController

- [ ] **Step 1: Add restore-defaults + apply-temp endpoints**

```java
@Operation(summary = "恢复默认配置", description = "将所有 cvar 重置为 Default 注释值")
@PostMapping("/restore-defaults")
public Result<Void> restoreDefaults(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件名称") @RequestParam String pluginName) {
    log.info("恢复默认配置, instanceId: {}, pluginName: {}", instanceId, pluginName);
    sourceModCfgService.restoreDefaults(instanceId, pluginName);
    return Result.success();
}

@Operation(summary = "临时应用配置（RCON）", description = "通过 RCON 立即应用配置，不写入文件")
@PostMapping("/apply-temp")
public Result<Void> applyTemp(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件名称") @RequestParam String pluginName,
        @RequestBody java.util.List<com.gameplatform.plugin.l4d2.vo.config.ConfigItem> items) {
    log.info("临时应用配置, instanceId: {}, pluginName: {}", instanceId, pluginName);
    sourceModCfgService.applyTempConfig(instanceId, pluginName, items);
    return Result.success();
}

@Operation(summary = "更新或创建 cfg 文件", description = "若 cfg 不存在则用 restoreFormat 创建")
@PostMapping("/update-or-create")
public Result<Void> updateOrCreate(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "cfg 文件名") @RequestParam String cfgName,
        @RequestBody java.util.Map<String, String> updates) {
    log.info("更新或创建 cfg: instanceId={}, cfgName={}", instanceId, cfgName);
    sourceModCfgService.updateOrCreateConfig(instanceId, cfgName, updates);
    return Result.success();
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java
git commit -m "feat(l4d2): PluginConfigController adds restore-defaults + apply-temp + update-or-create"
```

### Task 8.3: Enhance PluginStoreController

- [ ] **Step 1: Add README endpoint + force-refresh param**

```java
@Operation(summary = "获取插件 README", description = "返回插件 README.md 内容")
@GetMapping("/readme")
public Result<String> readme(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件ID") @RequestParam String pluginId) {
    return Result.success(pluginStoreService.readme(pluginId));
}

@Operation(summary = "强制刷新商店缓存", description = "清空目录树缓存，下次列表请求重新拉取")
@PostMapping("/refresh")
public Result<Void> refresh() {
    pluginStoreService.invalidateCache();
    return Result.success();
}
```

Add to PluginStoreService:

```java
public void invalidateCache() {
    cachedItems = null;
    cachedTimestamp = 0;
    gitHubApiClient.invalidateCache();
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "feat(l4d2): PluginStoreController adds readme + refresh-cache endpoints"
```

### Task 8.4: Adjust PresetController

- [ ] **Step 1: Update PresetController for new VO**

Check `PresetController.java` and update any references to removed fields (`gameMode`, `maxPlayers`, `disabledPlugins`, `configOverrides`). The list endpoint should now return `id + name + desc + pluginCount`.

```java
@Operation(summary = "获取预设列表")
@GetMapping("/list")
public Result<List<PresetSummaryVO>> list() {
    return Result.success(presetService.list().stream()
            .map(p -> new PresetSummaryVO(p.getId(), p.getName(), p.getDesc(), p.getPluginCount()))
            .toList());
}
```

Create `PresetSummaryVO` if not exists (or inline as record).

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PresetController.java
git commit -m "refactor(l4d2): PresetController aligns with new PresetDetailVO"
```

---

## Phase 9: Frontend Rewrite

**Goal:** Update Vue pages to use the new API contracts.

### Task 9.1: Update api/plugin.js

- [ ] **Step 1: Update API function signatures**

In `frontend/src/api/plugin.js`:

```javascript
// 新增 enable / disable（无 RCON）
export function enablePlugin(instanceId, pluginName) {
  return request({ url: '/api/plugin/l4d2/plugins/enable', method: 'post', params: { instanceId, pluginName } })
}
export function disablePlugin(instanceId, pluginName) {
  return request({ url: '/api/plugin/l4d2/plugins/disable', method: 'post', params: { instanceId, pluginName } })
}

// 配置：新增 restore-defaults + apply-temp + update-or-create
export function restoreDefaults(instanceId, pluginName) {
  return request({ url: '/api/plugin/l4d2/plugin-config/restore-defaults', method: 'post', params: { instanceId, pluginName } })
}
export function applyTempConfig(instanceId, pluginName, items) {
  return request({ url: '/api/plugin/l4d2/plugin-config/apply-temp', method: 'post', params: { instanceId, pluginName }, data: items })
}
export function updateOrCreateConfig(instanceId, cfgName, updates) {
  return request({ url: '/api/plugin/l4d2/plugin-config/update-or-create', method: 'post', params: { instanceId, cfgName }, data: updates })
}

// 商店：新增 readme + refresh
export function getStoreReadme(pluginId) {
  return request({ url: '/api/plugin/l4d2/plugin-store/readme', method: 'get', params: { pluginId } })
}
export function refreshStore() {
  return request({ url: '/api/plugin/l4d2/plugin-store/refresh', method: 'post' })
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/api/plugin.js
git commit -m "feat(l4d2-frontend): update api/plugin.js with new endpoints"
```

### Task 9.2: Update Plugins.vue

- [ ] **Step 1: Update table columns**

In `frontend/src/views/Plugins.vue`:

- Replace `enabled`/`disabled` boolean column with `status` (enabled/disabled) chip
- Add `source` column (panel/store/upload)
- Add `hasSmx` and `hasConfig` indicator columns
- Action buttons: `enable-load` (RCON) / `disable-unload` (RCON) / `enable` (no RCON) / `disable` (no RCON) / `delete`

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/Plugins.vue
git commit -m "feat(l4d2-frontend): Plugins.vue uses new status/source/hasSmx/hasConfig fields"
```

### Task 9.3: Update PluginConfig.vue

- [ ] **Step 1: Add restore-defaults + apply-temp buttons**

- "恢复默认值" button calls `restoreDefaults(instanceId, pluginName)`
- "临时应用" button calls `applyTempConfig(instanceId, pluginName, items)` (sends current edited values via RCON, no file write)
- "保存" button calls `update(instanceId, pluginName, items)` (existing, writes to file)

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/PluginConfig.vue
git commit -m "feat(l4d2-frontend): PluginConfig.vue adds restore-defaults + apply-temp buttons"
```

### Task 9.4: Update Preset.vue

- [ ] **Step 1: Replace old fields with new structure**

- Remove `gameMode`, `maxPlayers`, `disabledPlugins`, `configOverrides` displays
- Show `name`, `desc`, `pluginCount` in list
- Detail view shows `plugins[]` with collapsible `configs[]` per plugin

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/Preset.vue
git commit -m "feat(l4d2-frontend): Preset.vue uses new structure (plugins[].configs)"
```

### Task 9.5: Update PluginStore.vue

- [ ] **Step 1: Add README modal + refresh button**

- "刷新缓存" button calls `refreshStore()`
- Click plugin row opens detail modal with README content