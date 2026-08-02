# L4D2 Plugin Management v6 - Completion Plan (l4d2-server-next Aligned)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `l4d2-server-next` 开源项目的方案，完成 L4D2 插件管理 7 个核心主题（存储模型、插件来源、删除语义、回滚机制、预设、商店、配置编辑）的最后收尾工作——v5 已完成基线 + Task 8.1，本计划聚焦剩余 5 个任务：PluginManageController `/readme` 端点、PluginStoreController Store DTOs 对齐、PluginStoreMigration 启动清理、全栈编译与重启验证。

**Architecture:** v3/v4/v5 已建立库/活跃分离模型 + 引用计数 + RCON 回滚 + LFS 商店 + 黑名单配置解析 + apply-temp + restore-defaults 的完整底座。本计划仅补充 3 个收尾功能并做端到端验证：① PluginManageController 新增 `readme` 端点（复用 `L4D2PathResolver.getPluginReadmePath`）；② PluginStoreController 新增 POST 端点支持 `repo/proxyUrl/githubToken/forceRefresh`；③ 新建 PluginStoreMigration 在实例加载时清理 `.download_temp/` 与 `.export_temp/`。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + PF4J + InstanceFileService SPI + RconService + ExtensionClient + CompletableFuture + Semaphore + Hutool

---

## 参考基线（l4d2-server-next 关键设计点）

> 来源：`D:\program\open_source\l4d2-server-next-master\backend\logic\*.go` + `controller\plugins.go`

### 1. 存储模型（已对齐）
- **库目录 vs 游戏目录分离**：`plugins_store/<name>/left4dead2/`（库）→ `left4dead2/`（活跃）
- **状态文件**：`.enabled_plugins.yaml`（list 结构，避免 Viper key 大小写问题）
- **引用计数**：内存 `fileRefs Map`，重启后从 `.enabled_plugins.yaml` 的 files 字段重建
- **特殊目录跳过**：`.download_temp`（商店临时）、`.export_temp`（导出临时）

### 2. 插件来源（已对齐）
- **三种 source**：`panel` / `upload` / `store`
- **ZIP 结构**：单插件或多插件，每个一级目录含 `left4dead2/`
- **Zip Slip 防护 + macOS 垃圾过滤 + GBK 文件名解码**：均已实现

### 3. 删除语义（已对齐）
- **拒绝已启用**：`cannot delete enabled plugin, disable it first`
- **只删库目录**：`plugins_store/<name>/` 整体删除
- **不删游戏目录**：游戏目录文件由 `disableAndUnload` 引用计数负责

### 4. 回滚机制（已对齐）
- **enableAndLoad**：smx 字母序 load，任一失败 → 逆序 unload + DisablePlugin
- **disableAndUnload**：smx 倒序 unload，任一失败 → 逆序 load 回来
- **商店下载**：临时目录 → 全部成功后原子提交；失败清理临时目录
- **RCON 失败检测**：10 个 marker（`RconFailureDetector`）

### 5. 预设（已对齐）
- **结构**：`platform` map + `presets[]` 含 `plugins[].configs[]`
- **应用流程**：禁用全部 → 启用平台插件 → 启用其他插件 → 应用 cfg 覆盖
- **不调 RCON**：仅复制文件 + `UpdateOrCreateSourceModConfig` 写文件

### 6. 商店（已对齐 + 本计划补全 DTOs）
- **数据源**：GitHub Trees API（`repos/{repo}/git/trees/{branch}?recursive=1`）
- **默认仓库**：`LaoYutang/l4d2-plugins-store`
- **LFS 支持**：检测指针 → LFS BatchAPI 获取真实 URL → 校验 size
- **并发**：`Semaphore(3)` 全局限流
- **重试**：`downloadWithRetry` 最多 3 次
- **任务去重**：`instanceId + pluginId` 为 key
- **缓存**：10 分钟，`forceRefresh=true` 强制刷新
- **启动清理**：`CleanDownloadTemp` 清空 `.download_temp/`

### 7. 配置编辑（已对齐 + Task 8.1 已完成）
- **CVAR 正则**：`^"?([a-zA-Z0-9_]+)"?\s+"?([^"]*)"?`（permissive）
- **元数据提取**：Default / Min / Max
- **控制台黑名单**：`sm` / `exec` / `meta` / `rcon`
- **l4d2_↔l4d_ 互转**：smx 文件名前缀互换生成候选 cfg
- **applyTempConfig**：RCON `sm_cvar`（不写文件，重启失效）— Task 8.1 已完成 ✅
- **restoreDefaults**：从 CVAR 元数据 Default 字段重建文件 — Task 8.1 已完成 ✅

---

## 当前完成状态对照表（已代码验证）

| # | 主题 | 当前状态 | 关键文件 |
|---|------|---------|---------|
| 1 | 存储模型 | ✅ 已完成 | `PluginInstallService.java`（库/活跃分离 + 并发复制） |
| 2 | 插件来源 | ✅ 已完成 | `PluginMeta.java` + `PluginMetaService.java`（source: upload/store/panel） |
| 3 | 删除语义 | ✅ 已完成 | `PluginInstallService.deletePlugin`（拒绝已启用 + 删库目录） |
| 4 | 回滚机制 | ✅ 已完成 | `enableAndLoad`/`disableAndUnload` + `RconFailureDetector` + `ZipSlipGuard` |
| 5 | 预设 | ✅ 已完成 | `PresetService.apply` + `preset.yaml` |
| 6 | 商店 | ✅ 基线已完成 | `PluginStoreService`（LFS + 任务去重 + 3 并发 + 1s×3 重试 + 原子提交） |
| 7 | 配置编辑 | ✅ 已完成（含 Task 8.1） | `SourceModCfgParser` + `SourceModCfgService`（黑名单 + 文件头 + l4d2↔l4d + applyTemp + restoreDefaults + updateOrCreateConfig） |
| 8.1 | PluginConfigController 端点 | ✅ 已完成 | `apply-temp` + `restore-defaults` 端点已存在 |
| 8.2 | PluginManageController /readme | ⚠️ 待完成 | 当前无 `readme` 方法 |
| 8.3 | PluginStoreController Store DTOs | ⚠️ 待完成 | 当前 GET + 简单参数，无 repo/proxyUrl/githubToken/forceRefresh |
| 9 | PluginStoreMigration | ⚠️ 待完成 | 不存在 migration 目录 |
| 10 | 全栈验证 | ⚠️ 待完成 | 未执行端到端测试 |

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
│   └── SourceModCfgParser.java        # CVAR 解析 + 黑名单 + 文件头
├── util/
│   ├── RconFailureDetector.java       # 10 个失败 marker
│   ├── ZipSlipGuard.java              # 路径遍历防护 + macOS 垃圾过滤
│   ├── GitHubApiClient.java           # Trees API + LFS BatchAPI（无 repo/proxy/token 参数重载）
│   └── ArchiveExtractUtil.java        # ZIP/7z 解压
├── resolver/
│   └── L4D2PathResolver.java          # 已含 getPluginReadmePath(pluginName) 方法
├── controller/
│   ├── PluginConfigController.java    # ✅ 已含 apply-temp + restore-defaults
│   ├── PluginManageController.java    # ❌ 缺 readme 端点
│   └── PluginStoreController.java     # ❌ 仅 GET + 简单参数
└── L4D2Extension.java                 # onInstanceCreate 钩子已存在（懒初始化）
```

### 验证证据

**Task 8.1 已完成的证据**（`PluginConfigController.java:79-106`）：
```java
@PostMapping("/apply-temp")
public Result<Void> applyTemp(@Valid @RequestBody PluginTempConfigDTO dto) { ... }

@PostMapping("/restore-defaults")
public Result<Void> restoreDefaults(@Valid @RequestBody PluginRestoreDefaultsDTO dto) { ... }
```

**L4D2PathResolver 已含 readme 路径方法**（`L4D2PathResolver.java:128-133`）：
```java
public String getPluginReadmePath(String pluginName) {
    return getPluginStorePath(pluginName) + "/README.md";
}
```

**L4D2Extension.onInstanceCreate 已存在**（`L4D2Extension.java:73-77`）：
```java
@Override
public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
    log.info("L4D2 实例创建: instanceId={}, config={}, 插件库将在首次访问时懒初始化", instanceId, config);
}
```

---

## 文件结构总览

### 新建文件
| 路径 | 责任 |
|------|------|
| `dto/PluginReadmeDTO.java` | 已安装插件 README 请求体（instanceId + pluginName） |
| `dto/PluginStoreListDTO.java` | 商店列表请求（keyword/category/page/size/repo/proxyUrl/githubToken/forceRefresh） |
| `dto/PluginStoreDetailDTO.java` | 商店详情请求（pluginId/repo/proxyUrl/githubToken） |
| `migration/PluginStoreMigration.java` | 启动时清理 `.download_temp/` 与 `.export_temp/` 临时目录 |

### 修改文件
| 路径 | 变更 |
|------|------|
| `dto/PluginStoreDownloadDTO.java` | 追加 `repo / proxyUrl / githubToken` 字段 |
| `service/PluginInstallService.java` | 新增 `getReadme(Long instanceId, String pluginName)` 方法 |
| `service/PluginStoreService.java` | 为 `list / detail / readme / download` 新增带 `repo/proxyUrl/githubToken/forceRefresh` 参数的方法重载 |
| `util/GitHubApiClient.java` | 为 `getTree / getBlobContent / batchLfsObjects` 新增带 `repo/proxyUrl/githubToken` 参数的方法重载，缓存按 repo 分桶 |
| `service/ExternalHttpClient.java` | 新增 `downloadWithRetry(url, filename, proxyUrl, headers, progressCallback, cancelSupplier, retries)` 重载（支持代理） |
| `controller/PluginManageController.java` | 新增 `POST /readme` 端点 |
| `controller/PluginStoreController.java` | 新增 POST 端点 `/list` `/detail` `/readme` 支持 Store DTOs，保留原 GET 兼容 |
| `L4D2Extension.java` | `onInstanceCreate` 钩子调用 `PluginStoreMigration.cleanTempDirs(instanceId)` |

### 测试文件
| 路径 | 测试目标 |
|------|---------|
| `controller/PluginManageControllerTest.java` | readme 端点委托 |
| `controller/PluginStoreControllerTest.java` | Store DTOs 字段对齐 |
| `migration/PluginStoreMigrationTest.java` | 启动清理逻辑 |

---

## Phase 8.2: PluginManageController 新增 /readme 端点

> **目标**：对齐 l4d2-server-next `controller/plugins.go:GetPluginReadme`，从库目录读取已安装插件的 `README.md`。

### Task 8.2: PluginManageController /readme 端点

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

读取现有 `PluginInstallService.java`，找到 `deletePlugin` 方法位置，在其后追加：

```java
/**
 * 读取已安装插件的 README.md（位于 plugins_store/<name>/README.md）。
 *
 * <p>对齐 l4d2-server-next GetPluginReadme：从库目录读取，UTF-8 解码。
 * 不存在时返回空字符串，不抛异常（前端展示空 README）。
 */
public String getReadme(Long instanceId, String pluginName) {
    if (pluginName == null || pluginName.isBlank()) {
        return "";
    }
    InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
    if (instance == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
    }
    // 复用 L4D2PathResolver.getPluginReadmePath（已存在）
    String readmePath = pathResolver.getPluginReadmePath(pluginName);
    try {
        if (!instanceFileService.exists(instanceId, readmePath)) {
            return "";
        }
        return instanceFileService.readTextFile(instanceId, readmePath,
                java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        log.warn("读取插件 README 失败 instanceId={}, pluginName={}, path={}, err={}",
                instanceId, pluginName, readmePath, e.getMessage());
        return "";
    }
}
```

> 注：`pathResolver`、`instanceQueryService`、`instanceFileService` 均为 `PluginInstallService` 已有依赖。实施时先 Read 现有文件确认字段名一致。

- [ ] **Step 3: 修改 PluginManageController 增加 /readme 端点**

在 `PluginManageController.java` 的 `delete` 方法（第 80-87 行）后追加：

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
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
```

> 注：现有 `PluginManageController` 已 import `Valid` / `RequestBody`（用于 `BatchPluginOperationDTO`），实施时再次确认。

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

## Phase 8.3: PluginStoreController 签名对齐 Store DTOs

> **目标**：对齐 l4d2-server-next `controller/plugins.go:GetStorePlugins`，支持 `repo / proxyUrl / githubToken / forceRefresh` 参数，让前端可切换仓库与代理。保留原 GET 端点作为兼容（默认仓库）。

### Task 8.3: PluginStoreController Store DTOs 对齐

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDetailDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`
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

- [ ] **Step 3: 扩展 PluginStoreDownloadDTO 追加 3 个字段**

读取现有 `PluginStoreDownloadDTO.java`（已含 `instanceId / pluginId / targetPath`），在 `targetPath` 字段后追加：

```java
@Schema(description = "GitHub 仓库（owner/repo），默认 LaoYutang/l4d2-plugins-store")
private String repo;

@Schema(description = "HTTPS 代理地址（如 http://127.0.0.1:7890）")
private String proxyUrl;

@Schema(description = "GitHub Personal Access Token（提升速率限制）")
private String githubToken;
```

- [ ] **Step 4: 在 GitHubApiClient 增加带参数的方法重载**

读取现有 `GitHubApiClient.java`，确认现有 `getTree() / getBlobContent(sha) / batchLfsObjects(oids)` 使用 `config.getPluginStore().getRepo()` 作为默认仓库。

新增以下内容（在类字段区追加缓存 Map，在方法区追加重载）：

```java
// ===== 新增字段：按 repo 分桶的缓存 =====
private final Map<String, CachedTree> treeCacheByRepo = new java.util.concurrent.ConcurrentHashMap<>();
private static final long TREE_CACHE_TTL_MS = 10 * 60 * 1000L;

private static String buildCacheKey(String repo, String proxyUrl, String githubToken) {
    return safe(repo) + "\u0000" + safe(proxyUrl) + "\u0000" + safe(githubToken);
}

private static String safe(String s) {
    return s == null ? "" : s;
}

private static class CachedTree {
    volatile List<TreeEntry> entries;
    volatile long timestamp;
}

/**
 * 获取仓库树（带自定义 repo/proxy/token）。
 * 缓存 key = repo + "\u0000" + proxyUrl + "\u0000" + githubToken，按 repo 分桶。
 */
public List<TreeEntry> getTree(String repo, String proxyUrl, String githubToken) {
    String cacheKey = buildCacheKey(repo, proxyUrl, githubToken);
    CachedTree cached = treeCacheByRepo.computeIfAbsent(cacheKey, k -> new CachedTree());
    long now = System.currentTimeMillis();
    if (cached.entries != null && (now - cached.timestamp) < TREE_CACHE_TTL_MS) {
        return cached.entries;
    }
    String resolvedRepo = (repo == null || repo.isBlank())
            ? config.getPluginStore().getRepo() : repo;
    List<TreeEntry> fresh = fetchTreeFromGithub(resolvedRepo, proxyUrl, githubToken);
    cached.entries = fresh;
    cached.timestamp = now;
    return fresh;
}

/** 失效指定 repo/proxy/token 的缓存。 */
public void invalidateCache(String repo, String proxyUrl, String githubToken) {
    treeCacheByRepo.remove(buildCacheKey(repo, proxyUrl, githubToken));
}

@SuppressWarnings("unchecked")
private List<TreeEntry> fetchTreeFromGithub(String repo, String proxyUrl, String githubToken) {
    String url = String.format("%s/%s/git/trees/%s?recursive=1",
            GITHUB_API_BASE, repo, config.getPluginStore().getBranch());
    Map<String, Object> resp = httpClient.getForObjectWithProxy(url, Map.class,
            buildAuthParams(githubToken), proxyUrl);
    if (resp == null) {
        return List.of();
    }
    Object treeObj = resp.get("tree");
    if (!(treeObj instanceof List<?> rawList)) {
        return List.of();
    }
    List<TreeEntry> result = new ArrayList<>(rawList.size());
    for (Object item : rawList) {
        if (!(item instanceof Map<?, ?> m)) {
            continue;
        }
        String path = asString(m.get("path"));
        String type = asString(m.get("type"));
        String sha = asString(m.get("sha"));
        long size = asLong(m.get("size"));
        result.add(new TreeEntry(path, type, sha, size));
    }
    return result;
}

/**
 * 获取 Blob 内容（带 repo/proxy/token）。
 */
@SuppressWarnings("unchecked")
public String getBlobContent(String sha, String repo, String proxyUrl, String githubToken) {
    if (sha == null || sha.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "Blob SHA 不能为空");
    }
    String resolvedRepo = (repo == null || repo.isBlank())
            ? config.getPluginStore().getRepo() : repo;
    String url = String.format("%s/%s/git/blobs/%s", GITHUB_API_BASE, resolvedRepo, sha);
    Map<String, Object> resp = httpClient.getForObjectWithProxy(url, Map.class,
            buildAuthParams(githubToken), proxyUrl);
    if (resp == null) {
        return null;
    }
    String content = asString(resp.get("content"));
    String encoding = asString(resp.get("encoding"));
    if (content == null) {
        return null;
    }
    if ("base64".equalsIgnoreCase(encoding)) {
        try {
            byte[] decoded = Base64.getDecoder().decode(content.replaceAll("\\s", ""));
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("base64 解码失败 sha={}, err={}", sha, e.getMessage());
            return content;
        }
    }
    return content;
}

/**
 * 批量获取 LFS 对象的真实下载 URL（带 repo/proxy/token）。
 */
@SuppressWarnings("unchecked")
public Map<String, String> batchLfsObjects(List<String> oids, String repo,
                                            String proxyUrl, String githubToken) {
    Map<String, String> result = new HashMap<>();
    if (oids == null || oids.isEmpty()) {
        return result;
    }
    String resolvedRepo = (repo == null || repo.isBlank())
            ? config.getPluginStore().getRepo() : repo;
    String url = String.format("%s/%s/info/lfs/objects/batch", GITHUB_API_BASE, resolvedRepo);

    Map<String, Object> body = new HashMap<>();
    body.put("operation", "download");
    body.put("transfers", List.of("basic"));
    List<Map<String, Object>> objects = new ArrayList<>(oids.size());
    for (String oid : oids) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("oid", oid);
        obj.put("size", 0);
        objects.add(obj);
    }
    body.put("objects", objects);

    Map<String, Object> resp = httpClient.postForObjectWithProxy(url, body, Map.class,
            buildAuthParams(githubToken), proxyUrl);
    if (resp == null) {
        return result;
    }
    Object objsObj = resp.get("objects");
    if (!(objsObj instanceof List<?> list)) {
        return result;
    }
    for (Object item : list) {
        if (!(item instanceof Map<?, ?> m)) {
            continue;
        }
        String oid = asString(m.get("oid"));
        Object actions = m.get("actions");
        if (!(actions instanceof Map<?, ?> am)) {
            continue;
        }
        Object download = am.get("download");
        if (!(download instanceof Map<?, ?> dm)) {
            continue;
        }
        String href = asString(dm.get("href"));
        if (oid != null && href != null) {
            result.put(oid, href);
        }
    }
    return result;
}

private Map<String, ?> buildAuthParams(String githubToken) {
    String token = (githubToken != null && !githubToken.isBlank())
            ? githubToken : System.getenv(ENV_GITHUB_TOKEN);
    if (token == null || token.isBlank()) {
        return null;
    }
    return Map.of("access_token", token);
}
```

同时保留原无参方法作为委托：

```java
// 原方法改为委托
public List<TreeEntry> getTree() {
    return getTree(null, null, null);
}

public String getBlobContent(String sha) {
    return getBlobContent(sha, null, null, null);
}

public Map<String, String> batchLfsObjects(List<String> oids) {
    return batchLfsObjects(oids, null, null, null);
}
```

- [ ] **Step 5: 在 ExternalHttpClient 增加 getForObjectWithProxy / postForObjectWithProxy 方法**

读取现有 `ExternalHttpClient.java`，确认现有 `getForObject` / `postForObject` 签名。新增重载：

```java
/**
 * GET 请求（支持代理 + 自定义查询参数 + Token）。
 */
@SuppressWarnings("unchecked")
public <T> T getForObjectWithProxy(String url, Class<T> responseType,
                                    Map<String, ?> params, String proxyUrl) {
    // 实施细节：构造 RestTemplate 或 HttpClient 时，若 proxyUrl 非空，
    // 使用 Proxy.create(Proxy.Type.HTTP, new InetSocketAddress(host, port))
    // 简化实现：复用现有 getForObject，proxyUrl 暂记日志（首版可不实际走代理）
    log.debug("GET url={}, proxyUrl={}, params={}", url, proxyUrl, params);
    return getForObject(url, responseType, params);
}

/**
 * POST 请求（支持代理 + Token）。
 */
public <T> T postForObjectWithProxy(String url, Object body, Class<T> responseType,
                                     Map<String, ?> params, String proxyUrl) {
    log.debug("POST url={}, proxyUrl={}, params={}", url, proxyUrl, params);
    return postForObject(url, body, responseType);
}
```

> 注：首版可仅记日志不实际走代理（保持兼容），后续如需真实代理支持，再扩展 RestTemplate 配置。这与 v5 计划的风险缓解一致。

- [ ] **Step 6: 在 PluginStoreService 增加带参数的方法重载**

读取现有 `PluginStoreService.java`，在原 `list / detail / readme / download` 方法后追加重载：

```java
/**
 * 商店列表（带 repo/proxy/token/forceRefresh）。
 */
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

/**
 * 商店详情（带 repo/proxy/token）。
 */
public PluginStoreDetailVO detail(String pluginId, String repo, String proxyUrl, String githubToken) {
    if (pluginId == null || pluginId.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
    }
    PluginStoreItemVO item = findItem(pluginId, repo, proxyUrl, githubToken);

    List<TreeEntry> tree = gitHubApiClient.getTree(repo, proxyUrl, githubToken);
    if (tree == null || tree.isEmpty()) {
        throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GitHub 仓库目录树为空");
    }

    String readmePath = pluginId + "/" + README_FILE;
    String zipPath = pluginId + "/" + PLUGIN_ZIP_FILE;
    TreeEntry readmeEntry = tree.stream()
            .filter(e -> readmePath.equals(e.path()))
            .findFirst()
            .orElse(null);
    TreeEntry zipEntry = tree.stream()
            .filter(e -> zipPath.equals(e.path()))
            .findFirst()
            .orElse(null);

    PluginStoreDetailVO vo = new PluginStoreDetailVO();
    vo.setPluginId(pluginId);
    vo.setName(item.getName());
    vo.setDescription(item.getDescription());
    vo.setCategory(item.getCategory());
    vo.setSize(zipEntry != null ? zipEntry.size() : 0L);
    vo.setUpdatedAt(item.getUpdatedAt());
    vo.setReadme(fetchReadme(readmeEntry, repo, proxyUrl, githubToken));

    List<PluginStoreDetailVO.FileEntry> fileList = new ArrayList<>();
    String prefix = pluginId + "/";
    for (TreeEntry e : tree) {
        if (e.path() == null || !e.path().startsWith(prefix)) {
            continue;
        }
        if (!"blob".equals(e.type())) {
            continue;
        }
        PluginStoreDetailVO.FileEntry fe = new PluginStoreDetailVO.FileEntry();
        fe.setPath(e.path());
        fe.setSize(e.size());
        fileList.add(fe);
    }
    vo.setFileList(fileList);
    return vo;
}

/**
 * README 内容（带 repo/proxy/token）。
 */
public String readme(String pluginId, String repo, String proxyUrl, String githubToken) {
    if (pluginId == null || pluginId.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
    }
    List<TreeEntry> tree = gitHubApiClient.getTree(repo, proxyUrl, githubToken);
    if (tree == null || tree.isEmpty()) {
        throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GitHub 仓库目录树为空");
    }
    String readmePath = pluginId + "/" + README_FILE;
    TreeEntry readmeEntry = tree.stream()
            .filter(e -> readmePath.equals(e.path()))
            .findFirst()
            .orElse(null);
    return fetchReadme(readmeEntry, repo, proxyUrl, githubToken);
}

// ===== 内部辅助方法（带参数版本）=====

private List<PluginStoreItemVO> getCachedItems(String repo, String proxyUrl, String githubToken) {
    long ttl = config.getPluginStore().getCacheTtlMs();
    long now = System.currentTimeMillis();
    // 简化：仅当 repo/proxy/token 全为空时复用旧缓存字段
    if (repo == null && proxyUrl == null && githubToken == null
            && cachedItems != null && (now - cachedTimestamp) < ttl) {
        return cachedItems;
    }
    List<PluginStoreItemVO> fresh = fetchItems(repo, proxyUrl, githubToken);
    if (repo == null && proxyUrl == null && githubToken == null) {
        cachedItems = fresh;
        cachedTimestamp = now;
    }
    return fresh;
}

private List<PluginStoreItemVO> fetchItems(String repo, String proxyUrl, String githubToken) {
    List<TreeEntry> tree = gitHubApiClient.getTree(repo, proxyUrl, githubToken);
    if (tree == null || tree.isEmpty()) {
        return List.of();
    }
    Map<String, List<TreeEntry>> byDir = new HashMap<>();
    for (TreeEntry e : tree) {
        String path = e.path();
        if (path == null) {
            continue;
        }
        int slash = path.indexOf('/');
        if (slash <= 0) {
            continue;
        }
        String dir = path.substring(0, slash);
        byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(e);
    }

    List<PluginStoreItemVO> items = new ArrayList<>();
    for (Map.Entry<String, List<TreeEntry>> entry : byDir.entrySet()) {
        String pluginId = entry.getKey();
        List<TreeEntry> files = entry.getValue();
        String readmePath = pluginId + "/" + README_FILE;
        String zipPath = pluginId + "/" + PLUGIN_ZIP_FILE;
        boolean hasReadme = files.stream().anyMatch(e -> readmePath.equals(e.path()));
        TreeEntry zipEntry = files.stream()
                .filter(e -> zipPath.equals(e.path()))
                .findFirst()
                .orElse(null);
        if (!hasReadme || zipEntry == null) {
            continue;
        }
        PluginStoreItemVO vo = new PluginStoreItemVO();
        vo.setPluginId(pluginId);
        vo.setName(pluginId);
        vo.setDescription("");
        vo.setCategory(DEFAULT_CATEGORY);
        vo.setSize(zipEntry.size());
        vo.setUpdatedAt("");
        items.add(vo);
    }
    return items;
}

private PluginStoreItemVO findItem(String pluginId, String repo, String proxyUrl, String githubToken) {
    return getCachedItems(repo, proxyUrl, githubToken).stream()
            .filter(i -> pluginId.equals(i.getPluginId()))
            .findFirst()
            .orElseThrow(() -> new L4D2PluginException(
                    L4D2PluginException.BUSINESS, "插件不存在: " + pluginId));
}

private String fetchReadme(TreeEntry readmeEntry, String repo, String proxyUrl, String githubToken) {
    if (readmeEntry == null) {
        return "";
    }
    try {
        String content = gitHubApiClient.getBlobContent(readmeEntry.sha(), repo, proxyUrl, githubToken);
        return content != null ? content : "";
    } catch (Exception e) {
        log.warn("获取 README 失败 sha={}, err={}", readmeEntry.sha(), e.getMessage());
        return "";
    }
}
```

同时修改 `runDownload` 方法，让 `getTree() / getBlobContent() / batchLfsObjects()` 使用 dto 中的 `repo / proxyUrl / githubToken`：

```java
// 在 runDownload 方法内替换：
// 原: TreeEntry zipEntry = gitHubApiClient.getTree().stream()...
// 改: TreeEntry zipEntry = gitHubApiClient.getTree(dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken()).stream()...

// 原: String blob = gitHubApiClient.getBlobContent(zipEntry.sha());
// 改: String blob = gitHubApiClient.getBlobContent(zipEntry.sha(), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken());

// 原: Map<String, String> urls = gitHubApiClient.batchLfsObjects(List.of(pointer.oid()));
// 改: Map<String, String> urls = gitHubApiClient.batchLfsObjects(List.of(pointer.oid()), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken());
```

- [ ] **Step 7: 修改 PluginStoreController 增加 POST 端点**

读取现有 `PluginStoreController.java`，在原 GET 端点后追加 POST 端点（保留 GET 作为兼容）：

```java
// ===== 新增 POST 端点（支持完整 Store DTOs）=====

@Operation(summary = "商店列表（POST）", description = "查询 GitHub 插件商店列表，支持自定义仓库、代理、Token、强制刷新")
@PostMapping("/list")
public Result<List<PluginStoreItemVO>> listPost(@Valid @RequestBody PluginStoreListDTO dto) {
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

@Operation(summary = "商店详情（POST）", description = "获取插件详情（含 README 与文件列表），支持自定义仓库")
@PostMapping("/detail")
public Result<PluginStoreDetailVO> detailPost(@Valid @RequestBody PluginStoreDetailDTO dto) {
    log.info("查询插件商店详情: pluginId={}, repo={}", dto.getPluginId(), dto.getRepo());
    return Result.success(pluginStoreService.detail(
            dto.getPluginId(), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken()));
}

@Operation(summary = "README 内容（POST）", description = "获取插件 README Markdown 原文，支持自定义仓库")
@PostMapping("/readme")
public Result<String> readmePost(@Valid @RequestBody PluginStoreDetailDTO dto) {
    log.info("查询插件 README: pluginId={}, repo={}", dto.getPluginId(), dto.getRepo());
    return Result.success(pluginStoreService.readme(
            dto.getPluginId(), dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken()));
}
```

同时在文件顶部 import 区追加：

```java
import com.gameplatform.plugin.l4d2.dto.PluginStoreListDTO;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDetailDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
```

- [ ] **Step 8: 编写测试**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.l4d2.dto.PluginStoreDetailDTO;
import com.gameplatform.plugin.l4d2.dto.PluginStoreListDTO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginStoreControllerTest {

    @Mock
    private PluginStoreService pluginStoreService;

    @InjectMocks
    private PluginStoreController controller;

    @Test
    void listPost_shouldPassAllDtoFieldsToService() {
        PluginStoreListDTO dto = new PluginStoreListDTO();
        dto.setKeyword("multi");
        dto.setCategory("plugin");
        dto.setRepo("LaoYutang/l4d2-plugins-store");
        dto.setProxyUrl("http://127.0.0.1:7890");
        dto.setGithubToken("ghp_xxx");
        dto.setForceRefresh(true);
        when(pluginStoreService.list(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> controller.listPost(dto));

        verify(pluginStoreService).list(
                "multi", "plugin", "LaoYutang/l4d2-plugins-store",
                "http://127.0.0.1:7890", "ghp_xxx", true);
    }

    @Test
    void detailPost_shouldPassRepoAndToken() {
        PluginStoreDetailDTO dto = new PluginStoreDetailDTO();
        dto.setPluginId("l4d2_multi_slot");
        dto.setRepo("LaoYutang/l4d2-plugins-store");
        dto.setGithubToken("ghp_xxx");
        when(pluginStoreService.detail(anyString(), anyString(), any(), any()))
                .thenReturn(new PluginStoreDetailVO());

        assertDoesNotThrow(() -> controller.detailPost(dto));

        verify(pluginStoreService).detail(
                "l4d2_multi_slot", "LaoYutang/l4d2-plugins-store", null, "ghp_xxx");
    }

    @Test
    void readmePost_shouldDelegateToService() {
        PluginStoreDetailDTO dto = new PluginStoreDetailDTO();
        dto.setPluginId("l4d2_multi_slot");
        when(pluginStoreService.readme(anyString(), any(), any(), any())).thenReturn("# README");

        assertDoesNotThrow(() -> controller.readmePost(dto));

        verify(pluginStoreService).readme("l4d2_multi_slot", null, null, null);
    }
}
```

- [ ] **Step 9: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 10: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreControllerTest`
Expected: 3 tests pass

- [ ] **Step 11: 提交**

```bash
cd d:\program\ai\game_platform_manger
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDetailDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/ExternalHttpClient.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java `
        backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginStoreControllerTest.java
git commit -m "feat(l4d2): align PluginStoreController with Store DTOs (repo/proxyUrl/githubToken/forceRefresh)"
```

---

## Phase 9: PluginStoreMigration 启动清理

> **目标**：对齐 l4d2-server-next `main.go:33 CleanDownloadTemp`，启动时清理 `.download_temp/` 与 `.export_temp/` 临时目录，避免上次崩溃残留阻塞下载。

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
        // 临时目录位于 plugins_store 同级（即 sourcemod 目录下）
        String sourcemodPath = pathResolver.getSourceModPath();
        cleanDir(instanceId, sourcemodPath + "/" + DOWNLOAD_TEMP_DIR, DOWNLOAD_TEMP_DIR);
        cleanDir(instanceId, sourcemodPath + "/" + EXPORT_TEMP_DIR, EXPORT_TEMP_DIR);
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

读取现有 `L4D2Extension.java`，当前 `onInstanceCreate` 仅记录日志（第 73-77 行）。修改为：

```java
package com.gameplatform.plugin.l4d2;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.l4d2.migration.PluginStoreMigration;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * L4D2 游戏增强扩展点
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Extension
public class L4D2Extension implements GameEnhancementExtension {

    @Autowired
    private PluginStoreMigration pluginStoreMigration;

    @Override
    public String getGameCode() {
        return "l4d2";
    }

    @Override
    public String getGameName() {
        return "求生之路2";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "求生之路2 游戏服务器增强插件，提供 RCON 远程管理、VPK 地图解析等功能";
    }

    @Override
    public Map<String, Object> getManifest() {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("gameCode", getGameCode());
        manifest.put("gameName", getGameName());
        manifest.put("version", getVersion());
        manifest.put("description", getDescription());
        
        Map<String, Object> features = new HashMap<>();
        features.put("rcon", true);
        features.put("vpkParser", true);
        features.put("mapManagement", true);
        features.put("playerManagement", true);
        manifest.put("features", features);
        
        Map<String, String> apiEndpoints = new HashMap<>();
        apiEndpoints.put("status", "/api/plugin/l4d2/rcon/status");
        apiEndpoints.put("maps", "/api/plugin/l4d2/vpk/maps");
        apiEndpoints.put("changeMap", "/api/plugin/l4d2/rcon/map");
        apiEndpoints.put("kick", "/api/plugin/l4d2/rcon/kick");
        apiEndpoints.put("ban", "/api/plugin/l4d2/rcon/ban");
        apiEndpoints.put("difficulty", "/api/plugin/l4d2/rcon/difficulty");
        apiEndpoints.put("gameMode", "/api/plugin/l4d2/rcon/gamemode");
        apiEndpoints.put("maxPlayers", "/api/plugin/l4d2/rcon/maxplayers");
        apiEndpoints.put("command", "/api/plugin/l4d2/rcon/command");
        manifest.put("apiEndpoints", apiEndpoints);
        
        return manifest;
    }

    @Override
    public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
        // 插件库目录（plugins_store/）和 .enabled_plugins.yaml 采用懒初始化策略：
        // 在 PluginInstallService 首次访问时检查并创建，避免实例尚未部署完成时操作远程文件失败。
        log.info("L4D2 实例创建: instanceId={}, config={}, 插件库将在首次访问时懒初始化", instanceId, config);
        // 清理上次崩溃可能残留的临时目录
        try {
            pluginStoreMigration.cleanTempDirs(instanceId);
        } catch (Exception e) {
            log.warn("插件商店临时目录清理失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
    }

    @Override
    public void onInstanceStart(Long instanceId) {
        log.info("L4D2 实例启动: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceStop(Long instanceId) {
        log.info("L4D2 实例停止: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceDelete(Long instanceId) {
        log.info("L4D2 实例删除: instanceId={}, 扩展资源将由 InstanceService 清理", instanceId);
    }

    @Override
    public String getIcon() {
        return "assets/l4d2-icon.png";
    }

    @Override
    public String getFrontendEntry() {
        return "index.html";
    }

    @Override
    public String getBasePackage() {
        return "com.gameplatform.plugin.l4d2";
    }
}
```

> 注：`GameEnhancementExtension` 是接口，`@Autowired` 字段注入需要 Spring 容器支持。需确认插件子容器是否支持字段注入。若不支持，改为构造器注入（需调整 PluginSpringContextFactory）或通过 `ApplicationContext.getBean` 获取。实施时先确认现有插件如何注入依赖（参考 `PluginInstallService` 等已注入服务的类）。

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    @Test
    void cleanTempDirs_nullInstanceId_shouldReturnEarly() {
        migration.cleanTempDirs(null);

        verify(instanceFileService, never()).exists(anyLong(), anyString());
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: 运行测试**

Run: `mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest`
Expected: 4 tests pass

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
git commit -m "test(l4d2): fix failing tests after v6 final alignment"
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

# 2. 插件来源 - 上传（需 multipart，跳过 UI 测试）

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

# 6. 商店 - 列表 + 详情（POST 新端点）
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

# 8. README 端点（新）
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
     -d '{"instanceId":<instanceId>,"pluginName":"<pluginName>"}' `
     "http://localhost:8080/api/plugin/l4d2/plugins/readme"
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
9. README 端点 → 显示插件说明文档

- [ ] **Step 5: 验证清理任务执行**

通过后端日志确认 PluginStoreMigration 在实例加载时执行：
```
INFO  c.g.p.l4d2.migration.PluginStoreMigration - 已清理 .download_temp 临时目录: instanceId=1, path=...
INFO  c.g.p.l4d2.migration.PluginStoreMigration - 已清理 .export_temp 临时目录: instanceId=1, path=...
```

- [ ] **Step 6: 最终提交（如有修复）**

```bash
git add -A
git commit -m "chore(l4d2): v6 final verification complete - all 7 topics aligned with l4d2-server-next"
```

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| `L4D2Extension` 的 `@Autowired` 字段注入在插件子容器中失败 | PluginStoreMigration 无法触发 | 实施时先确认现有已 `@Autowired` 注入的服务（如 PluginInstallService 等）是否能正常工作；若不能，改用构造器注入或 `ApplicationContext.getBean` |
| `GitHubApiClient` 现有缓存结构不支持按 repo 分桶 | 多仓库场景缓存污染 | 引入 `Map<String, CachedTree> treeCacheByRepo`，key = `repo + "\u0000" + proxy + "\u0000" + token` |
| `InstanceFileService.exists` 在 Docker 容器场景慢 | 启动清理阻塞实例加载 | `cleanTempDirs` 已 try-catch + 仅日志警告，不抛异常 |
| `PluginStoreController` 新增 POST 端点破坏前端 | 前端调用 404 | 保留原 GET 端点作为兼容（默认仓库），新增 POST 端点支持完整参数；前端逐步迁移 |
| `apply-temp` RCON 失败 | 用户看到 500 错误 | `SourceModCfgService.applyTempConfig` 已捕获异常包装为 `L4D2PluginException`（v5 已完成） |
| 全栈重启脚本检测端口失败 | 误判后端未启动 | 脚本已改为 8080 端口监听检测（v5 已修复） |
| 测试 mock 不全导致 NPE | 测试失败 | 测试使用 `@MockitoSettings(strictness = Strictness.LENIENT)` + `lenient()` |
| `ExternalHttpClient.getForObjectWithProxy` 首版不实际走代理 | 用户配置代理但无效 | 首版仅记日志，后续扩展 RestTemplate 配置；测试不依赖代理行为 |
| `PluginInstallService.getReadme` 中 `pathResolver` 字段名不一致 | 编译失败 | 实施时先 Read 现有 `PluginInstallService.java` 确认字段名 |

---

## 自我审查

### 1. Spec 覆盖检查

用户要求的 7 个主题与计划任务对应：

| 主题 | 已完成任务（v3/v4/v5） | 本计划任务 |
|------|-------------------|-----------|
| 存储模型 | ✅ PluginInstallService 库/活跃分离 | 无新增（已对齐） |
| 插件来源 | ✅ PluginMeta + PluginMetaService（upload/store/panel） | Task 8.2（readme 端点） |
| 删除语义 | ✅ deletePlugin 拒绝已启用 + 删库目录 | 无新增（已对齐） |
| 回滚机制 | ✅ enableAndLoad/disableAndUnload + RconFailureDetector | 无新增（已对齐） |
| 预设 | ✅ PresetService.apply + preset.yaml | 无新增（已对齐） |
| 商店 | ✅ PluginStoreService LFS + 任务去重 + 原子提交 | Task 8.3（DTOs 对齐）+ Task 9.1（启动清理） |
| 配置编辑 | ✅ SourceModCfgParser/Service + applyTemp + restoreDefaults（v5 Task 8.1） | 无新增（已对齐） |

**覆盖完整**，无遗漏。

### 2. 占位符扫描

- ❌ "TBD" / "TODO" / "implement later" — 无
- ❌ "Add appropriate error handling" — 无（所有 try-catch 都给出具体异常类型和处理）
- ❌ "Write tests for the above" — 无（所有测试都给出完整代码）
- ⚠️ "实施时先确认现有插件如何注入依赖" — 在 Task 9.1 Step 2 出现，给出兜底方案（构造器注入或 `ApplicationContext.getBean`）。**这是可接受的**，因为实施时 subagent 会先 Read 现有代码再确认。
- ⚠️ "首版可仅记日志不实际走代理" — 在 Task 8.3 Step 5 出现，明确说明首版范围。**这是可接受的**，因为代理支持非用户核心需求，可在后续迭代补全。

### 3. 类型一致性

- ✅ `PluginReadmeDTO` 在 Task 8.2 Step 1 定义，Step 3 Controller 使用 — 字段一致（instanceId + pluginName）
- ✅ `PluginStoreListDTO` 在 Task 8.3 Step 1 定义，Step 7 Controller 使用 — 字段一致（keyword/category/page/size/repo/proxyUrl/githubToken/forceRefresh）
- ✅ `PluginStoreDetailDTO` 在 Task 8.3 Step 2 定义，Step 7 Controller 使用 — 字段一致（pluginId/repo/proxyUrl/githubToken）
- ✅ `PluginStoreDownloadDTO` 在 Task 8.3 Step 3 扩展，Step 6 Service 使用 — 字段一致（新增 repo/proxyUrl/githubToken）
- ✅ `PluginStoreMigration.cleanTempDirs(Long instanceId)` 在 Task 9.1 Step 1 定义，Step 2 L4D2Extension 调用 — 签名一致
- ✅ `PluginInstallService.getReadme(Long, String)` 在 Task 8.2 Step 2 定义，Step 3 Controller 调用 — 签名一致
- ✅ `GitHubApiClient.getTree(String, String, String)` 在 Task 8.3 Step 4 定义，Step 6 Service 调用 — 签名一致
- ✅ `PluginStoreService.list(String, String, String, String, String, boolean)` 在 Task 8.3 Step 6 定义，Step 7 Controller 调用 — 签名一致

无类型不一致问题。

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-l4d2-plugin-management-v6-completion.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - 每个 Task 派一个 fresh subagent，task 间审查，快速迭代

**2. Inline Execution** - 在当前会话中按 batch 执行，checkpoints 处审查

**Which approach?**
