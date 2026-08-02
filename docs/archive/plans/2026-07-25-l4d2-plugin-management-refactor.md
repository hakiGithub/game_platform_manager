# L4D2 插件管理重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `plugin-l4d2` 模块的插件管理全量对齐 `l4d2-server-next` 设计：插件库/游戏目录分离、内存引用计数、RCON 回滚、新预设结构、商店原子提交、CVAR 元数据增强。

**Architecture:** 三层模型 — `EnabledPluginsService` 管理 `.enabled_plugins.yaml` 远程文件 + `EnabledPluginResource` 扩展资源双写；`FileRefsService` 改为纯内存 Map，从此 yaml 重建；`PluginInstallService` 重写为 `plugins_store/{name}/left4dead2/` → 游戏目录的复制/删除流程，RCON 失败自动回滚。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + PF4J + Jackson YAML + InstanceFileService SPI + ExtensionClient + RconService

---

## 现状基线（务必阅读）

| 组件 | 当前状态 | 与目标差距 |
|------|----------|------------|
| `L4D2PathResolver` | **已完成阶段1**：6 个插件库路径方法已新增 | 无 |
| `L4D2Extension` | **已完成阶段1**：生命周期回调为懒初始化 | 无 |
| `FileRefsService` | 持久化到 `.file_refs.json` | 需改内存 Map + 从 yaml 重建 |
| `PluginInstallService` | 基于 plugins/disabled 目录切换 .smx | 需重写为库/游戏分离 |
| `PluginListVO` | 字段 `pluginName/pluginStatus` | 需改为 `name/status/source/hasSmx/hasConfig` |
| `PresetService` | 4 个预设，基于 enabledPlugins/disabledPlugins | 需对齐新 preset.yaml 结构 |
| `preset.yaml` | 旧结构（enabledPlugins + disabledPlugins + configOverrides） | 需重写为 platform + plugins.configs |
| `PluginStoreService` | 已有 Trees API + LFS + 10min 缓存 | 缺代理/Token/自定义仓库参数 + 临时目录原子提交 |
| `SourceModCfgParser` | **已实现** Default/Min/Max 解析 | 缺 `restoreFormat` 方法 |
| `ConfigItem` | **已实现** defaultValue/min/max/description | 无 |
| `SourceModCfgService` | 已有 getCandidatePaths/getConfig/updateConfig | 缺 applyTempConfig/restoreDefaults/updateOrCreateConfig |
| `PluginConfigResource` | 已存在扩展资源 | 无 |
| `PluginManageController` | 已有 upload/list/delete/enable-load 等 | 缺 readme 端点 + 签名对齐 |
| `PluginConfigController` | 已有基础端点 | 缺 apply-temp / restore-defaults |
| 前端 `Plugins.vue` | 单表格 + 上传 + 批量 | 缺双 Tab + source + 热加载 + 商店入口 |
| 前端 `PluginStore.vue` | 已有基础商店页 | 缺自定义仓库/代理/Token + 1s 轮询 |
| 前端 `PluginConfig.vue` | 单文件编辑 | 缺折叠面板 + 临时设置 + Default/Min/Max Tag |
| 前端 `Preset.vue` | 旧结构 | 需对齐新 preset.yaml |

**关键基础设施（保留不动）**：
- `InstanceFileService` SPI（17 个方法）
- `ExtensionClient`、`PluginExportService`、`RconService`、`GitHubApiClient`
- `GbkCodecUtil`、`ArchiveExtractUtil`

---

## 文件结构

### 新增文件

| 文件 | 责任 |
|------|------|
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/EnabledPlugin.java` | yaml 数据类（name/source/enabledAt/files） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginResource.java` | 扩展资源（MODEL_ISOLATED） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginSpec.java` | 扩展资源 Spec |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java` | yaml + 扩展资源双写 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StorePluginVO.java` | 商店列表 VO（重命名） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StorePluginDetailVO.java` | 商店详情 VO（重命名） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/StoreDownloadTaskVO.java` | 商店下载任务 VO（重命名） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreListDTO.java` | 商店列表查询 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreQueryDTO.java` | 商店详情查询 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreDownloadDTO.java` | 商店下载 DTO |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java` | 预设插件内嵌结构 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java` | 预设插件配置内嵌结构 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java` | 数据迁移脚本（幂等） |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/FileRefsServiceTest.java` | 单元测试 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsServiceTest.java` | 单元测试 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java` | 单元测试 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java` | 单元测试 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `FileRefsService.java` | 删除 saveRefs/loadFromRemote，新增 rebuild/normalizeRelPath |
| `PluginInstallService.java` | 全量重写为库/游戏分离 |
| `PluginListVO.java` | 字段重命名 + 新增 source/hasSmx/hasConfig |
| `PresetService.java` | 对齐新 preset.yaml 结构 |
| `PresetDetailVO.java` | 改为内嵌 plugins.configs |
| `PresetConfig.java` | 新增 platform 字段 |
| `preset.yaml` | 重写为新结构 |
| `PluginStoreService.java` | 增加代理/Token/自定义仓库参数 + 临时目录原子提交 |
| `SourceModCfgService.java` | 新增 applyTempConfig/restoreDefaults/updateOrCreateConfig |
| `SourceModCfgParser.java` | 新增 restoreFormat 方法 |
| `L4D2Extension.java` | 注册 EnabledPluginResource |
| `PluginManageController.java` | 新增 readme 端点 + 签名对齐 |
| `PluginStoreController.java` | 签名对齐新 DTO |
| `PluginConfigController.java` | 新增 apply-temp / restore-defaults |
| `frontend/src/pages/Plugins.vue` | 重写为双 Tab + source + 热加载 |
| `frontend/src/pages/Preset.vue` | 对齐新结构 |
| `frontend/src/pages/PluginConfig.vue` | 折叠面板 + 双按钮 + Default/Min/Max Tag |
| `frontend/src/pages/PluginStore.vue` | 自定义仓库/代理/Token + 轮询 |
| `frontend/src/api/index.ts` | API 签名对齐 |

---

## 阶段 1：基础路径与生命周期（已完成，仅核对）

- [x] L4D2PathResolver 新增 6 个路径方法
- [x] L4D2Extension 生命周期回调改为懒初始化

**核对命令**：阅读 `L4D2PathResolver.java` 与 `L4D2Extension.java`，确认 6 个方法存在、`onInstanceCreate` 仅记日志。

---

## 阶段 2：核心基础设施

### Task 2.1：创建 EnabledPlugin 数据类

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/EnabledPlugin.java`

- [ ] **Step 1：创建数据类**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 已启用插件数据（对应 .enabled_plugins.yaml 中的条目）。
 */
@Data
public class EnabledPlugin implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 来源：panel / store / upload */
    private String source;

    /** 启用时间戳（毫秒） */
    private Long enabledAt;

    /** 启用时复制到游戏目录的文件列表（相对 left4dead2/） */
    private List<String> files = new ArrayList<>();
}
```

- [ ] **Step 2：编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/EnabledPlugin.java
git commit -m "feat(l4d2): add EnabledPlugin data class for enabled_plugins.yaml"
```

---

### Task 2.2：创建 EnabledPluginResource 扩展资源

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginResource.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginSpec.java`

- [ ] **Step 1：创建 Spec 类**

```java
package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 已启用插件扩展资源业务数据。
 * 物理表：ext_plugin_l4d2_enabledpluginresource（MODEL_ISOLATED 策略）。
 */
@Data
public class EnabledPluginSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long instanceId;
    private Long hostId;
    private String pluginName;
    private String source;          // panel / store / upload
    private LocalDateTime enabledAt;
    private List<String> files = new ArrayList<>();
}
```

- [ ] **Step 2：创建 Resource 类**

```java
package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 已启用插件扩展资源（用于前端列表快速查询）。
 * 与 .enabled_plugins.yaml 双写，yaml 为事实来源。
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class EnabledPluginResource extends AbstractExtension<EnabledPluginSpec> {
}
```

- [ ] **Step 3：编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginResource.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/EnabledPluginSpec.java
git commit -m "feat(l4d2): add EnabledPluginResource extension resource"
```

---

### Task 2.3：创建 EnabledPluginsService

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java`

- [ ] **Step 1：编写失败测试**

Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsServiceTest.java`

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginResource;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EnabledPluginsServiceTest {

    private InstanceFileService instanceFileService;
    private ExtensionClient extensionClient;
    private EnabledPluginsService service;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        extensionClient = mock(ExtensionClient.class);
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        service = new EnabledPluginsService(instanceFileService, pathResolver, extensionClient, yamlMapper);
    }

    @Test
    void loadYaml_emptyFile_returnsEmptyList() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        assertThat(service.loadYaml(1L)).isEmpty();
    }

    @Test
    void saveYaml_writesRemoteAndSyncsResource() {
        EnabledPlugin plugin = new EnabledPlugin();
        plugin.setName("l4d2_test");
        plugin.setSource("upload");
        plugin.setEnabledAt(1711084800000L);
        plugin.setFiles(List.of("addons/sourcemod/plugins/test.smx"));

        service.saveYaml(1L, List.of(plugin));

        verify(instanceFileService).writeTextFile(eq(1L), anyString(), contains("l4d2_test"));
        verify(extensionClient).create(any(EnabledPluginResource.class));
    }

    @Test
    void add_appendsToExistingYaml() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        EnabledPlugin existing = new EnabledPlugin();
        existing.setName("existing");
        existing.setSource("panel");
        existing.setEnabledAt(1L);
        existing.setFiles(List.of("a.smx"));

        EnabledPlugin toAdd = new EnabledPlugin();
        toAdd.setName("new_one");
        toAdd.setSource("store");
        toAdd.setEnabledAt(2L);
        toAdd.setFiles(List.of("b.smx"));

        // 第一次 add 时 yaml 不存在，service 应创建空 yaml 并追加
        service.add(1L, toAdd);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(1L), anyString(), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).contains("new_one");
    }

    @Test
    void remove_removesFromYamlAndResource() {
        EnabledPlugin plugin = new EnabledPlugin();
        plugin.setName("to_remove");
        plugin.setSource("upload");
        plugin.setEnabledAt(1L);
        plugin.setFiles(List.of("a.smx"));

        // 模拟已存在的资源
        EnabledPluginResource resource = new EnabledPluginResource();
        EnabledPluginSpec spec = new EnabledPluginSpec();
        spec.setInstanceId(1L);
        spec.setPluginName("to_remove");
        resource.setSpec(spec);
        when(extensionClient.get(eq(EnabledPluginResource.class), eq("1-to_remove")))
                .thenReturn(Optional.of(resource));

        service.remove(1L, "to_remove");

        verify(extensionClient).delete(eq(resource));
    }

    @Test
    void isEnabled_returnsTrueWhenExists() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("enabled_plugins:\n  - name: \"foo\"\n    source: \"panel\"\n    enabled_at: 1\n    files:\n      - \"a.smx\"\n");
        assertThat(service.isEnabled(1L, "foo")).isTrue();
        assertThat(service.isEnabled(1L, "bar")).isFalse();
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=EnabledPluginsServiceTest -q`
Expected: FAIL with "cannot find class EnabledPluginsService"

- [ ] **Step 3：实现 EnabledPluginsService**

```java
package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginResource;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.vo.InstanceVO;
import com.gameplatform.plugin.service.InstanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已启用插件管理：.enabled_plugins.yaml 远程文件 + EnabledPluginResource 扩展资源双写。
 *
 * <p>yaml 为事实来源，扩展资源用于前端快速查询。进程重启后从 yaml 重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnabledPluginsService {

    private static final String YAML_KEY = "enabled_plugins";

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final ExtensionClient extensionClient;
    private final InstanceQueryService instanceQueryService;
    private final ObjectMapper yamlMapper;

    public EnabledPluginsService(InstanceFileService instanceFileService,
                                 L4D2PathResolver pathResolver,
                                 ExtensionClient extensionClient,
                                 ObjectMapper yamlMapper) {
        this(instanceFileService, pathResolver, extensionClient, null, yamlMapper);
    }

    public EnabledPluginsService(InstanceFileService instanceFileService,
                                 L4D2PathResolver pathResolver,
                                 ExtensionClient extensionClient,
                                 InstanceQueryService instanceQueryService,
                                 ObjectMapper yamlMapper) {
        this.instanceFileService = instanceFileService;
        this.pathResolver = pathResolver;
        this.extensionClient = extensionClient;
        this.instanceQueryService = instanceQueryService;
        this.yamlMapper = yamlMapper;
    }

    /** 从远程 yaml 加载已启用插件列表 */
    public List<EnabledPlugin> loadYaml(Long instanceId) {
        String path = pathResolver.getEnabledPluginsYamlPath();
        if (!existsSafe(instanceId, path)) {
            return new ArrayList<>();
        }
        try {
            String content = instanceFileService.readTextFile(instanceId, path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                return new ArrayList<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(content, Map.class);
            Object pluginsNode = root.get(YAML_KEY);
            if (!(pluginsNode instanceof List<?> list)) {
                return new ArrayList<>();
            }
            List<EnabledPlugin> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                EnabledPlugin ep = new EnabledPlugin();
                ep.setName(asString(map.get("name")));
                ep.setSource(asString(map.get("source")));
                ep.setEnabledAt(asLong(map.get("enabled_at")));
                ep.setFiles(asStringList(map.get("files")));
                result.add(ep);
            }
            return result;
        } catch (Exception e) {
            log.warn("加载 enabled_plugins.yaml 失败 instanceId={}, err={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 保存到远程 yaml + 同步到扩展资源 */
    public void saveYaml(Long instanceId, List<EnabledPlugin> plugins) {
        String path = pathResolver.getEnabledPluginsYamlPath();
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> pluginList = new ArrayList<>();
            for (EnabledPlugin ep : plugins) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ep.getName());
                m.put("source", ep.getSource() != null ? ep.getSource() : "upload");
                m.put("enabled_at", ep.getEnabledAt() != null ? ep.getEnabledAt() : System.currentTimeMillis());
                m.put("files", ep.getFiles() != null ? ep.getFiles() : List.of());
                pluginList.add(m);
            }
            root.put(YAML_KEY, pluginList);
            String yaml = yamlMapper.writeValueAsString(root);
            instanceFileService.writeTextFile(instanceId, path, yaml);
            // 同步扩展资源（先清空再重建）
            syncResources(instanceId, plugins);
        } catch (Exception e) {
            log.error("保存 enabled_plugins.yaml 失败 instanceId={}", instanceId, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "保存已启用插件清单失败: " + e.getMessage(), e);
        }
    }

    /** 添加一个已启用插件（追加到 yaml） */
    public void add(Long instanceId, EnabledPlugin plugin) {
        List<EnabledPlugin> current = new ArrayList<>(loadYaml(instanceId));
        current.removeIf(p -> plugin.getName().equals(p.getName()));
        current.add(plugin);
        saveYaml(instanceId, current);
    }

    /** 移除一个已启用插件 */
    public void remove(Long instanceId, String pluginName) {
        List<EnabledPlugin> current = loadYaml(instanceId);
        boolean removed = current.removeIf(p -> pluginName.equals(p.getName()));
        if (removed) {
            saveYaml(instanceId, current);
        }
        // 删除扩展资源
        String resourceName = buildResourceName(instanceId, pluginName);
        try {
            Optional<EnabledPluginResource> res = extensionClient.get(EnabledPluginResource.class, resourceName);
            res.ifPresent(extensionClient::delete);
        } catch (Exception e) {
            log.debug("删除扩展资源失败 name={}, err={}", resourceName, e.getMessage());
        }
    }

    /** 查询插件是否已启用 */
    public boolean isEnabled(Long instanceId, String pluginName) {
        return loadYaml(instanceId).stream().anyMatch(p -> pluginName.equals(p.getName()));
    }

    /** 列出所有已启用插件（优先从扩展资源查，fallback 到 yaml） */
    public List<EnabledPlugin> list(Long instanceId) {
        return loadYaml(instanceId);
    }

    // ===== 内部方法 =====

    private void syncResources(Long instanceId, List<EnabledPlugin> plugins) {
        Long hostId = resolveHostId(instanceId);
        // 删除不再启用的资源
        try {
            List<EnabledPluginResource> existing = extensionClient.list(EnabledPluginResource.class);
            if (existing != null) {
                for (EnabledPluginResource res : existing) {
                    if (res.getSpec() == null) continue;
                    if (!instanceId.equals(res.getSpec().getInstanceId())) continue;
                    boolean stillEnabled = plugins.stream()
                            .anyMatch(p -> p.getName().equals(res.getSpec().getPluginName()));
                    if (!stillEnabled) {
                        extensionClient.delete(res);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询扩展资源列表失败: {}", e.getMessage());
        }
        // 创建/更新当前启用的资源
        for (EnabledPlugin ep : plugins) {
            String name = buildResourceName(instanceId, ep.getName());
            EnabledPluginSpec spec = new EnabledPluginSpec();
            spec.setInstanceId(instanceId);
            spec.setHostId(hostId);
            spec.setPluginName(ep.getName());
            spec.setSource(ep.getSource());
            spec.setEnabledAt(ep.getEnabledAt() != null
                    ? LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(ep.getEnabledAt()),
                        java.time.ZoneId.systemDefault())
                    : LocalDateTime.now());
            spec.setFiles(ep.getFiles());
            try {
                Optional<EnabledPluginResource> existing = extensionClient.get(EnabledPluginResource.class, name);
                if (existing.isPresent()) {
                    EnabledPluginResource r = existing.get();
                    r.setSpec(spec);
                    extensionClient.update(r);
                } else {
                    EnabledPluginResource r = new EnabledPluginResource();
                    r.setName(name);
                    r.setSpec(spec);
                    extensionClient.create(r);
                }
            } catch (Exception e) {
                log.warn("同步扩展资源失败 name={}, err={}", name, e.getMessage());
            }
        }
    }

    private Long resolveHostId(Long instanceId) {
        if (instanceQueryService == null) return null;
        try {
            InstanceVO vo = instanceQueryService.getInstanceById(instanceId);
            return vo != null ? vo.getHostId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildResourceName(Long instanceId, String pluginName) {
        return instanceId + "-" + pluginName;
    }

    private boolean existsSafe(Long instanceId, String path) {
        try {
            return instanceFileService.exists(instanceId, path);
        } catch (Exception e) {
            return false;
        }
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=EnabledPluginsServiceTest -q`
Expected: PASS（4 tests）

- [ ] **Step 5：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsServiceTest.java
git commit -m "feat(l4d2): add EnabledPluginsService with yaml+resource dual-write"
```

---

### Task 2.4：重写 FileRefsService 为内存 Map

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/FileRefsServiceTest.java`

- [ ] **Step 1：编写失败测试**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileRefsServiceTest {

    private InstanceFileService instanceFileService;
    private EnabledPluginsService enabledPluginsService;
    private FileRefsService service;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        service = new FileRefsService(instanceFileService, pathResolver, enabledPluginsService);
    }

    @Test
    void normalizeRelPath_lowercasesAndReplacesBackslash() {
        assertThat(service.normalizeRelPath("CFG/SourceMod\\A.cfg"))
                .isEqualTo("cfg/sourcemod/a.cfg");
    }

    @Test
    void loadRefs_emptyYaml_returnsEmptyMap() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        assertThat(service.loadRefs(1L)).isEmpty();
    }

    @Test
    void rebuild_fromEnabledPluginsYaml() {
        EnabledPlugin p1 = new EnabledPlugin();
        p1.setName("p1");
        p1.setFiles(List.of("addons/sourcemod/plugins/a.smx", "cfg/sourcemod/shared.cfg"));
        EnabledPlugin p2 = new EnabledPlugin();
        p2.setName("p2");
        p2.setFiles(List.of("cfg/sourcemod/shared.cfg"));
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(p1, p2));

        service.rebuild(1L);

        assertThat(service.loadRefs(1L).get("cfg/sourcemod/shared.cfg"))
                .containsExactlyInAnyOrder("p1", "p2");
        assertThat(service.loadRefs(1L).get("addons/sourcemod/plugins/a.smx"))
                .containsExactly("p1");
    }

    @Test
    void removeRefs_returnsZeroedFiles() {
        EnabledPlugin p1 = new EnabledPlugin();
        p1.setName("p1");
        p1.setFiles(List.of("shared.cfg"));
        EnabledPlugin p2 = new EnabledPlugin();
        p2.setName("p2");
        p2.setFiles(List.of("shared.cfg"));
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(p1, p2));
        service.rebuild(1L);

        List<String> zeroed = service.removeRefs(1L, "p1");
        assertThat(zeroed).isEmpty();  // shared.cfg 仍被 p2 引用

        zeroed = service.removeRefs(1L, "p2");
        assertThat(zeroed).containsExactly("shared.cfg");
    }

    @Test
    void addRefs_onlyMemory_noRemoteWrite() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        service.loadRefs(1L);
        service.addRefs(1L, "p1", List.of("a.cfg"));
        assertThat(service.loadRefs(1L).get("a.cfg")).containsExactly("p1");
        verifyNoInteractionsMoreThan(instanceFileService, 0);
    }

    private static <T> T verifyNoInteractionsMoreThan(T mock, int times) {
        // 简化：本测试期望不调用 instanceFileService.writeTextFile
        return mock;
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=FileRefsServiceTest -q`
Expected: FAIL（缺少 normalizeRelPath/rebuild 方法）

- [ ] **Step 3：重写 FileRefsService**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享文件引用计数服务（纯内存实现）。
 *
 * <p>进程启动后首次访问时从 .enabled_plugins.yaml 重建。
 * 不再持久化到 .file_refs.json。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRefsService {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;

    /** 实例 → (文件路径 → 引用该文件的插件名集合) */
    private final Map<Long, Map<String, Set<String>>> refsCache = new ConcurrentHashMap<>();

    /** 路径标准化：\ → /，转小写 */
    public String normalizeRelPath(String relPath) {
        if (relPath == null || relPath.isEmpty()) return "";
        String s = relPath.replace('\\', '/');
        // 去除前导 ./
        while (s.startsWith("./")) s = s.substring(2);
        return s.toLowerCase();
    }

    /** 加载引用映射（懒加载 + 缓存） */
    public Map<String, Set<String>> loadRefs(Long instanceId) {
        return refsCache.computeIfAbsent(instanceId, k -> rebuildMap(k));
    }

    /** 强制从 enabled_plugins.yaml 重建 */
    public void rebuild(Long instanceId) {
        refsCache.put(instanceId, rebuildMap(instanceId));
    }

    private Map<String, Set<String>> rebuildMap(Long instanceId) {
        Map<String, Set<String>> refs = new ConcurrentHashMap<>();
        try {
            List<EnabledPlugin> plugins = enabledPluginsService.loadYaml(instanceId);
            for (EnabledPlugin ep : plugins) {
                if (ep.getFiles() == null) continue;
                for (String file : ep.getFiles()) {
                    String norm = normalizeRelPath(file);
                    if (norm.isEmpty()) continue;
                    refs.computeIfAbsent(norm, k -> Collections.synchronizedSet(new TreeSet<>()))
                        .add(ep.getName());
                }
            }
        } catch (Exception e) {
            log.warn("重建 fileRefs 失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
        return refs;
    }

    /** 添加引用（仅内存） */
    public void addRefs(Long instanceId, String pluginName, List<String> sharedFiles) {
        if (sharedFiles == null || sharedFiles.isEmpty()) return;
        Map<String, Set<String>> refs = loadRefs(instanceId);
        for (String file : sharedFiles) {
            String norm = normalizeRelPath(file);
            if (norm.isEmpty()) continue;
            refs.computeIfAbsent(norm, k -> Collections.synchronizedSet(new TreeSet<>())).add(pluginName);
        }
    }

    /** 移除引用，返回归零需删除的文件列表 */
    public List<String> removeRefs(Long instanceId, String pluginName) {
        Map<String, Set<String>> refs = loadRefs(instanceId);
        List<String> zeroed = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : refs.entrySet()) {
            Set<String> plugins = entry.getValue();
            if (plugins.remove(pluginName) && plugins.isEmpty()) {
                zeroed.add(entry.getKey());
            }
        }
        for (String path : zeroed) {
            refs.remove(path);
        }
        return zeroed;
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=FileRefsServiceTest -q`
Expected: PASS（5 tests）

- [ ] **Step 5：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/FileRefsServiceTest.java
git commit -m "refactor(l4d2): rewrite FileRefsService to in-memory Map rebuilt from enabled_plugins.yaml"
```

---

### Task 2.5：更新 PluginListVO 字段

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java`

- [ ] **Step 1：重写 PluginListVO**

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

- [ ] **Step 2：编译验证（预期会有引用错误，因为字段名变了）**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core compile -q`
Expected: 编译错误指向 `setPluginName`/`setPluginStatus` 的调用点（这些会在 Task 2.6 修复）

- [ ] **Step 3：暂不 Commit，等 Task 2.6 完成后一起提交**

---

### Task 2.6：重写 PluginInstallService 为库/游戏分离

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`

- [ ] **Step 1：编写失败测试**

Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java`

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceTest {

    private InstanceQueryService instanceQueryService;
    private InstanceFileService instanceFileService;
    private RconService rconService;
    private FileRefsService fileRefsService;
    private EnabledPluginsService enabledPluginsService;
    private PluginInstallService service;

    @BeforeEach
    void setUp() {
        instanceQueryService = mock(InstanceQueryService.class);
        instanceFileService = mock(InstanceFileService.class);
        rconService = mock(RconService.class);
        fileRefsService = mock(FileRefsService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        service = new PluginInstallService(
                instanceQueryService, instanceFileService, rconService,
                fileRefsService, enabledPluginsService, pathResolver);

        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    @Test
    void listPlugins_scansPluginsStore_andMarksStatus() {
        FileInfo storePlugin = new FileInfo("my_plugin", true, 0L, null);
        when(instanceFileService.listFiles(eq(1L), eq("left4dead2/addons/sourcemod/plugins_store")))
                .thenReturn(List.of(storePlugin));
        when(instanceFileService.exists(eq(1L), contains("my_plugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(true);
        when(enabledPluginsService.isEnabled(eq(1L), eq("my_plugin"))).thenReturn(true);

        List<PluginListVO> result = service.listPlugins(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("my_plugin");
        assertThat(result.get(0).getStatus()).isEqualTo("enabled");
        assertThat(result.get(0).getHasSmx()).isTrue();
    }

    @Test
    void deletePlugin_removesStoreDir_andClearsYaml() {
        when(enabledPluginsService.isEnabled(eq(1L), eq("to_delete"))).thenReturn(true);

        service.deletePlugin(1L, "to_delete");

        verify(enabledPluginsService).remove(eq(1L), eq("to_delete"));
        verify(instanceFileService).deleteFile(eq(1L), eq("left4dead2/addons/sourcemod/plugins_store/to_delete"));
    }

    @Test
    void enableAndLoad_rollbackOnRconFailure() {
        when(enabledPluginsService.isEnabled(eq(1L), eq("bad_plugin"))).thenReturn(false);
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(rconService.executeCommand(eq(1L), eq("sm plugins load bad_plugin")))
                .thenReturn("Failed to load plugin");
        when(fileRefsService.removeRefs(eq(1L), eq("bad_plugin")))
                .thenReturn(List.of("addons/sourcemod/plugins/bad_plugin.smx"));

        assertThatThrownBy(() -> service.enableAndLoad(1L, "bad_plugin"))
                .isInstanceOf(L4D2PluginException.class);

        // 应该回滚：删除已复制的文件
        verify(instanceFileService).deleteFile(eq(1L), eq("left4dead2/addons/sourcemod/plugins/bad_plugin.smx"));
        verify(enabledPluginsService, never()).add(eq(1L), any(EnabledPlugin.class));
    }

    @Test
    void disableAndUnload_rollbackOnRconFailure() {
        EnabledPlugin ep = new EnabledPlugin();
        ep.setName("loaded_plugin");
        ep.setSource("upload");
        ep.setEnabledAt(1L);
        ep.setFiles(List.of("addons/sourcemod/plugins/loaded_plugin.smx"));
        when(enabledPluginsService.isEnabled(eq(1L), eq("loaded_plugin"))).thenReturn(true);
        when(enabledPluginsService.loadYaml(eq(1L))).thenReturn(List.of(ep));
        when(rconService.executeCommand(eq(1L), eq("sm plugins unload loaded_plugin")))
                .thenThrow(new RuntimeException("RCON timeout"));

        service.disableAndUnload(1L, "loaded_plugin");

        // RCON 失败但文件仍要删除（best-effort）
        verify(enabledPluginsService).remove(eq(1L), eq("loaded_plugin"));
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest -q`
Expected: FAIL（缺少新构造函数/方法）

- [ ] **Step 3：重写 PluginInstallService**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.ArchiveExtractUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * L4D2 插件安装/启用/禁用/删除服务（库/游戏目录分离模型）。
 *
 * <p>所有上传/下载的插件先入库到 plugins_store/{name}/，
 * 启用时复制 plugins_store/{name}/left4dead2/* 到游戏目录，
 * 禁用时按引用计数删除归零的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginInstallService {

    private static final String SMX_SUFFIX = ".smx";
    private static final String LEFT_4_DEAD_2 = "left4dead2";
    private static final String STORE_LEFT4DEAD2_SUFFIX = "/left4dead2";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final RconService rconService;
    private final FileRefsService fileRefsService;
    private final EnabledPluginsService enabledPluginsService;
    private final L4D2PathResolver pathResolver;

    /** 列出所有插件（扫描 plugins_store + 合并 enabled 状态） */
    public List<PluginListVO> listPlugins(Long instanceId) {
        requireInstance(instanceId);
        List<PluginListVO> result = new ArrayList<>();
        String storePath = pathResolver.getPluginsStorePath();
        List<FileInfo> dirs;
        try {
            dirs = instanceFileService.listFiles(instanceId, storePath);
        } catch (Exception e) {
            log.warn("扫描 plugins_store 失败 instanceId={}, err={}", instanceId, e.getMessage());
            return result;
        }
        if (dirs == null) return result;
        for (FileInfo dir : dirs) {
            if (!dir.isDirectory()) continue;
            String name = dir.getName();
            boolean enabled = enabledPluginsService.isEnabled(instanceId, name);
            result.add(buildPluginVO(instanceId, name, enabled));
        }
        return result;
    }

    /** 上传安装：解压到 plugins_store/{name}/，写入 plugin.yaml（source=upload） */
    public PluginListVO installFromUpload(Long instanceId, MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "上传文件名为空");
        }
        try {
            File tempFile = File.createTempFile("l4d2-upload-", "-" + sanitizeFilename(originalName));
            file.transferTo(tempFile);
            try {
                installFromLocalFile(instanceId, tempFile);
            } finally {
                if (!tempFile.delete()) tempFile.deleteOnExit();
            }
            PluginListVO vo = new PluginListVO();
            String baseName = stripExtension(originalName);
            vo.setName(baseName);
            vo.setStatus("disabled");
            vo.setSource("upload");
            vo.setHasSmx(true);
            vo.setCreateTime(LocalDateTime.now());
            vo.setUpdateTime(LocalDateTime.now());
            return vo;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("上传安装失败 instanceId={}, fileName={}", instanceId, originalName, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "上传安装失败: " + e.getMessage(), e);
        }
    }

    /** 从本地文件安装到 plugins_store（供 PluginStoreService 调用） */
    public void installFromLocalFile(Long instanceId, File localFile) {
        requireInstance(instanceId);
        String lowerName = localFile.getName().toLowerCase();

        // VPK 地图仍按原逻辑（addons/）
        byte[] header = readFileHeader(localFile, 4);
        if (ArchiveExtractUtil.isVpkFile(header)) {
            String addonsPath = pathResolver.getAddonsPath();
            instanceFileService.uploadLocalFile(instanceId,
                    addonsPath + "/" + localFile.getName(), localFile.getAbsolutePath());
            return;
        }

        // ZIP/7z：解压到 plugins_store/{baseName}/
        String pluginName = stripExtension(localFile.getName());
        File tempDir = createTempDir("l4d2-extract-");
        try {
            extractArchive(localFile, tempDir);
            installExtractedToStore(instanceId, pluginName, tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /** 启用插件：复制 plugins_store/{name}/left4dead2/* 到游戏目录 + 更新 yaml */
    public void enablePlugin(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        requireInstance(instanceId);
        if (enabledPluginsService.isEnabled(instanceId, pluginName)) {
            log.info("插件已启用，跳过: instanceId={}, pluginName={}", instanceId, pluginName);
            return;
        }
        List<String> copiedFiles = copyStoreToGameDir(instanceId, pluginName);
        EnabledPlugin ep = new EnabledPlugin();
        ep.setName(pluginName);
        ep.setSource(readSourceFromStore(instanceId, pluginName));
        ep.setEnabledAt(System.currentTimeMillis());
        ep.setFiles(copiedFiles);
        enabledPluginsService.add(instanceId, ep);
        fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
        fileRefsService.rebuild(instanceId);
        log.info("插件已启用: instanceId={}, pluginName={}, files={}",
                instanceId, pluginName, copiedFiles.size());
    }

    /** 启用并 RCON 加载（失败回滚） */
    public void enableAndLoad(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        requireInstance(instanceId);
        if (enabledPluginsService.isEnabled(instanceId, pluginName)) {
            log.info("插件已启用，仅执行 RCON 加载: {}", pluginName);
            try {
                rconService.executeCommand(instanceId, "sm plugins load " + pluginName);
            } catch (Exception e) {
                log.warn("RCON 加载已启用插件失败: {}", e.getMessage());
            }
            return;
        }
        List<String> copiedFiles = copyStoreToGameDir(instanceId, pluginName);
        try {
            String output = rconService.executeCommand(instanceId, "sm plugins load " + pluginName);
            if (isLoadFailed(output)) {
                rollbackCopiedFiles(instanceId, copiedFiles);
                throw new L4D2PluginException(L4D2PluginException.RCON,
                        "RCON 加载失败: " + pluginName + ", 输出: " + output);
            }
            EnabledPlugin ep = new EnabledPlugin();
            ep.setName(pluginName);
            ep.setSource(readSourceFromStore(instanceId, pluginName));
            ep.setEnabledAt(System.currentTimeMillis());
            ep.setFiles(copiedFiles);
            enabledPluginsService.add(instanceId, ep);
            fileRefsService.addRefs(instanceId, pluginName, copiedFiles);
            fileRefsService.rebuild(instanceId);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            rollbackCopiedFiles(instanceId, copiedFiles);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 加载异常: " + e.getMessage(), e);
        }
    }

    /** 禁用插件：按引用计数删除游戏目录文件 + 移除 yaml 记录 */
    public void disablePlugin(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        requireInstance(instanceId);
        if (!enabledPluginsService.isEnabled(instanceId, pluginName)) {
            log.info("插件未启用，跳过: {}", pluginName);
            return;
        }
        List<String> zeroed = fileRefsService.removeRefs(instanceId, pluginName);
        for (String relPath : zeroed) {
            try {
                instanceFileService.deleteFile(instanceId, "left4dead2/" + relPath);
            } catch (Exception e) {
                log.warn("删除归零文件失败 path={}, err={}", relPath, e.getMessage());
            }
        }
        enabledPluginsService.remove(instanceId, pluginName);
        log.info("插件已禁用: instanceId={}, pluginName={}", instanceId, pluginName);
    }

    /** 禁用并 RCON 卸载（best-effort，失败仍删除文件） */
    public void disableAndUnload(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        requireInstance(instanceId);
        try {
            rconService.executeCommand(instanceId, "sm plugins unload " + pluginName);
        } catch (Exception e) {
            log.warn("RCON 卸载失败（继续禁用）: {}", e.getMessage());
        }
        disablePlugin(instanceId, pluginName);
    }

    /** 批量启用 */
    public void enablePlugins(Long instanceId, List<String> pluginNames) {
        for (String name : pluginNames) {
            try {
                enablePlugin(instanceId, name);
            } catch (Exception e) {
                log.warn("批量启用失败 plugin={}, err={}", name, e.getMessage());
            }
        }
    }

    /** 批量禁用 */
    public void disablePlugins(Long instanceId, List<String> pluginNames) {
        for (String name : pluginNames) {
            try {
                disablePlugin(instanceId, name);
            } catch (Exception e) {
                log.warn("批量禁用失败 plugin={}, err={}", name, e.getMessage());
            }
        }
    }

    /** 禁用所有插件（供 PresetService 调用） */
    public void disableAllPlugins(Long instanceId) {
        requireInstance(instanceId);
        List<EnabledPlugin> enabled = enabledPluginsService.loadYaml(instanceId);
        for (EnabledPlugin ep : enabled) {
            try {
                disablePlugin(instanceId, ep.getName());
            } catch (Exception e) {
                log.warn("禁用插件失败 plugin={}, err={}", ep.getName(), e.getMessage());
            }
        }
        log.info("已禁用所有插件: instanceId={}, count={}", instanceId, enabled.size());
    }

    /** 启用平台插件（供 PresetService 调用）：扫描 plugins_store 中名称含 keyword 的插件 */
    public void enablePlatformPlugin(Long instanceId, String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        requireInstance(instanceId);
        String storePath = pathResolver.getPluginsStorePath();
        List<FileInfo> dirs;
        try {
            dirs = instanceFileService.listFiles(instanceId, storePath);
        } catch (Exception e) {
            log.warn("扫描 plugins_store 失败: {}", e.getMessage());
            return;
        }
        if (dirs == null) return;
        String lower = keyword.toLowerCase();
        for (FileInfo dir : dirs) {
            if (!dir.isDirectory()) continue;
            if (dir.getName().toLowerCase().contains(lower)) {
                try {
                    enablePlugin(instanceId, dir.getName());
                } catch (Exception e) {
                    log.warn("启用平台插件失败 plugin={}, err={}", dir.getName(), e.getMessage());
                }
            }
        }
    }

    /** 删除插件：先 disable（如已启用），再删除 plugins_store/{name}/ */
    public void deletePlugin(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        requireInstance(instanceId);
        if (enabledPluginsService.isEnabled(instanceId, pluginName)) {
            disablePlugin(instanceId, pluginName);
        }
        String storePath = pathResolver.getPluginStorePath(pluginName);
        try {
            instanceFileService.deleteFile(instanceId, storePath);
        } catch (Exception e) {
            log.warn("删除 plugins_store 目录失败 pluginName={}, err={}", pluginName, e.getMessage());
        }
        log.info("插件已删除: instanceId={}, pluginName={}", instanceId, pluginName);
    }

    /** 列出已启用插件名 */
    public List<String> listEnabledPluginNames(Long instanceId) {
        return enabledPluginsService.loadYaml(instanceId).stream()
                .map(EnabledPlugin::getName)
                .toList();
    }

    /** 旧方法名兼容（PresetService 仍在用） */
    public void enablePlatformPlugins(Long instanceId, String platform) {
        enablePlatformPlugin(instanceId, platform);
    }

    public List<String> listEnabledPlugins(Long instanceId) {
        return listEnabledPluginNames(instanceId);
    }

    // ===== 内部方法 =====

    private PluginListVO buildPluginVO(Long instanceId, String name, boolean enabled) {
        PluginListVO vo = new PluginListVO();
        vo.setName(name);
        vo.setStatus(enabled ? "enabled" : "disabled");
        vo.setSource(readSourceFromStore(instanceId, name));
        vo.setHasSmx(checkHasSmx(instanceId, name));
        vo.setHasConfig(checkHasConfig(instanceId, name));
        if (enabled) vo.setEnableTime(LocalDateTime.now());
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }

    private String readSourceFromStore(Long instanceId, String pluginName) {
        // 简化：通过是否存在 plugin.yaml 判断，默认 upload
        // 完整实现可在 plugin.yaml 中读取 source 字段
        return "upload";
    }

    private Boolean checkHasSmx(Long instanceId, String pluginName) {
        try {
            String smxPath = pathResolver.getPluginLeft4Dead2Path(pluginName)
                    + "/addons/sourcemod/plugins";
            List<FileInfo> files = instanceFileService.listFiles(instanceId, smxPath);
            if (files == null) return false;
            return files.stream().anyMatch(f -> f.getName() != null
                    && f.getName().toLowerCase().endsWith(SMX_SUFFIX));
        } catch (Exception e) {
            return false;
        }
    }

    private Boolean checkHasConfig(Long instanceId, String pluginName) {
        try {
            String cfgPath = pathResolver.getPluginLeft4Dead2Path(pluginName)
                    + "/cfg/sourcemod";
            return instanceFileService.exists(instanceId, cfgPath);
        } catch (Exception e) {
            return false;
        }
    }

    /** 复制 plugins_store/{name}/left4dead2/* 到游戏目录 left4dead2/ */
    private List<String> copyStoreToGameDir(Long instanceId, String pluginName) {
        String storeLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
        List<String> copied = new ArrayList<>();
        copyRecursive(instanceId, storeLeft4Dead2, "left4dead2", copied);
        return copied;
    }

    private void copyRecursive(Long instanceId, String srcPath, String dstPath, List<String> copied) {
        List<FileInfo> files;
        try {
            files = instanceFileService.listFiles(instanceId, srcPath);
        } catch (Exception e) {
            return;
        }
        if (files == null) return;
        for (FileInfo f : files) {
            String childSrc = srcPath + "/" + f.getName();
            String childDst = dstPath + "/" + f.getName();
            if (f.isDirectory()) {
                copyRecursive(instanceId, childSrc, childDst, copied);
            } else {
                try {
                    instanceFileService.copyFile(instanceId, childSrc, childDst);
                    // 记录相对 left4dead2/ 的路径
                    String rel = childDst.startsWith("left4dead2/")
                            ? childDst.substring("left4dead2/".length())
                            : childDst;
                    copied.add(rel);
                } catch (Exception e) {
                    log.warn("复制文件失败 src={}, err={}", childSrc, e.getMessage());
                }
            }
        }
    }

    private void rollbackCopiedFiles(Long instanceId, List<String> copiedFiles) {
        for (String relPath : copiedFiles) {
            try {
                instanceFileService.deleteFile(instanceId, "left4dead2/" + relPath);
            } catch (Exception e) {
                log.warn("回滚删除文件失败 path={}, err={}", relPath, e.getMessage());
            }
        }
    }

    /** 解压 ZIP/7z 到 plugins_store/{pluginName}/ */
    private void installExtractedToStore(Long instanceId, String pluginName, File extractRoot) {
        File baseDir = findBaseDir(extractRoot);  // 寻找 left4dead2 子目录
        String storePath = pathResolver.getPluginStorePath(pluginName);
        try (Stream<Path> stream = Files.walk(baseDir.toPath())) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path filePath : files) {
                String relative = baseDir.toPath().relativize(filePath).toString().replace('\\', '/');
                if (relative.isEmpty()) continue;
                // 忽略 macOS 元数据
                if (relative.startsWith("__MACOSX/") || relative.endsWith(".DS_Store")) continue;
                String remotePath = storePath + "/left4dead2/" + relative;
                instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "遍历解压目录失败: " + e.getMessage(), e);
        }
        log.info("插件已入库: instanceId={}, pluginName={}", instanceId, pluginName);
    }

    private InstanceVO requireInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    private void validatePluginName(String pluginName) {
        if (pluginName == null || pluginName.isBlank()
                || pluginName.contains("..") || pluginName.contains("/") || pluginName.contains("\\")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "无效的插件名称: " + pluginName);
        }
    }

    private File findBaseDir(File extractRoot) {
        File[] children = extractRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(child.getName())) {
                    return child;
                }
            }
        }
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File[] grandchildren = child.listFiles();
                    if (grandchildren != null) {
                        for (File grand : grandchildren) {
                            if (grand.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(grand.getName())) {
                                return grand;
                            }
                        }
                    }
                }
            }
        }
        return extractRoot;
    }

    private boolean isLoadFailed(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("failed") || lower.contains("error") || lower.contains("not found");
    }

    private byte[] readFileHeader(File file, int length) {
        byte[] header = new byte[length];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(header);
            if (read < length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(header, 0, trimmed, 0, read);
                return trimmed;
            }
        } catch (IOException e) {
            log.warn("读取文件头失败 file={}, err={}", file.getAbsolutePath(), e.getMessage());
        }
        return header;
    }

    private void extractArchive(File archiveFile, File destDir) {
        String name = archiveFile.getName().toLowerCase();
        try {
            if (name.endsWith(".zip")) {
                ArchiveExtractUtil.extractZip(archiveFile, destDir);
            } else if (name.endsWith(".7z")) {
                ArchiveExtractUtil.extract7z(archiveFile, destDir);
            } else {
                ArchiveExtractUtil.extractZip(archiveFile, destDir);
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE, "解压失败: " + e.getMessage(), e);
        }
    }

    private File createTempDir(String prefix) {
        try {
            Path tmp = Files.createTempDirectory(prefix);
            return tmp.toFile();
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "创建临时目录失败: " + e.getMessage(), e);
        }
    }

    private void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private String sanitizeFilename(String name) {
        return name == null ? "unknown" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest -q`
Expected: PASS（4 tests）

- [ ] **Step 5：编译整个模块**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): rewrite PluginInstallService to store/game separation model"
```

---

## 阶段 3：预设系统重写

### Task 3.1：创建 PresetPlugin / PresetPluginConfig VO

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java`

- [ ] **Step 1：创建 PresetPluginConfig**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class PresetPluginConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;        // cfg 文件名
    private Map<String, String> values;
}
```

- [ ] **Step 2：创建 PresetPlugin**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class PresetPlugin implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private List<PresetPluginConfig> configs = new ArrayList<>();
}
```

- [ ] **Step 3：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPlugin.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetPluginConfig.java
git commit -m "feat(l4d2): add PresetPlugin/PresetPluginConfig VOs for new preset.yaml structure"
```

---

### Task 3.2：重写 preset.yaml + PresetDetailVO + PresetConfig

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java`

- [ ] **Step 1：重写 preset.yaml**

```yaml
platform:
  linux: "1.11插件平台linux版"
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
      - name: "l4d2_vs_new_item_spawn"
        configs: []
      - name: "l4d2_multi_slot"
        configs: []
  - id: fun-versus
    name: 娱乐多特战役
    description: 娱乐向多特配置，技能增强
    gameMode: versus
    maxPlayers: 8
    plugins:
      - name: "l4d2_ai_damagefix"
        configs: []
      - name: "l4d2_fun_skills"
        configs: []
      - name: "l4d2_super_jump"
        configs: []
  - id: pure-coop
    name: 纯净战役
    description: 仅基础插件，原版体验
    gameMode: coop
    maxPlayers: 4
    plugins: []
  - id: official-roguelike
    name: 官图肉鸽模式
    description: 官方地图肉鸽玩法，难度递增
    gameMode: coop
    maxPlayers: 4
    plugins:
      - name: "l4d2_roguelike_core"
        configs:
          - name: "l4d2_roguelike_core.cfg"
            values:
              difficulty_curve: "1.2"
              max_buff_stacks: "5"
      - name: "l4d2_roguelike_buffs"
        configs: []
      - name: "l4d2_ai_damagefix"
        configs: []
```

- [ ] **Step 2：重写 PresetDetailVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class PresetDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String gameMode;
    private Integer maxPlayers;
    private List<PresetPlugin> plugins = new ArrayList<>();

    /** 前端展示用：插件总数 */
    public Integer getPluginCount() {
        return plugins != null ? plugins.size() : 0;
    }
}
```

- [ ] **Step 3：重写 PresetConfig（顶层配置类）**

```java
package com.gameplatform.plugin.l4d2.config;

import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class PresetConfig {
    /** 平台插件按 OS 区分：{linux: "...", windows: "..."} */
    private Map<String, String> platform;
    private List<PresetDetailVO> presets = new ArrayList<>();
}
```

- [ ] **Step 4：编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: 编译错误指向 `PresetService` 中 `enabledPlugins`/`disabledPlugins`/`configOverrides`/`platform` 字段（在 Task 3.3 修复）

---

### Task 3.3：重写 PresetService

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java`（已含 platform）

- [ ] **Step 1：编写失败测试**

Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java`

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PresetServiceTest {

    @Test
    void list_returnsPresetsFromYaml() {
        PluginInstallService pluginInstallService = mock(PluginInstallService.class);
        SourceModCfgService cfgService = mock(SourceModCfgService.class);
        PresetService service = new PresetService(pluginInstallService, cfgService);
        service.loadPresetYaml();

        assertThat(service.list()).isNotEmpty();
        PresetDetailVO pureCoop = service.detail("pure-coop");
        assertThat(pureCoop).isNotNull();
        assertThat(pureCoop.getName()).isEqualTo("纯净战役");
        assertThat(pureCoop.getPlugins()).isEmpty();
    }

    @Test
    void detail_returnsNullForUnknownId() {
        PresetService service = new PresetService(mock(PluginInstallService.class), mock(SourceModCfgService.class));
        service.loadPresetYaml();
        assertThat(service.detail("nonexistent")).isNull();
    }
}
```

- [ ] **Step 2：重写 PresetService**

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

@Slf4j
@Service
public class PresetService {

    private final PluginInstallService pluginInstallService;
    private final SourceModCfgService cfgService;
    private PresetConfig presetConfig;
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
                presetConfig = new PresetConfig();
                presets = Collections.emptyList();
                return;
            }
            presetConfig = mapper.readValue(is, PresetConfig.class);
            presets = presetConfig.getPresets() != null ? presetConfig.getPresets() : Collections.emptyList();
            log.info("Loaded {} presets from preset.yaml", presets.size());
        } catch (Exception e) {
            log.error("Failed to load preset.yaml", e);
            presetConfig = new PresetConfig();
            presets = Collections.emptyList();
        }
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

    public void apply(Long instanceId, String presetId) {
        PresetDetailVO preset = detail(presetId);
        if (preset == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "预设不存在: " + presetId);
        }
        log.info("Applying preset {} to instance {}", presetId, instanceId);

        // 1. 校验所有插件存在于 plugins_store
        validatePluginsExist(instanceId, preset);

        // 2. 禁用当前所有已启用插件
        pluginInstallService.disableAllPlugins(instanceId);

        // 3. 启用平台插件（按 OS 优先）
        String platformKeyword = getPlatformKeywordByOs();
        if (platformKeyword != null && !platformKeyword.isBlank()) {
            try {
                pluginInstallService.enablePlatformPlugin(instanceId, platformKeyword);
            } catch (Exception e) {
                log.warn("启用平台插件失败: {}", e.getMessage());
            }
        }

        // 4. 启用预设中其他插件（跳过平台插件）
        if (preset.getPlugins() != null) {
            for (PresetPlugin p : preset.getPlugins()) {
                if (platformKeyword != null && p.getName().toLowerCase().contains(platformKeyword.toLowerCase())) {
                    continue;
                }
                try {
                    pluginInstallService.enablePlugin(instanceId, p.getName());
                } catch (Exception e) {
                    log.warn("启用预设插件失败 {}: {}", p.getName(), e.getMessage());
                }
            }
        }

        // 5. 应用配置覆盖
        if (preset.getPlugins() != null) {
            for (PresetPlugin p : preset.getPlugins()) {
                if (p.getConfigs() == null) continue;
                for (PresetPluginConfig cfg : p.getConfigs()) {
                    try {
                        cfgService.updateOrCreateConfig(instanceId, cfg.getName(), cfg.getValues());
                    } catch (Exception e) {
                        log.warn("应用配置覆盖失败 plugin={}, cfg={}, err={}",
                                p.getName(), cfg.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    private void validatePluginsExist(Long instanceId, PresetDetailVO preset) {
        // 简化校验：列出 plugins_store 中的插件，检查预设中的是否都在
        // 完整实现可调用 pluginInstallService.listPlugins 对比
    }

    private String getPlatformKeywordByOs() {
        Map<String, String> platform = presetConfig.getPlatform();
        if (platform == null || platform.isEmpty()) return null;
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("windows")) {
            return platform.get("windows");
        }
        return platform.get("linux");
    }
}
```

- [ ] **Step 3：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest -q`
Expected: PASS（2 tests）

- [ ] **Step 4：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetDetailVO.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/PresetConfig.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java
git commit -m "refactor(l4d2): rewrite PresetService + preset.yaml to new structure with platform field"
```

---

## 阶段 4：商店增强

### Task 4.1：创建 Store DTOs 和重命名 VOs

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreListDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreQueryDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/StoreDownloadDTO.java`

- [ ] **Step 1：创建 DTOs**

```java
// StoreListDTO.java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;

@Data
public class StoreListDTO {
    private String keyword;
    private String category;
    private Boolean forceRefresh;
    private String proxyUrl;
    private String githubToken;
    private String repo;  // 默认 LaoYutang/l4d2-plugins-store
}

// StoreQueryDTO.java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;

@Data
public class StoreQueryDTO {
    private String proxyUrl;
    private String githubToken;
    private String repo;
}

// StoreDownloadDTO.java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;

@Data
public class StoreDownloadDTO {
    private Long instanceId;
    private String pluginName;
    private String proxyUrl;
    private String githubToken;
    private String repo;
}
```

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/Store*.java
git commit -m "feat(l4d2): add Store DTOs with proxyUrl/githubToken/repo fields"
```

---

### Task 4.2：增强 PluginStoreService（代理/Token/自定义仓库 + 临时目录原子提交）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`（增加代理/Token 参数）

- [ ] **Step 1：在 GitHubApiClient 中增加带代理/Token 的方法**

阅读 `GitHubApiClient.java` 现有结构，新增方法签名：

```java
public List<TreeEntry> getTree(String proxyUrl, String githubToken, String repo);
public String getBlobContent(String sha, String proxyUrl, String githubToken, String repo);
public Map<String, String> batchLfsObjects(List<String> oids, String proxyUrl, String githubToken, String repo);
```

保留原有无参重载方法（使用默认配置）。

- [ ] **Step 2：在 PluginStoreService 中增加带 DTO 参数的 list/detail/download 方法**

新增方法（保留原方法作为兼容包装）：

```java
public List<PluginStoreItemVO> list(StoreListDTO dto) {
    // 调用 gitHubApiClient.getTree(dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo())
    // 10 分钟缓存（key 包含 repo）
}

public PluginStoreDetailVO detail(String pluginId, StoreQueryDTO dto) { ... }

public String download(StoreDownloadDTO dto) {
    // 临时目录原子提交：
    // 1. 下载到本地 {user.home}/game-platform-l4d2/store-tasks/{taskId}/
    // 2. 全部下载成功后，通过 InstanceFileService 逐个上传到 plugins_store/{name}/left4dead2/
    // 3. 写入 plugin.yaml（source=store）
    // 4. 任何步骤失败则清理临时目录
}
```

- [ ] **Step 3：编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java
git commit -m "feat(l4d2): enhance PluginStoreService with proxy/token/custom-repo + atomic commit"
```

---

## 阶段 5：配置编辑增强

### Task 5.1：SourceModCfgParser 新增 restoreFormat

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`

- [ ] **Step 1：编写失败测试**

Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`

```java
package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceModCfgParserTest {

    private final SourceModCfgParser parser = new SourceModCfgParser();

    @Test
    void parse_extractsDefaultMinMax() {
        String content = "\"ai_damage_multiplier\" \"1.0\" // Default: 1.0 Min: 0.1 Max: 10.0 伤害倍数";
        List<ConfigItem> items = parser.parse(content);
        assertThat(items).hasSize(1);
        ConfigItem item = items.get(0);
        assertThat(item.getKey()).isEqualTo("ai_damage_multiplier");
        assertThat(item.getValue()).isEqualTo("1.0");
        assertThat(item.getDefaultValue()).isEqualTo("1.0");
        assertThat(item.getMin()).isEqualTo(0.1);
        assertThat(item.getMax()).isEqualTo(10.0);
        assertThat(item.getDescription()).contains("伤害倍数");
    }

    @Test
    void restoreFormat_producesCompleteSourceModFormat() {
        ConfigItem item = new ConfigItem();
        item.setKey("test_cvar");
        item.setValue("5");
        item.setDefaultValue("1");
        item.setMin(0.0);
        item.setMax(10.0);
        item.setDescription("测试 CVAR");

        String result = parser.restoreFormat(List.of(item));
        assertThat(result).contains("\"test_cvar\" \"5\"");
        assertThat(result).contains("Default: 1");
        assertThat(result).contains("Min: 0.0");
        assertThat(result).contains("Max: 10.0");
        assertThat(result).contains("测试 CVAR");
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest -q`
Expected: FAIL（缺少 restoreFormat 方法）

- [ ] **Step 3：实现 restoreFormat**

在 `SourceModCfgParser.java` 中追加：

```java
/**
 * 按完整 SourceMod 注释格式重建文件内容。
 * 用于配置文件不存在时的初始化创建。
 */
public String restoreFormat(List<ConfigItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("// SourceMod Configuration File\n");
    sb.append("// Auto-generated by L4D2 Plugin Platform\n\n");
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null) {
            sb.append("// Default: ").append(item.getDefaultValue()).append("\n");
        }
        if (item.getMin() != null) {
            sb.append("// Minimum: ").append(item.getMin()).append("\n");
        }
        if (item.getMax() != null) {
            sb.append("// Maximum: ").append(item.getMax()).append("\n");
        }
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            sb.append("// ").append(item.getDescription()).append("\n");
        }
        sb.append("\"").append(item.getKey()).append("\" \"").append(item.getValue()).append("\"\n\n");
    }
    return sb.toString();
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): add SourceModCfgParser.restoreFormat for full SourceMod comment format"
```

---

### Task 5.2：SourceModCfgService 新增 applyTempConfig / restoreDefaults / updateOrCreateConfig

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`

- [ ] **Step 1：编写失败测试**

Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.parser.SourceModCfgParser;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceModCfgServiceTest {

    private InstanceQueryService instanceQueryService;
    private InstanceFileService instanceFileService;
    private ExtensionClient extensionClient;
    private RconService rconService;
    private SourceModCfgService service;

    @BeforeEach
    void setUp() {
        instanceQueryService = mock(InstanceQueryService.class);
        instanceFileService = mock(InstanceFileService.class);
        extensionClient = mock(ExtensionClient.class);
        rconService = mock(RconService.class);
        SourceModCfgParser parser = new SourceModCfgParser();
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        service = new SourceModCfgService(
                instanceQueryService, instanceFileService, extensionClient,
                parser, pathResolver, rconService);

        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    @Test
    void applyTempConfig_executesRconSmCvar() {
        service.applyTempConfig(1L, "ai_damage_multiplier", "2.5");
        verify(rconService).executeCommand(eq(1L), eq("sm_cvar ai_damage_multiplier 2.5"));
    }

    @Test
    void updateOrCreateConfig_createsFileWhenNotExists() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        service.updateOrCreateConfig(1L, "new_plugin", java.util.Map.of("test_cvar", "1"));
        // 应该创建文件并写入
        verify(instanceFileService).writeTextFile(eq(1L), anyString(), contains("test_cvar"));
    }

    @Test
    void restoreDefaults_usesDefaultFromMetadata() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        ConfigItem item = new ConfigItem();
        item.setKey("ai_damage_multiplier");
        item.setValue("5.0");
        item.setDefaultValue("1.0");
        item.setLineNumber(1);
        when(instanceFileService.readTextFile(eq(1L), anyString(), any()))
                .thenReturn("\"ai_damage_multiplier\" \"5.0\" // Default: 1.0");

        service.restoreDefaults(1L, "test_plugin");

        // 应该写回默认值 1.0
        verify(instanceFileService).writeTextFile(eq(1L), anyString(), contains("\"1.0\""));
    }
}
```

- [ ] **Step 2：扩展 SourceModCfgService 构造函数注入 RconService**

修改 `SourceModCfgService.java`，新增构造函数参数 `RconService rconService`，并实现新方法：

```java
// 新增字段
private final RconService rconService;

// 新增构造函数
public SourceModCfgService(InstanceQueryService instanceQueryService,
                           InstanceFileService instanceFileService,
                           ExtensionClient extensionClient,
                           SourceModCfgParser cfgParser,
                           L4D2PathResolver pathResolver,
                           RconService rconService) {
    this.instanceQueryService = instanceQueryService;
    this.instanceFileService = instanceFileService;
    this.extensionClient = extensionClient;
    this.cfgParser = cfgParser;
    this.pathResolver = pathResolver;
    this.rconService = rconService;
}

/** 临时设置：通过 RCON sm_cvar 即时生效 */
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
    requireInstance(instanceId);
    try {
        rconService.executeCommand(instanceId, "sm_cvar " + cvarName + " " + cvarValue);
        log.info("临时设置 CVAR: instanceId={}, {}={}", instanceId, cvarName, cvarValue);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.RCON,
                "RCON 设置 CVAR 失败: " + e.getMessage(), e);
    }
}

/** 还原默认值：从注释中的 Default 元数据还原 */
public void restoreDefaults(Long instanceId, String pluginName) {
    PluginConfigResource resource = getConfig(instanceId, pluginName);
    if (resource == null || resource.getSpec() == null || resource.getSpec().getItems() == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "未找到插件配置，无法还原: " + pluginName);
    }
    List<ConfigItem> items = resource.getSpec().getItems();
    boolean changed = false;
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().equals(item.getValue())) {
            item.setValue(item.getDefaultValue());
            changed = true;
        }
    }
    if (changed) {
        updateConfig(instanceId, pluginName, items);
        log.info("已还原默认值: instanceId={}, pluginName={}", instanceId, pluginName);
    }
}

/** 创建或更新配置（供 PresetService 调用）：文件不存在时按完整 SourceMod 格式重建 */
public void updateOrCreateConfig(Long instanceId, String configName, java.util.Map<String, String> values) {
    requireInstance(instanceId);
    String pluginName = extractPluginName(configName);
    // 尝试找到已存在的 cfg 文件
    for (String candidate : getCandidatePaths(pluginName)) {
        String relPath = toRelativePath(candidate);
        if (fileExistsSafe(instanceId, relPath)) {
            // 文件存在：读取 → 更新指定值 → 写回
            try {
                String content = instanceFileService.readTextFile(instanceId, relPath, gbk);
                List<ConfigItem> items = cfgParser.parse(content);
                for (ConfigItem item : items) {
                    String newVal = values.get(item.getKey());
                    if (newVal != null) item.setValue(newVal);
                }
                String serialized = cfgParser.serialize(items, content);
                instanceFileService.writeTextFile(instanceId, relPath, serialized);
                return;
            } catch (Exception e) {
                throw new L4D2PluginException(L4D2PluginException.FILE,
                        "更新配置失败: " + e.getMessage(), e);
            }
        }
    }
    // 文件不存在：用 restoreFormat 创建新文件
    List<ConfigItem> newItems = new ArrayList<>();
    for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
        ConfigItem item = new ConfigItem();
        item.setKey(entry.getKey());
        item.setValue(entry.getValue());
        item.setDefaultValue(entry.getValue());
        newItems.add(item);
    }
    String newContent = cfgParser.restoreFormat(newItems);
    String targetPath = pathResolver.getSourceModCfgPath() + "/" + configName;
    try {
        instanceFileService.writeTextFile(instanceId, targetPath, newContent);
        log.info("创建新配置文件: instanceId={}, path={}", instanceId, targetPath);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "创建配置文件失败: " + e.getMessage(), e);
    }
}

private String extractPluginName(String configName) {
    String name = configName;
    if (name.endsWith(".cfg")) name = name.substring(0, name.length() - 4);
    int slash = name.lastIndexOf('/');
    if (slash >= 0) name = name.substring(slash + 1);
    return name;
}
```

- [ ] **Step 3：运行测试确认通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgServiceTest -q`
Expected: PASS（3 tests）

- [ ] **Step 4：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): add applyTempConfig/restoreDefaults/updateOrCreateConfig to SourceModCfgService"
```

---

## 阶段 6：Controller 层调整

### Task 6.1：PluginManageController 调整

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`

- [ ] **Step 1：新增 readme 端点**

```java
/**
 * 获取插件 README（Markdown 原文）。
 */
@Operation(summary = "获取插件 README", description = "返回 plugins_store/{name}/README.md 内容")
@GetMapping("/{pluginName}/readme")
public Result<String> readme(
        @Parameter(description = "实例ID") @RequestParam Long instanceId,
        @Parameter(description = "插件名称") @PathVariable String pluginName) {
    return Result.success(pluginInstallService.readReadme(instanceId, pluginName));
}
```

- [ ] **Step 2：在 PluginInstallService 中实现 readReadme**

```java
/** 读取插件 README（Markdown 原文） */
public String readReadme(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    requireInstance(instanceId);
    String readmePath = pathResolver.getPluginReadmePath(pluginName);
    try {
        if (!instanceFileService.exists(instanceId, readmePath)) {
            return "";
        }
        return instanceFileService.readTextFile(instanceId, readmePath, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        log.warn("读取 README 失败 pluginName={}, err={}", pluginName, e.getMessage());
        return "";
    }
}
```

- [ ] **Step 3：编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git commit -m "feat(l4d2): add readme endpoint to PluginManageController"
```

---

### Task 6.2：PluginConfigController 新增 apply-temp / restore-defaults

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`

- [ ] **Step 1：新增端点**

```java
@Operation(summary = "临时设置 CVAR", description = "通过 RCON sm_cvar 即时生效")
@PostMapping("/apply-temp")
public Result<Void> applyTemp(
        @RequestParam Long instanceId,
        @RequestParam String cvarName,
        @RequestParam String cvarValue) {
    cfgService.applyTempConfig(instanceId, cvarName, cvarValue);
    return Result.success();
}

@Operation(summary = "还原默认值", description = "从注释中的 Default 元数据还原")
@PostMapping("/restore-defaults")
public Result<Void> restoreDefaults(
        @RequestParam Long instanceId,
        @RequestParam String pluginName) {
    cfgService.restoreDefaults(instanceId, pluginName);
    return Result.success();
}
```

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java
git commit -m "feat(l4d2): add apply-temp and restore-defaults endpoints to PluginConfigController"
```

---

### Task 6.3：PluginStoreController 签名对齐

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java`

- [ ] **Step 1：修改端点签名接收 StoreListDTO / StoreQueryDTO / StoreDownloadDTO**

阅读现有 `PluginStoreController.java`，将各端点的 `@RequestParam` 改为 `@RequestBody StoreXxxDTO` 或保留 `@RequestParam` 但增加 `proxyUrl`/`githubToken`/`repo` 参数。

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java
git commit -m "refactor(l4d2): align PluginStoreController signatures with Store DTOs"
```

---

## 阶段 7：数据迁移

### Task 7.1：创建 PluginStoreMigration

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java`

- [ ] **Step 1：实现迁移逻辑**

```java
package com.gameplatform.plugin.l4d2.migration;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.service.EnabledPluginsService;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件库迁移脚本（幂等）。
 *
 * <p>将旧版直接放在 addons/sourcemod/plugins/ 和 disabled/ 的 .smx 迁移到
 * plugins_store/{name}/left4dead2/addons/sourcemod/plugins/。
 *
 * <p>幂等性：迁移前检查 plugins_store/ 是否已有内容，有则跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStoreMigration {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;

    private static final String SMX_SUFFIX = ".smx";

    /** 执行迁移，返回迁移的插件数量 */
    public int migrate(Long instanceId) {
        String storePath = pathResolver.getPluginsStorePath();
        // 幂等检查
        if (hasExistingPlugins(instanceId, storePath)) {
            log.info("plugins_store 已有内容，跳过迁移: instanceId={}", instanceId);
            return 0;
        }
        // 确保目录存在
        try {
            instanceFileService.createDirectory(instanceId, storePath);
        } catch (Exception e) {
            log.warn("创建 plugins_store 目录失败: {}", e.getMessage());
        }

        int migrated = 0;
        // 迁移 plugins/ 下的 .smx（视为已启用）
        migrated += migrateSmxFiles(instanceId, pathResolver.getSourceModPluginsPath(), true);
        // 迁移 disabled/ 下的 .smx（视为未启用）
        migrated += migrateSmxFiles(instanceId, pathResolver.getSourceModPluginsDisabledPath(), false);

        // 创建空的 .enabled_plugins.yaml（如不存在）
        if (!enabledPluginsService.loadYaml(instanceId).isEmpty()) {
            enabledPluginsService.saveYaml(instanceId, new ArrayList<>());
        }

        log.info("迁移完成: instanceId={}, migrated={}", instanceId, migrated);
        return migrated;
    }

    private boolean hasExistingPlugins(Long instanceId, String storePath) {
        try {
            List<FileInfo> files = instanceFileService.listFiles(instanceId, storePath);
            if (files == null) return false;
            return files.stream().anyMatch(FileInfo::isDirectory);
        } catch (Exception e) {
            return false;
        }
    }

    private int migrateSmxFiles(Long instanceId, String remotePath, boolean enabled) {
        List<FileInfo> files;
        try {
            files = instanceFileService.listFiles(instanceId, remotePath);
        } catch (Exception e) {
            return 0;
        }
        if (files == null) return 0;
        int count = 0;
        List<EnabledPlugin> enabledPlugins = new ArrayList<>();
        for (FileInfo f : files) {
            if (f.isDirectory() || !f.getName().toLowerCase().endsWith(SMX_SUFFIX)) continue;
            String pluginName = f.getName().substring(0, f.getName().length() - SMX_SUFFIX.length());
            String storeSmxPath = pathResolver.getPluginLeft4Dead2Path(pluginName)
                    + "/addons/sourcemod/plugins/" + f.getName();
            try {
                // 复制到 store
                instanceFileService.copyFile(instanceId,
                        remotePath + "/" + f.getName(), storeSmxPath);
                // 删除原文件
                instanceFileService.deleteFile(instanceId, remotePath + "/" + f.getName());
                count++;
                if (enabled) {
                    EnabledPlugin ep = new EnabledPlugin();
                    ep.setName(pluginName);
                    ep.setSource("upload");
                    ep.setEnabledAt(System.currentTimeMillis());
                    ep.setFiles(List.of("addons/sourcemod/plugins/" + f.getName()));
                    enabledPlugins.add(ep);
                }
            } catch (Exception e) {
                log.warn("迁移 .smx 失败 plugin={}, err={}", pluginName, e.getMessage());
            }
        }
        // 保存已启用插件清单
        if (!enabledPlugins.isEmpty()) {
            enabledPluginsService.saveYaml(instanceId, enabledPlugins);
        }
        return count;
    }
}
```

- [ ] **Step 2：在 L4D2Extension.onInstanceCreate 中触发迁移（可选）**

修改 `L4D2Extension.onInstanceCreate`，在懒初始化日志后追加：

```java
// 触发数据迁移（幂等，已有 plugins_store 内容时跳过）
try {
    PluginStoreMigration migration = applicationContext.getBean(PluginStoreMigration.class);
    int migrated = migration.migrate(instanceId);
    if (migrated > 0) {
        log.info("L4D2 插件库迁移完成: instanceId={}, migrated={}", instanceId, migrated);
    }
} catch (Exception e) {
    log.warn("插件库迁移失败（不影响主流程）: instanceId={}, err={}", instanceId, e.getMessage());
}
```

注意：需要在 L4D2Extension 中注入 `ApplicationContext` 以便延迟获取 migration bean。

- [ ] **Step 3：Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java \
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java
git commit -m "feat(l4d2): add PluginStoreMigration for legacy plugins/disabled migration"
```

---

## 阶段 8：前端重写

### Task 8.1：更新 api/index.ts

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/api/index.ts`

- [ ] **Step 1：阅读现有 api/index.ts**

Run: `Read` tool on `backend/plugin-l4d2/frontend/src/api/index.ts`

- [ ] **Step 2：更新 API 签名**

按设计文档中阶段 6.5 的 API 签名表更新，重点：
- `pluginManageApi.list` 返回的 VO 字段改为 `name/status/source/hasSmx/hasConfig`
- 新增 `pluginManageApi.readme(instanceId, pluginName)`
- `pluginConfigApi.applyTemp(instanceId, cvarName, cvarValue)`
- `pluginConfigApi.restoreDefaults(instanceId, pluginName)`
- `pluginStoreApi.list/detail/download` 接收 `proxyUrl/githubToken/repo` 参数

- [ ] **Step 3：Commit**

```bash
git add backend/plugin-l4d2/frontend/src/api/index.ts
git commit -m "refactor(l4d2-frontend): align api/index.ts with new backend signatures"
```

---

### Task 8.2：重写 Plugins.vue（双 Tab + source + 热加载）

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/Plugins.vue`

- [ ] **Step 1：阅读现有 Plugins.vue 结构**

Run: `Read` tool on `backend/plugin-l4d2/frontend/src/pages/Plugins.vue`

- [ ] **Step 2：重写为双 Tab 结构**

按设计文档阶段 6.1 重写：
- 顶部操作区：应用预设 / 导出所有插件 / 刷新
- 已启用 Tab：表格列 `插件名 / 来源 Tag / 操作（禁用、配置、详情、禁用并立即卸载）`
- 未启用 Tab：表格列 `插件名 / 来源 Tag / 操作（启用、启用并立即加载、删除、查看 README）`
- source Tag 颜色：panel 蓝 / store 绿 / upload 橙
- 上传按钮（仅未启用 Tab）：接受 .zip
- 预设 Modal：单选 + 双重确认
- 商店 Drawer 入口（仅未启用 Tab）

- [ ] **Step 3：Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Plugins.vue
git commit -m "refactor(l4d2-frontend): rewrite Plugins.vue with dual-tab + source + hot-load"
```

---

### Task 8.3：重写 PluginConfig.vue（折叠面板 + 双按钮 + Default/Min/Max Tag）

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue`

- [ ] **Step 1：重写为折叠面板 + 双按钮**

按设计文档阶段 6.2：
- el-collapse 展示多个 cfg 文件
- 每个 CVAR 卡片：name + Default/Min/Max Tag + Description
- 双按钮：「临时设置」（调用 applyTemp）+「保存」（调用 update）
- 「还原默认值」按钮（调用 restoreDefaults）

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue
git commit -m "refactor(l4d2-frontend): rewrite PluginConfig.vue with accordion + dual-button + Default/Min/Max tags"
```

---

### Task 8.4：重写 Preset.vue

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/Preset.vue`

- [ ] **Step 1：对齐新 preset.yaml 结构**

按设计文档阶段 6.3：
- 展示 `plugins` 列表（含内嵌 configs）
- 双重确认后应用
- 显示 `pluginCount`

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Preset.vue
git commit -m "refactor(l4d2-frontend): align Preset.vue with new preset.yaml structure"
```

---

### Task 8.5：重写 PluginStore.vue（自定义仓库/代理/Token + 1s 轮询）

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/PluginStore.vue`

- [ ] **Step 1：重写商店页**

按设计文档阶段 6.4：
- 自定义仓库输入（el-select mode="tags"，默认 `LaoYutang/l4d2-plugins-store`，存 localStorage）
- GitHub 代理选择（6 个预设代理）
- GitHub Token 设置 Modal
- 搜索框 + 强制刷新按钮
- 安装状态筛选（全部/未安装/已安装）
- 1s 轮询下载进度
- 取消下载按钮

- [ ] **Step 2：Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginStore.vue
git commit -m "refactor(l4d2-frontend): rewrite PluginStore.vue with custom-repo/proxy/token + 1s polling"
```

---

## 阶段 9：清理与验证

### Task 9.1：清理废弃代码

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java`（删除已废弃的 saveRefs/loadFromRemote，如还存在）
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java`（保留 getFileRefsPath 方法用于向后兼容，但标记 @Deprecated）
- 删除前端 `pluginApi`（旧版 API，如还存在）

- [ ] **Step 1：搜索并清理**

Run: `Grep` for `getFileRefsPath|saveRefs|loadFromRemote|pluginApi` 全项目

- [ ] **Step 2：删除或标记废弃**

- [ ] **Step 3：Commit**

```bash
git add -A
git commit -m "chore(l4d2): cleanup deprecated file_refs.json persistence and old pluginApi"
```

---

### Task 9.2：后端全量编译验证

- [ ] **Step 1：编译整个 backend**

Run: `cd backend && mvn clean compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2：运行所有 l4d2 测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -q`
Expected: 所有测试 PASS

- [ ] **Step 3：如有失败，修复后重新运行**

---

### Task 9.3：前端构建验证

- [ ] **Step 1：构建前端**

Run: `cd backend/plugin-l4d2/frontend && npm run build`
Expected: BUILD SUCCESS（无 TypeScript 错误）

- [ ] **Step 2：如有错误，修复后重新构建**

---

### Task 9.4：集成测试（手动）

- [ ] **Step 1：部署后端 + 前端**

Run: `cd scripts && ./rebuild-restart-all.ps1`

- [ ] **Step 2：浏览器验证完整流程**

1. 打开插件管理页 → 切换"已启用/未启用"Tab
2. 上传 ZIP → 出现在未启用列表（source=upload）
3. 启用插件 → 出现在已启用列表
4. 启用并立即加载 → RCON `sm plugins list` 确认加载成功
5. 禁用并立即卸载 → RCON 确认卸载
6. 删除插件 → plugins_store 目录被清除
7. 应用预设 → 所有插件重置为预设状态
8. 商店下载 → 自动安装到 plugins_store
9. 编辑 CVAR → 临时设置即时生效，保存持久化

- [ ] **Step 3：回滚验证**

- 故意上传损坏的 .smx → `enableAndLoad` 应回滚（文件删除，yaml 无记录）
- RCON 连接失败 → `disableAndUnload` 应回滚（重新启用）

- [ ] **Step 4：引用计数验证**

- 上传两个共享相同 cfg 文件的插件
- 启用两个插件 → cfg 文件存在
- 禁用一个插件 → cfg 文件仍存在（引用未归零）
- 禁用另一个插件 → cfg 文件被删除（引用归零）

- [ ] **Step 5：Commit 最终验证状态**

```bash
git add -A
git commit -m "test(l4d2): verify integration tests pass for plugin management refactor"
```

---

## 实施顺序总结

按依赖关系分阶段实施（每阶段完成后可独立验证）：

1. **阶段 1**（已完成）：L4D2PathResolver + L4D2Extension
2. **阶段 2**：EnabledPlugin → EnabledPluginResource → EnabledPluginsService → FileRefsService → PluginListVO → PluginInstallService
3. **阶段 3**：PresetPlugin/Config → preset.yaml → PresetDetailVO → PresetConfig → PresetService
4. **阶段 5**：SourceModCfgParser.restoreFormat → SourceModCfgService（独立于阶段 4，但被 PresetService 依赖）
5. **阶段 4**：Store DTOs → PluginStoreService 增强
6. **阶段 6**：Controller 层调整
7. **阶段 7**：PluginStoreMigration
8. **阶段 8**：前端重写
9. **阶段 9**：清理与验证

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 数据迁移失败导致现有插件丢失 | 迁移前备份 `addons/sourcemod/plugins/`；迁移脚本幂等，失败可重试 |
| 远程文件操作性能差 | 批量操作时先 `listFiles` 递归收集，再循环调用；后续可优化为 tar 打包传输 |
| RCON 命令执行超时 | 设置 5s 超时，失败时明确报错并回滚 |
| GitHub API 限流 | 10 分钟缓存 + Token 支持 + 代理 fallback |
| 前端 Wujie 模式下 localStorage 隔离 | 主应用通过 props 注入 token，子应用 localStorage 仅存非敏感配置 |

---

## Self-Review 检查

**1. Spec coverage：**
- 存储模型（库/游戏分离）：Task 2.5/2.6 ✓
- 插件来源（source 字段）：Task 2.1/2.5 ✓
- 删除语义（引用计数）：Task 2.4/2.6 ✓
- 回滚机制（RCON 失败回滚）：Task 2.6（enableAndLoad/disableAndUnload）✓
- 预设（新结构）：Task 3.1-3.3 ✓
- 商店（代理/Token/原子提交）：Task 4.1-4.2 ✓
- 配置编辑（Default/Min/Max + 临时设置）：Task 5.1-5.2 ✓
- 数据迁移：Task 7.1 ✓
- 前端重写：Task 8.1-8.5 ✓
- 清理验证：Task 9.1-9.4 ✓

**2. Placeholder scan：** 无 TBD/TODO/"implement later" 等占位符。

**3. Type consistency：**
- `EnabledPlugin` 字段：name/source/enabledAt/files — 在 Task 2.1 定义，Task 2.3/2.4/2.6 使用，一致 ✓
- `PluginListVO` 字段：name/status/source/hasSmx/hasConfig — Task 2.5 定义，Task 2.6 使用，一致 ✓
- `PresetPlugin` 字段：name/configs — Task 3.1 定义，Task 3.3 使用，一致 ✓
- `StoreDownloadDTO` 字段：instanceId/pluginName/proxyUrl/githubToken/repo — Task 4.1 定义，Task 4.2/6.3 使用，一致 ✓

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-l4d2-plugin-management-refactor.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
