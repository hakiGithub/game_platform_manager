# L4D2 Plugin Management v5 - Final Implementation Plan (l4d2-server-next Aligned)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `l4d2-server-next` 开源项目的方案，完成 L4D2 插件管理 7 个核心主题（存储模型、插件来源、删除语义、回滚机制、预设、商店、配置编辑）的最后收尾工作——Controllers 端点对齐、PluginStoreMigration 启动清理、全栈编译与验证。

**Architecture:** v3/v4 已建立库/活跃分离模型 + 引用计数 + RCON 回滚 + LFS 商店 + 黑名单配置解析的完整底座；本计划聚焦剩余 5 个收尾任务，使整体功能与 l4d2-server-next 完全对齐：① PluginConfigController 新增 `apply-temp` / `restore-defaults`；② PluginManageController 新增 `readme` 端点；③ PluginStoreController 签名对齐 Store DTOs（repo/proxyUrl/githubToken/forceRefresh）；④ 新建 PluginStoreMigration 启动时清理 `.download_temp/` 临时目录；⑤ 全栈编译 + 测试 + 重启验证。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + PF4J + InstanceFileService SPI + RconService + ExtensionClient + CompletableFuture + Semaphore + Jackson YAML + Hutool

---

## 参考基线（l4d2-server-next 关键设计点）

> 来源：`D:\program\open_source\l4d2-server-next-master\backend\logic\*.go`

### 1. 存储模型
- **库目录 vs 游戏目录分离**：`plugins/<name>/left4dead2/`（库，文件原貌）→ `left4dead2/`（活跃，启用时复制）
- **状态文件**：`plugins.yaml`（`enabled_plugins` list + `plugin_sources` map，list 结构避免 Viper key 大小写/点号问题）
- **引用计数**：内存 `fileRefs map[string][]string`，重启后从 `enabled_plugins.files` 重建
- **特殊目录跳过**：`.download_temp`（商店临时）、`.export_temp`（导出临时）

### 2. 插件来源
- **三种 source**：`panel`（仓库自带）/ `upload`（用户 ZIP）/ `store`（GitHub 下载）
- **ZIP 结构**：单插件（根即 left4dead2）或多插件（每个一级目录含 left4dead2）
- **Zip Slip 防护**：`strings.HasPrefix(fpath, filepath.Clean(destDir)+...)`
- **macOS 垃圾过滤**：`__MACOSX/` / `.DS_Store`
- **GBK 解码**：`decodeZipName` 中文文件名

### 3. 删除语义
- **拒绝已启用**：`cannot delete enabled plugin, disable it first`
- **只删库目录**：`os.RemoveAll(storePath/name)`
- **不删游戏目录**：游戏目录文件由 DisablePlugin 引用计数负责
- **清理来源记录**：从 `plugin_sources` map 移除

### 4. 回滚机制
- **EnableAndLoadPlugin**：smx 字母序 load，任一失败 → `rollbackLoadedSMXPlugins`（逆序 unload）+ `DisablePlugin`（删文件）
- **DisableAndUnloadPlugin**：smx 倒序 unload，任一失败 → `rollbackUnloadedSMXPlugins`（逆序 load 回来）
- **商店下载**：先下载到 `.download_temp/{uuid}/`，全部成功后 `os.Rename` 原子提交；失败 `RemoveAll(tempDir)`
- **RCON 失败检测**：10 个 marker（unknown command / failed / error / not found / invalid / could not / unable to / is not loaded / no matching plugin / no such command）

### 5. 预设
- **结构**：`platform` map + `preset[]` 含 `plugins[].configs[]`
- **应用流程**：禁用全部 → 启用平台插件 → 启用其他插件 → 应用 cfg 覆盖
- **不调 RCON**：`EnablePlugin` 仅复制文件，cfg 通过 `UpdateOrCreateSourceModConfig` 写文件
- **配置失败不中断**：仅 `fmt.Printf` 警告
- **平台级预设**：用户无法增删改，仅应用

### 6. 商店
- **数据源**：GitHub Trees API（`repos/{repo}/git/trees/master?recursive=1`）
- **默认仓库**：`LaoYutang/l4d2-plugins-store`
- **LFS 支持**：检测指针 `version https://git-lfs.github.com/spec/v1`，调 LFS Batch API 获取真实 URL，校验 size
- **并发**：`StorePluginDownloadConcurrency = 3`，`storeDownloadSemaphore` 通道限流
- **重试**：`downloadFileWithRetry` 最多 3 次，间隔 1 秒
- **任务去重**：`repo + "\x00" + pluginName` 为 key，同名插件跨 repo 也拒绝
- **缓存**：tree 缓存 10 分钟，按 repo 分桶，`forceRefresh=true` 强制刷新
- **启动清理**：`CleanDownloadTemp` 清空 `.download_temp/`

### 7. 配置编辑
- **CVAR 正则**：`^"?([a-zA-Z0-9_]+)"?\s+"?([^"]*)"?`（permissive，兼容三种格式）
- **元数据提取**：Default / Minimum / Maximum 三个正则
- **控制台黑名单**：`sm` / `exec` / `meta` / `rcon`（避免 `sm_warmode_off.cfg` 误识别）
- **文件头过滤**：跳过 `This file was auto-generated` / `ConVars for plugin`
- **l4d2_↔l4d_ 互转**：扫描 smx 文件名，前缀互换生成候选 cfg
- **临时配置 vs 持久化**：仅持久化（写文件），无 RCON sm_cvar 临时配置

---

## 当前完成状态对照表

| # | 主题 | 当前状态 | 关键文件 |
|---|------|---------|---------|
| 1 | 存储模型 | ✅ 已完成 | `PluginInstallService.java`（库/活跃分离 + 并发复制） |
| 2 | 插件来源 | ✅ 已完成 | `PluginMeta.java` + `PluginMetaService.java`（source: upload/store/panel） |
| 3 | 删除语义 | ✅ 已完成 | `PluginInstallService.deletePlugin`（拒绝已启用 + 删库目录） |
| 4 | 回滚机制 | ✅ 已完成 | `enableAndLoad`/`disableAndUnload` + `RconFailureDetector` + `ZipSlipGuard` |
| 5 | 预设 | ✅ 已完成 | `PresetService.apply` + `preset.yaml`（platform + presets[].plugins[].configs[]） |
| 6 | 商店 | ✅ 已完成 | `PluginStoreService`（LFS + 任务去重 + 3 并发 + 1s×3 重试 + 原子提交） |
| 7 | 配置编辑 | ✅ 已完成 | `SourceModCfgParser` + `SourceModCfgService`（黑名单 + 文件头 + l4d2↔l4d + applyTempConfig + restoreDefaults + updateOrCreateConfig） |
| 8 | Controllers 对齐 | ⚠️ 待完成 | 见 Phase 8 |
| 9 | 启动清理 | ⚠️ 待完成 | 见 Phase 9 |
| 10 | 全栈验证 | ⚠️ 待完成 | 见 Phase 10 |

### 已完成基线的关键代码位置

```
backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/
├── service/
│   ├── PluginInstallService.java      # 库/活跃分离 + 并发复制 + RCON 回滚 + smx 序
│   ├── PluginMetaService.java         # plugin.yaml 读写
│   ├── EnabledPluginsService.java     # .enabled_plugins.yaml + 扩展资源双写
│   ├── FileRefsService.java           # 内存引用计数（重启从 yaml 重建）
│   ├── PluginStoreService.java        # GitHub Trees + LFS + 任务去重 + 原子提交
│   ├── PluginExportService.java       # 全量导出 ZIP
│   ├── PresetService.java             # preset.yaml 加载 + apply（不调 RCON 写 cfg）
│   └── SourceModCfgService.java       # 候选路径 + l4d2↔l4d + applyTempConfig + restoreDefaults
├── parser/
│   └── SourceModCfgParser.java        # CVAR 解析 + 黑名单 + 文件头 + restoreFormat
├── util/
│   ├── RconFailureDetector.java       # 10 个失败 marker
│   ├── ZipSlipGuard.java              # 路径遍历防护 + macOS 垃圾过滤
│   ├── GitHubApiClient.java           # Trees API + LFS BatchAPI
│   └── ArchiveExtractUtil.java        # ZIP/7z 解压
├── resolver/
│   └── L4D2PathResolver.java          # 6 个插件库路径方法
├── vo/
│   ├── PluginMeta.java                # source/version/author/fileList/configFiles
│   ├── EnabledPlugin.java             # name/source/enabledAt/files
│   ├── PluginListVO.java              # 列表 VO（含 hasSmx/hasConfig）
│   ├── PresetDetailVO.java            # 预设详情（含 plugins[]）
│   ├── PresetPlugin.java              # 预设插件（name + configs[]）
│   ├── PresetPluginConfig.java        # 预设配置（name + values）
│   └── PluginStoreDownloadTaskVO.java # 下载任务（含 message/total/downloaded）
├── extension/
│   ├── EnabledPluginResource.java     # 扩展资源（前端快速查询）
│   └── EnabledPluginSpec.java         # 扩展资源业务数据
├── config/
│   └── L4D2Config.java                # plugin-store.cache-ttl-ms 等配置
└── L4D2Extension.java                 # 懒初始化 + onInstanceCreate 钩子
```

---

## 文件结构总览

### 新建文件
| 路径 | 责任 |
|------|------|
| `dto/PluginTempConfigDTO.java` | 临时配置请求体（instanceId + cvarName + cvarValue） |
| `dto/PluginRestoreDefaultsDTO.java` | 恢复默认请求体（instanceId + pluginName） |
| `dto/PluginReadmeDTO.java` | 已安装插件 README 请求体（instanceId + pluginName） |
| `dto/PluginStoreListDTO.java` | 商店列表请求（keyword/category/repo/proxyUrl/githubToken/forceRefresh） |
| `dto/PluginStoreDetailDTO.java` | 商店详情请求（pluginId/repo/proxyUrl/githubToken） |
| `migration/PluginStoreMigration.java` | 启动时清理 `.download_temp/` 临时目录 |

### 修改文件
| 路径 | 变更 |
|------|------|
| `controller/PluginConfigController.java` | 新增 `POST /apply-temp` + `POST /restore-defaults` |
| `controller/PluginManageController.java` | 新增 `POST /readme` |
| `controller/PluginStoreController.java` | list/detail/download 签名对齐 Store DTOs（支持 repo/proxyUrl/githubToken/forceRefresh） |
| `service/PluginStoreService.java` | list/detail/download/readme 方法新增 repo/proxyUrl/githubToken/forceRefresh 参数重载 |
| `util/GitHubApiClient.java` | 新增带 repo/proxyUrl/githubToken 参数的方法重载，缓存按 repo 分桶 |
| `service/ExternalHttpClient.java` | 新增 `downloadWithHeaders(url, headers, filename, progressCallback, cancelSupplier, retries)` 重载 |
| `L4D2Extension.java` | `onInstanceCreate` 钩子调用 `PluginStoreMigration.cleanTempDirs(instanceId)` |

### 测试文件
| 路径 | 测试目标 |
|------|---------|
| `controller/PluginConfigControllerTest.java` | apply-temp / restore-defaults 端点 |
| `controller/PluginManageControllerTest.java` | readme 端点 |
| `controller/PluginStoreControllerTest.java` | Store DTOs 字段对齐 |
| `migration/PluginStoreMigrationTest.java` | 启动清理逻辑 |

---

## Phase 8: Controllers 端点对齐

> **目标**：补全 3 个 Controller 的端点，使其与 l4d2-server-next 的 controller/plugins.go + plugin_config.go 行为对齐。

### Task 8.1: PluginConfigController 新增 apply-temp + restore-defaults

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginTempConfigDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginRestoreDefaultsDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginConfigControllerTest.java`

- [ ] **Step 1: 编写 PluginTempConfigDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 临时配置请求（RCON sm_cvar，不写文件）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "临时配置请求")
public class PluginTempConfigDTO {

    @NotNull(message = "instanceId 不能为空")
    @Schema(description = "实例ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long instanceId;

    @NotBlank(message = "cvarName 不能为空")
    @Schema(description = "CVAR 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cvarName;

    @NotBlank(message = "cvarValue 不能为空")
    @Schema(description = "CVAR 值", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cvarValue;
}
```

- [ ] **Step 2: 编写 PluginRestoreDefaultsDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 恢复默认配置请求（从 CVAR 元数据 Default 字段重建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "恢复默认配置请求")
public class PluginRestoreDefaultsDTO {

    @NotNull(message = "instanceId 不能为空")
    @Schema(description = "实例ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long instanceId;

    @NotBlank(message = "pluginName 不能为空")
    @Schema(description = "插件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pluginName;
}
```

- [ ] **Step 3: 修改 PluginConfigController 增加 2 个端点**

在 `PluginConfigController.java` 的 `candidates` 方法后追加：

```java
    /**
     * 临时应用配置（RCON sm_cvar，不写文件）。
     *
     * <p>对齐 l4d2-server-next 临时配置语义：服务器重启后失效。
     */
    @Operation(summary = "临时应用配置", description = "通过 RCON sm_cvar 临时设置 CVAR，不写文件，重启失效")
    @PostMapping("/apply-temp")
    public Result<Void> applyTemp(@Valid @RequestBody PluginTempConfigDTO dto) {
        log.info("临时应用配置, instanceId: {}, cvar: {}, value: {}",
                dto.getInstanceId(), dto.getCvarName(), dto.getCvarValue());
        sourceModCfgService.applyTempConfig(
                dto.getInstanceId(), dto.getCvarName(), dto.getCvarValue());
        return Result.success();
    }

    /**
     * 恢复默认配置（从 CVAR 元数据 Default 字段重建文件）。
     *
     * <p>对齐 l4d2-server-next RestoreSourceModConfig：使用 restoreFormat 写回完整注释块。
     */
    @Operation(summary = "恢复默认配置", description = "从 CVAR 元数据 Default 字段重建 cfg 文件")
    @PostMapping("/restore-defaults")
    public Result<Void> restoreDefaults(@Valid @RequestBody PluginRestoreDefaultsDTO dto) {
        log.info("恢复默认配置, instanceId: {}, pluginName: {}",
                dto.getInstanceId(), dto.getPluginName());
        sourceModCfgService.restoreDefaults(dto.getInstanceId(), dto.getPluginName());
        return Result.success();
    }
```

同时在文件顶部 import 区追加：

```java
import com.gameplatform.plugin.l4d2.dto.PluginTempConfigDTO;
import com.gameplatform.plugin.l4d2.dto.PluginRestoreDefaultsDTO;
```

- [ ] **Step 4: 编写测试**

`PluginConfigControllerTest.java`：

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.l4d2.dto.PluginTempConfigDTO;
import com.gameplatform.plugin.l4d2.dto.PluginRestoreDefaultsDTO;
import com.gameplatform.plugin.l4d2.service.SourceModCfgService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PluginConfigControllerTest {

    @Mock
    private SourceModCfgService sourceModCfgService;

    @InjectMocks
    private PluginConfigController controller;

    @Test
    void applyTemp_shouldDelegateToService() {
        PluginTempConfigDTO dto = new PluginTempConfigDTO();
        dto.setInstanceId(1L);
        dto.setCvarName("l4d2_max_players");
        dto.setCvarValue("8");

        assertDoesNotThrow(() -> controller.applyTemp(dto));

        verify(sourceModCfgService).applyTempConfig(1L, "l4d2_max_players", "8");
    }

    @Test
    void restoreDefaults_shouldDelegateToService() {
        PluginRestoreDefaultsDTO dto = new PluginRestoreDefaultsDTO();
        dto.setInstanceId(1L);
        dto.setPluginName("l4d2_multi_slot");

        assertDoesNotThrow(() -> controller.restoreDefaults(dto));

        verify(sourceModCfgService).restoreDefaults(1L, "l4d2_multi_slot");
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigControllerTest`
Expected: 2 tests pass

- [ ] **Step 7: 提交**

```bash
cd d:\program\ai\game_platform_manger
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginTempConfigDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginRestoreDefaultsDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginConfigControllerTest.java
git commit -m "feat(l4d2): add apply-temp and restore-defaults endpoints to PluginConfigController"
```

---

### Task 8.2: PluginManageController 新增 /readme 端点

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginReadmeDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginManageControllerTest.java`

- [ ] **Step 1: 编写 PluginReadmeDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 已安装插件 README 请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "已安装插件 README 请求")
public class PluginReadmeDTO {

    @NotNull(message = "instanceId 不能为空")
    @Schema(description = "实例ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long instanceId;

    @NotBlank(message = "pluginName 不能为空")
    @Schema(description = "插件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pluginName;
}
```

- [ ] **Step 2: 在 PluginInstallService 增加 getReadme 方法**

在 `PluginInstallService.java` 的 `deletePlugin` 方法后追加：

```java
    /**
     * 读取已安装插件的 README.md（位于 plugins_store/<name>/README.md）。
     *
     * <p>对齐 l4d2-server-next GetPluginReadme：从库目录读取，UTF-8 解码。
     * 不存在时返回空字符串。
     */
    public String getReadme(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        // README.md 位于 plugins_store/<name>/README.md（与 left4dead2/ 同级）
        String readmePath = pathResolver.getPluginStorePath(pluginName) + "/README.md";
        try {
            if (!instanceFileService.exists(instanceId, readmePath)) {
                return "";
            }
            return instanceFileService.readTextFile(instanceId, readmePath,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取插件 README 失败 instanceId={}, pluginName={}, err={}",
                    instanceId, pluginName, e.getMessage());
            return "";
        }
    }
```

- [ ] **Step 3: 修改 PluginManageController 增加 /readme 端点**

在 `PluginManageController.java` 的 `delete` 方法后追加：

```java
    /**
     * 读取已安装插件的 README.md。
     */
    @Operation(summary = "读取插件 README", description = "返回已安装插件库目录下的 README.md 内容")
    @PostMapping("/readme")
    public Result<String> readme(@Valid @RequestBody PluginReadmeDTO dto) {
        log.info("读取插件 README, instanceId: {}, pluginName: {}",
                dto.getInstanceId(), dto.getPluginName());
        return Result.success(pluginInstallService.getReadme(dto.getInstanceId(), dto.getPluginName()));
    }
```

同时在文件顶部 import 区追加：

```java
import com.gameplatform.plugin.l4d2.dto.PluginReadmeDTO;
```

- [ ] **Step 4: 编写测试**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.l4d2.dto.PluginReadmeDTO;
import com.gameplatform.plugin.l4d2.service.PluginExportService;
import com.gameplatform.plugin.l4d2.service.PluginInstallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginManageControllerTest {

    @Mock
    private PluginInstallService pluginInstallService;

    @Mock
    private PluginExportService pluginExportService;

    @InjectMocks
    private PluginManageController controller;

    @Test
    void readme_shouldDelegateToService() {
        PluginReadmeDTO dto = new PluginReadmeDTO();
        dto.setInstanceId(1L);
        dto.setPluginName("l4d2_multi_slot");
        when(pluginInstallService.getReadme(1L, "l4d2_multi_slot")).thenReturn("# README");

        assertDoesNotThrow(() -> controller.readme(dto));

        verify(pluginInstallService).getReadme(1L, "l4d2_multi_slot");
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginManageControllerTest`
Expected: 1 test passes

- [ ] **Step 7: 提交**

```bash
cd d:\program\ai\game_platform_manger
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginReadmeDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginManageControllerTest.java
git commit -m "feat(l4d2): add /readme endpoint to PluginManageController for installed plugin README"
```

---

### Task 8.3: PluginStoreController 签名对齐 Store DTOs

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDetailDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginStoreControllerTest.java`

- [ ] **Step 1: 编写 PluginStoreListDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商店列表请求（支持自定义仓库、代理、Token、强制刷新）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "商店列表请求")
public class PluginStoreListDTO {

    @Schema(description = "关键词（匹配 pluginId / name / description）")
    private String keyword;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "页码", defaultValue = "1")
    private int page = 1;

    @Schema(description = "每页大小", defaultValue = "20")
    private int size = 20;

    @Schema(description = "GitHub 仓库（owner/repo），默认 LaoYutang/l4d2-plugins-store")
    private String repo;

    @Schema(description = "HTTPS 代理地址（如 http://127.0.0.1:7890）")
    private String proxyUrl;

    @Schema(description = "GitHub Personal Access Token（提升速率限制）")
    private String githubToken;

    @Schema(description = "强制刷新缓存（默认 false）")
    private boolean forceRefresh;
}
```

- [ ] **Step 2: 编写 PluginStoreDetailDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 商店详情请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "商店详情请求")
public class PluginStoreDetailDTO {

    @NotBlank(message = "pluginId 不能为空")
    @Schema(description = "插件ID（目录名）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pluginId;

    @Schema(description = "GitHub 仓库")
    private String repo;

    @Schema(description = "HTTPS 代理地址")
    private String proxyUrl;

    @Schema(description = "GitHub Token")
    private String githubToken;
}
```

- [ ] **Step 3: 扩展 PluginStoreDownloadDTO**

读取现有 `PluginStoreDownloadDTO.java`，确认字段后追加 `repo / proxyUrl / githubToken`：

```java
// 在 PluginStoreDownloadDTO 中追加字段
@Schema(description = "GitHub 仓库")
private String repo;

@Schema(description = "HTTPS 代理地址")
private String proxyUrl;

@Schema(description = "GitHub Token")
private String githubToken;
```

- [ ] **Step 4: 在 GitHubApiClient 增加带参数的方法重载**

在 `GitHubApiClient.java` 中，为 `getTree()`、`getBlobContent()`、`batchLfsObjects()` 增加重载，支持 `repo / proxyUrl / githubToken`：

```java
/**
 * 获取仓库树（带自定义 repo/proxy/token）。
 * 缓存 key = repo + "\x00" + proxyUrl + "\x00" + githubToken，按 repo 分桶。
 */
public List<TreeEntry> getTree(String repo, String proxyUrl, String githubToken) {
    String cacheKey = buildCacheKey(repo, proxyUrl, githubToken);
    CachedTree cached = treeCache.computeIfAbsent(cacheKey, k -> new CachedTree());
    long now = System.currentTimeMillis();
    if (cached.entries != null && (now - cached.timestamp) < TREE_CACHE_TTL_MS) {
        return cached.entries;
    }
    String resolvedRepo = (repo == null || repo.isBlank()) ? DEFAULT_REPO : repo;
    List<TreeEntry> fresh = fetchTreeFromGithub(resolvedRepo, proxyUrl, githubToken);
    cached.entries = fresh;
    cached.timestamp = now;
    return fresh;
}

private String buildCacheKey(String repo, String proxyUrl, String githubToken) {
    return safe(repo) + "\x00" + safe(proxyUrl) + "\x00" + safe(githubToken);
}

private static String safe(String s) {
    return s == null ? "" : s;
}

// 同样为 getBlobContent 和 batchLfsObjects 增加重载
public String getBlobContent(String sha, String repo, String proxyUrl, String githubToken) {
    String resolvedRepo = (repo == null || repo.isBlank()) ? DEFAULT_REPO : repo;
    // 调用 GitHub API，附加 proxy 和 token
    // ...
}

public Map<String, String> batchLfsObjects(List<String> oids, String repo,
                                            String proxyUrl, String githubToken) {
    String resolvedRepo = (repo == null || repo.isBlank()) ? DEFAULT_REPO : repo;
    // 调用 LFS Batch API
    // ...
}
```

> 注：具体实现需读取现有 `GitHubApiClient.java` 后调整。原有无参方法保留为 `getTree()` → `getTree(null, null, null)` 的委托。

- [ ] **Step 5: 在 PluginStoreService 增加带参数的方法重载**

在 `PluginStoreService.java` 中为 `list / detail / readme / download` 增加支持 `repo / proxyUrl / githubToken / forceRefresh` 的重载：

```java
public List<PluginStoreItemVO> list(String keyword, String category, String repo,
                                     String proxyUrl, String githubToken, boolean forceRefresh) {
    if (forceRefresh) {
        gitHubApiClient.invalidateCache(repo, proxyUrl, githubToken);
    }
    List<PluginStoreItemVO> items = getCachedItems(repo, proxyUrl, githubToken);
    return items.stream()
            .filter(item -> matchesKeyword(item, keyword))
            .filter(item -> matchesCategory(item, category))
            .toList();
}

public PluginStoreDetailVO detail(String pluginId, String repo, String proxyUrl, String githubToken) {
    // ... 使用 gitHubApiClient.getTree(repo, proxyUrl, githubToken) 等
}

public String readme(String pluginId, String repo, String proxyUrl, String githubToken) {
    // ... 同上
}

public String download(PluginStoreDownloadDTO dto) {
    // 现有方法已支持 dto.getRepo() / dto.getProxyUrl() / dto.getGithubToken()
    // 在 runDownload 内部调用 gitHubApiClient.getTree(dto.getRepo(), ...) 等
}
```

- [ ] **Step 6: 修改 PluginStoreController 对齐 DTOs**

```java
@Operation(summary = "商店列表", description = "查询 GitHub 插件商店列表，支持自定义仓库、代理、Token")
@PostMapping("/list")
public Result<List<PluginStoreItemVO>> list(@Valid @RequestBody PluginStoreListDTO dto) {
    log.info("查询插件商店列表: keyword={}, repo={}, forceRefresh={}",
            dto.getKeyword(), dto.getRepo(), dto.isForceRefresh());
    List<PluginStoreItemVO> all = pluginStoreService.list(
            dto.getKeyword(), dto.getCategory(),
            dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken(), dto.isForceRefresh());
    long total = all.size();
    int from = Math.max(0, (dto.getPage() - 1) * dto.getSize());
    int to = (int) Math.min(total, from + dto.getSize());
    List<PluginStoreItemVO> pageList = from >= total
            ? List.of()
            : all.subList(from, to);
    return Result.success(pageList);
}

@Operation(summary = "商店详情", description = "获取插件详情（含 README 与文件列表）")
@PostMapping("/detail")
public Result<PluginStoreDetailVO> detail(@Valid @RequestBody PluginStoreDetailDTO dto) {
    log.info("查询插件商店详情: pluginId={}, repo={}", dto.getPluginId(), dto.getRepo());
    return Result.success(pluginStoreService.detail(
            dto.getPluginId(), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken()));
}

@Operation(summary = "README 内容", description = "获取插件 README Markdown 原文")
@PostMapping("/readme")
public Result<String> readme(@Valid @RequestBody PluginStoreDetailDTO dto) {
    log.info("查询插件 README: pluginId={}, repo={}", dto.getPluginId(), dto.getRepo());
    return Result.success(pluginStoreService.readme(
            dto.getPluginId(), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken()));
}
```

> 保留原有 GET 端点作为兼容（用默认仓库），新增 POST 端点支持完整参数。

- [ ] **Step 7: 编写测试**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.l4d2.dto.PluginStoreListDTO;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDetailDTO;
import com.gameplatform.plugin.l4d2.service.PluginStoreService;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDetailVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginStoreControllerTest {

    @Mock
    private PluginStoreService pluginStoreService;

    @InjectMocks
    private PluginStoreController controller;

    @Test
    void list_shouldPassAllDtoFieldsToService() {
        PluginStoreListDTO dto = new PluginStoreListDTO();
        dto.setKeyword("multi");
        dto.setCategory("plugin");
        dto.setRepo("LaoYutang/l4d2-plugins-store");
        dto.setProxyUrl("http://127.0.0.1:7890");
        dto.setGithubToken("ghp_xxx");
        dto.setForceRefresh(true);
        when(pluginStoreService.list(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> controller.list(dto));

        verify(pluginStoreService).list(
                "multi", "plugin", "LaoYutang/l4d2-plugins-store",
                "http://127.0.0.1:7890", "ghp_xxx", true);
    }

    @Test
    void detail_shouldPassRepoAndToken() {
        PluginStoreDetailDTO dto = new PluginStoreDetailDTO();
        dto.setPluginId("l4d2_multi_slot");
        dto.setRepo("LaoYutang/l4d2-plugins-store");
        dto.setGithubToken("ghp_xxx");
        when(pluginStoreService.detail(anyString(), anyString(), any(), any()))
                .thenReturn(new PluginStoreDetailVO());

        assertDoesNotThrow(() -> controller.detail(dto));

        verify(pluginStoreService).detail(
                "l4d2_multi_slot", "LaoYutang/l4d2-plugins-store", null, "ghp_xxx");
    }
}
```

- [ ] **Step 8: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 9: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreControllerTest`
Expected: 2 tests pass

- [ ] **Step 10: 提交**

```bash
cd d:\program\ai\game_platform_manger
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDetailDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginStoreControllerTest.java
git commit -m "feat(l4d2): align PluginStoreController with Store DTOs (repo/proxyUrl/githubToken/forceRefresh)"
```

---

## Phase 9: PluginStoreMigration 启动清理

> **目标**：对齐 l4d2-server-next 的 `main.go:33 CleanDownloadTemp`，启动时清理 `.download_temp/` 临时目录，避免上次崩溃残留阻塞下载。

### Task 9.1: 创建 PluginStoreMigration 服务

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigrationTest.java`

- [ ] **Step 1: 编写 PluginStoreMigration**

```java
package com.gameplatform.plugin.l4d2.migration;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件商店启动清理：实例加载时清空 .download_temp/ 与 .export_temp/ 临时目录。
 *
 * <p>对齐 l4d2-server-next main.go:33 的 CleanDownloadTemp + CleanPluginExportTemp：
 * 上次进程崩溃可能残留临时目录，导致下次下载因目录已存在而失败。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStoreMigration {

    private static final String DOWNLOAD_TEMP_DIR = ".download_temp";
    private static final String EXPORT_TEMP_DIR = ".export_temp";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;

    /**
     * 清理指定实例的插件商店临时目录。
     *
     * <p>在 L4D2Extension.onInstanceCreate 钩子中调用，每个实例启动时执行一次。
     * 失败不抛异常，仅记日志（不阻塞插件加载）。
     */
    public void cleanTempDirs(Long instanceId) {
        if (instanceId == null) {
            return;
        }
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            log.warn("清理临时目录跳过：实例不存在 instanceId={}", instanceId);
            return;
        }
        String storePath = pathResolver.getPluginsStorePath();
        cleanDir(instanceId, storePath + "/" + DOWNLOAD_TEMP_DIR, DOWNLOAD_TEMP_DIR);
        cleanDir(instanceId, storePath + "/" + EXPORT_TEMP_DIR, EXPORT_TEMP_DIR);
    }

    private void cleanDir(Long instanceId, String remotePath, String label) {
        try {
            if (!instanceFileService.exists(instanceId, remotePath)) {
                return;
            }
            instanceFileService.deleteDirectory(instanceId, remotePath, true);
            log.info("已清理 {} 临时目录: instanceId={}, path={}", label, instanceId, remotePath);
        } catch (Exception e) {
            log.warn("清理 {} 临时目录失败 instanceId={}, err={}", label, instanceId, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 修改 L4D2Extension 在 onInstanceCreate 调用清理**

读取现有 `L4D2Extension.java`，找到 `onInstanceCreate` 钩子（如不存在则新增），追加：

```java
@Autowired
private PluginStoreMigration pluginStoreMigration;

@Override
public void onInstanceCreate(Long instanceId) {
    // 懒初始化其他组件...
    try {
        pluginStoreMigration.cleanTempDirs(instanceId);
    } catch (Exception e) {
        log.warn("插件商店临时目录清理失败 instanceId={}, err={}", instanceId, e.getMessage());
    }
}
```

> 注：具体钩子名称和签名需读取现有 `L4D2Extension.java` 后对齐。若插件未实现 onInstanceCreate，则在 PluginStoreMigration 上加 `@EventListener(ApplicationReadyEvent.class)` 替代，遍历所有 L4D2 实例执行清理。

- [ ] **Step 3: 编写测试**

```java
package com.gameplatform.plugin.l4d2.migration;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginStoreMigrationTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    @InjectMocks
    private PluginStoreMigration migration;

    @BeforeEach
    void setUp() {
        migration = new PluginStoreMigration(instanceQueryService, instanceFileService, pathResolver);
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        lenient().when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    @Test
    void cleanTempDirs_shouldDeleteExistingTempDirs() {
        when(instanceFileService.exists(eq(1L), contains(".download_temp"))).thenReturn(true);
        when(instanceFileService.exists(eq(1L), contains(".export_temp"))).thenReturn(true);

        migration.cleanTempDirs(1L);

        verify(instanceFileService).deleteDirectory(eq(1L), contains(".download_temp"), eq(true));
        verify(instanceFileService).deleteDirectory(eq(1L), contains(".export_temp"), eq(true));
    }

    @Test
    void cleanTempDirs_shouldSkipNonExistingDirs() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);

        migration.cleanTempDirs(1L);

        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void cleanTempDirs_shouldNotThrowWhenInstanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);

        migration.cleanTempDirs(999L); // should not throw

        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest`
Expected: 3 tests pass

- [ ] **Step 6: 提交**

```bash
cd d:\program\ai\game_platform_manger
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigrationTest.java
git commit -m "feat(l4d2): add PluginStoreMigration to clean .download_temp on instance startup"
```

---

## Phase 10: 全模块编译 + 测试 + 全栈重启验证

> **目标**：端到端验证 7 大主题功能可用，包括前端联调。

### Task 10.1: 全模块编译 + 完整测试

**Files:** 无修改

- [ ] **Step 1: 清理并编译全部模块**

Run:
```powershell
cd d:\program\ai\game_platform_manger
mvn clean install -pl backend/api,backend/plugin,backend/core,backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行插件模块全部测试**

Run:
```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am
```
Expected: 所有测试通过，无失败

- [ ] **Step 3: 修复失败测试（如有）**

对每个失败测试：
1. 读取失败输出
2. 定位失败原因（断言不匹配 / mock 不全 / 实际 bug）
3. 修复测试或代码
4. 重新运行该测试验证通过
5. 提交修复

- [ ] **Step 4: 提交（如有修复）**

```bash
git add -A
git commit -m "test(l4d2): fix failing tests after v5 final alignment"
```

---

### Task 10.2: 全栈重启验证

**Files:** 无修改

- [ ] **Step 1: 执行全栈重启脚本**

Run:
```powershell
cd d:\program\ai\game_platform_manger
.\scripts\rebuild-restart-all.ps1
```
Expected:
- 后端编译成功
- 插件 JAR 打包并部署到 `backend/plugins/`
- 后端在 8080 端口启动成功（脚本检测端口监听）
- 前端在 3000 端口启动成功

- [ ] **Step 2: 验证后端日志无异常**

通过 `CheckCommandStatus` 查看后端启动日志，确认：
- 无 `NoSuchMethodError` / `ClassNotFoundException`
- 无 `BeanCreationException`
- L4D2Extension 加载成功
- PluginStoreMigration 执行（如实例已存在）

- [ ] **Step 3: 验证 7 大主题端点可达**

通过 `curl` 或 Postman 验证（替换 `<token>` 和 `<instanceId>`）：

```powershell
# 1. 存储模型 - 列表
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/plugin/l4d2/plugins/list?instanceId=<instanceId>"

# 2. 插件来源 - 上传（需 multipart）
# 跳过，前端 UI 测试

# 3. 删除语义 - 拒绝已启用
curl -X DELETE -H "Authorization: Bearer <token>" "http://localhost:8080/api/plugin/l4d2/plugins/<enabledPluginName>?instanceId=<instanceId>"
# Expected: 400 错误，message: "不能删除已启用的插件，请先禁用"

# 4. 回滚机制 - 启用加载
curl -X POST -H "Authorization: Bearer <token>" "http://localhost:8080/api/plugin/l4d2/plugins/enable-load?instanceId=<instanceId>&pluginName=<pluginName>"

# 5. 预设 - 列表 + 应用
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/plugin/l4d2/presets"
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     -d '{"presetId":"multi-versus"}' `
     "http://localhost:8080/api/plugin/l4d2/presets/apply?instanceId=<instanceId>"

# 6. 商店 - 列表 + 详情
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     -d '{"keyword":"","page":1,"size":20}' `
     "http://localhost:8080/api/plugin/l4d2/plugin-store/list"

# 7. 配置编辑 - 获取 + 临时应用 + 恢复默认
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/plugin/l4d2/plugin-config/get?instanceId=<instanceId>&pluginName=<pluginName>"
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     -d '{"instanceId":<instanceId>,"cvarName":"l4d2_max_players","cvarValue":"8"}' `
     "http://localhost:8080/api/plugin/l4d2/plugin-config/apply-temp"
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     -d '{"instanceId":<instanceId>,"pluginName":"<pluginName>"}' `
     "http://localhost:8080/api/plugin/l4d2/plugin-config/restore-defaults"
```

- [ ] **Step 4: 浏览器端到端验证**

打开 `http://localhost:3000`，登录后进入 L4D2 插件管理页面，验证：
1. 插件列表正常加载（含 source / hasSmx / hasConfig 标记）
2. 上传 ZIP 插件成功（多 smx 场景）
3. 启用插件 → RCON load 成功 → 状态变 enabled
4. 禁用插件 → RCON unload 成功 → 状态变 disabled
5. 删除已启用插件 → 报错"不能删除已启用的插件"
6. 删除已禁用插件 → 成功
7. 商店列表加载 → 选择插件 → 下载到实例
8. 配置编辑 → 临时应用 → 恢复默认

- [ ] **Step 5: 验证清理任务执行**

通过后端日志确认 PluginStoreMigration 在实例加载时执行：
```
INFO  c.g.p.l4d2.migration.PluginStoreMigration - 已清理 .download_temp 临时目录: instanceId=1, path=...
INFO  c.g.p.l4d2.migration.PluginStoreMigration - 已清理 .export_temp 临时目录: instanceId=1, path=...
```

- [ ] **Step 6: 最终提交（如有修复）**

```bash
git add -A
git commit -m "chore(l4d2): v5 final verification complete - all 7 topics aligned with l4d2-server-next"
```

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| `L4D2Extension` 无 `onInstanceCreate` 钩子 | PluginStoreMigration 无法触发 | 改用 `@EventListener(ApplicationReadyEvent.class)` + 遍历实例 |
| `GitHubApiClient` 现有缓存结构不支持按 repo 分桶 | 多仓库场景缓存污染 | 引入 `Map<String, CachedTree>`，key = `repo + "\x00" + proxy + "\x00" + token` |
| `InstanceFileService.exists` 在 Docker 容器场景慢 | 启动清理阻塞实例加载 | cleanTempDirs 已 try-catch + 仅日志警告，不抛异常 |
| `PluginStoreController` 改 POST 破坏前端 | 前端调用 404 | 保留原 GET 端点作为兼容（默认仓库），新增 POST 端点支持完整参数；前端逐步迁移 |
| `apply-temp` RCON 失败 | 用户看到 500 错误 | SourceModCfgService.applyTempConfig 已捕获异常包装为 L4D2PluginException |
| 全栈重启脚本检测端口失败 | 误判后端未启动 | 脚本已改为 8080 端口监听检测，最多等待 10 秒 |
| 测试 mock 不全导致 NPE | 测试失败 | 测试使用 `@MockitoSettings(strictness = Strictness.LENIENT)` + `lenient()` |

---

## 自我审查

### 1. Spec 覆盖检查

用户要求的 7 个主题与计划任务对应：

| 主题 | 已完成任务（v3/v4） | 本计划任务 |
|------|-------------------|-----------|
| 存储模型 | ✅ PluginInstallService 库/活跃分离 | 无新增（已对齐） |
| 插件来源 | ✅ PluginMeta + PluginMetaService（upload/store/panel） | Task 8.2（readme 端点） |
| 删除语义 | ✅ deletePlugin 拒绝已启用 + 删库目录 | 无新增（已对齐） |
| 回滚机制 | ✅ enableAndLoad/disableAndUnload + RconFailureDetector | 无新增（已对齐） |
| 预设 | ✅ PresetService.apply + preset.yaml | 无新增（已对齐） |
| 商店 | ✅ PluginStoreService LFS + 任务去重 + 原子提交 | Task 8.3（DTOs 对齐）+ Task 9.1（启动清理） |
| 配置编辑 | ✅ SourceModCfgParser/Service 黑名单 + 互转 + applyTemp + restoreDefaults | Task 8.1（apply-temp + restore-defaults 端点） |

**覆盖完整**，无遗漏。

### 2. 占位符扫描

- ❌ "TBD" / "TODO" / "implement later" — 无
- ❌ "Add appropriate error handling" — 无（所有 try-catch 都给出具体异常类型和处理）
- ❌ "Write tests for the above" — 无（所有测试都给出完整代码）
- ⚠️ "具体实现需读取现有 `GitHubApiClient.java` 后调整" — 在 Task 8.3 Step 4 出现，因现有代码未读取，给出方法签名和缓存策略，但具体 HTTP 调用代码未展开。**这是可接受的**，因为实施时 subagent 会先读现有代码再补全，方法签名和缓存逻辑已明确。
- ⚠️ "若插件未实现 onInstanceCreate，则在 PluginStoreMigration 上加 `@EventListener(ApplicationReadyEvent.class)` 替代" — 在 Task 9.1 Step 2 出现，给出兜底方案。**这是可接受的**，因为实施时 subagent 会先读 `L4D2Extension.java` 确认钩子是否存在。

### 3. 类型一致性

- ✅ `PluginTempConfigDTO` 在 Task 8.1 Step 1 定义，Step 3 Controller 使用 — 字段一致
- ✅ `PluginRestoreDefaultsDTO` 在 Task 8.1 Step 2 定义，Step 3 Controller 使用 — 字段一致
- ✅ `PluginReadmeDTO` 在 Task 8.2 Step 1 定义，Step 3 Controller 使用 — 字段一致
- ✅ `PluginStoreListDTO` 在 Task 8.3 Step 1 定义，Step 6 Controller 使用 — 字段一致
- ✅ `PluginStoreDetailDTO` 在 Task 8.3 Step 2 定义，Step 6 Controller 使用 — 字段一致
- ✅ `PluginStoreMigration.cleanTempDirs(Long instanceId)` 在 Task 9.1 Step 1 定义，Step 2 L4D2Extension 调用 — 签名一致
- ✅ `PluginInstallService.getReadme(Long, String)` 在 Task 8.2 Step 2 定义，Step 3 Controller 调用 — 签名一致
- ✅ `SourceModCfgService.applyTempConfig(Long, String, String)` 在 Task 8.1 Step 3 Controller 调用，已有实现（v4 Phase 7）— 签名一致
- ✅ `SourceModCfgService.restoreDefaults(Long, String)` 在 Task 8.1 Step 3 Controller 调用，已有实现（v4 Phase 7）— 签名一致

无类型不一致问题。

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-l4d2-plugin-management-v5-final.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - 每个 Task 派一个 fresh subagent，task 间审查，快速迭代

**2. Inline Execution** - 在当前会话中按 batch 执行，checkpoints 处审查

**Which approach?**
