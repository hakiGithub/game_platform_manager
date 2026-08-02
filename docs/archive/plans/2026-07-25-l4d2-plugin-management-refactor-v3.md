# L4D2 插件管理重构 v3 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 v2 已完成阶段 1+2.1-2.4 的基础上，补全开源项目 `l4d2-server-next` 的遗漏特性，完成 PluginInstallService 重写、预设系统对齐、商店增强（LFS+并发+重试）、配置编辑增强（CVAR 解析+控制台黑名单+l4d2↔l4d 互转），并新增插件导出功能。

**Architecture:** 三层模型 — `EnabledPluginsService` 管理 `.enabled_plugins.yaml` + `EnabledPluginResource` 双写；`FileRefsService` 纯内存引用计数；`PluginInstallService` 重写为库/游戏分离 + RCON 失败回滚 + 并发复制；`SourceModCfgParser` 增强为带控制台黑名单与文件头过滤的 CVAR 解析；`PluginStoreService` 增强为 LFS 大小校验 + 3 并发 + 1秒×3重试 + 任务去重；新增 `PluginExportService` 实现批量导出。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus + PF4J + Jackson YAML + Hutool + InstanceFileService SPI + RconService + ExtensionClient + CompletableFuture

---

## 参考与现状基线

### 参考项目
- `D:\program\open_source\l4d2-server-next-master`（Go + Vue 3）
- 核心文件：
  - `backend/logic/plugins.go`（945 行，CRUD + 启用/禁用 + RCON 回滚 + 并发复制 + 失败标记）
  - `backend/logic/plugin_store.go`（826 行，商店 + LFS + 大小校验 + 并发下载 + 重试）
  - `backend/logic/plugin_export.go`（398 行，批量导出 zip + 临时目录 + 任务过期清理）
  - `backend/logic/preset.go`（183 行，预设应用，不调 RCON）
  - `backend/logic/config_parser.go`（259 行，CVAR 解析 + 控制台黑名单 + 文件头过滤 + 三种写入操作）
  - `backend/logic/plugin_config.go`（112 行，候选发现 + l4d2↔l4d 互转）
  - `backend/preset.yaml`（377 行，4 个预设，单数 key `preset:`）

### v2 已完成成果（保留不动）
- **阶段 1**：`L4D2PathResolver` 新增 6 个插件库路径方法 + `L4D2Extension` 懒初始化
- **阶段 2.1-2.4**：
  - `vo/EnabledPlugin.java`（name/source/enabledAt/files）
  - `extension/EnabledPluginResource.java` + `EnabledPluginSpec.java`（MODEL_ISOLATED）
  - `service/EnabledPluginsService.java`（yaml + 扩展资源双写，246 行）
  - `service/FileRefsService.java`（纯内存 Map，从 yaml 重建，133 行）

### v2 遗漏特性（v3 补全）
| # | 遗漏特性 | 来源文件 | 影响阶段 |
|---|---------|---------|---------|
| 1 | RCON 失败检测标记列表（10 个 marker） | plugins.go:sourceModPluginCommandFailed | 阶段 2 |
| 2 | 并发复制（ants 协程池 + sync.Once + sync.Mutex） | plugins.go:EnablePlugin | 阶段 2 |
| 3 | 多插件 zip 上传支持（单插件/多插件两种结构） | plugins.go:UploadPlugin | 阶段 2 |
| 4 | GBK 编码处理（zip 文件名 GBK→UTF-8） | plugins.go:decodeZipName | 阶段 2 |
| 5 | Zip Slip 防护（路径遍历检查） | plugins.go:extractFiles | 阶段 2 |
| 6 | l4d2↔l4d 互转（配置候选发现） | plugin_config.go:getPluginConfigCandidates | 阶段 5 |
| 7 | 控制台命令黑名单（sm/exec/meta/rcon） | config_parser.go:consoleCmdNames | 阶段 5 |
| 8 | 文件头过滤（"This file was auto-generated" / "ConVars for plugin"） | config_parser.go:ParseSourceModConfig | 阶段 5 |
| 9 | 下载并发（3 worker + 全局信号量） | plugin_store.go:downloadFiles | 阶段 4 |
| 10 | LFS 大小校验（下载后校验 size） | plugin_store.go:downloadGitLFSObjectIfNeeded | 阶段 4 |
| 11 | 下载重试（1秒×3次固定退避） | plugin_store.go:downloadFileWithRetry | 阶段 4 |
| 12 | 任务 key 去重（repo\x00pluginName） | plugin_store.go:getStoreDownloadTaskKey | 阶段 4 |
| 13 | 商店 README 在线读取（GitHub raw） | plugin_store.go:FetchStorePluginReadme | 阶段 4 |
| 14 | 插件导出（批量 zip + 临时目录 + 任务过期） | plugin_export.go | 阶段 6（新增） |
| 15 | ApplyPreset 不调 RCON（仅复制文件，需重启服务器） | preset.go:ApplyPreset | 阶段 3 |

### 关键架构差异（l4d2-server-next → 本项目）
| 维度 | l4d2-server-next (Go) | 本项目 (Java) |
|------|----------------------|--------------|
| 文件操作 | `os.ReadFile/WriteFile` 本地 | `InstanceFileService` SPI 远程（SSH/Docker） |
| 启用状态文件 | `plugins.yaml`（viper，含 plugin_sources map） | `.enabled_plugins.yaml`（Jackson YAML） + `EnabledPluginResource` 双写 |
| 来源记录 | `plugin_sources` map（独立 key） | `EnabledPlugin.source` 字段（内嵌） |
| 引用计数 | 内存 `map[string][]string`（单实例） | 内存 `Map<Long, Map<String, Set<String>>>`（多实例） |
| 扩展资源 | 无 | `EnabledPluginResource` + `PluginConfigResource`（双写） |
| 配置持久化 | 仅写 cfg 文件 | cfg 文件 + `PluginConfigResource` 扩展资源 |
| RCON | `gorcon/rcon` 库 | `RconService`（已有分层架构） |
| 并发 | ants 协程池 | `CompletableFuture` + `Semaphore` |
| 预设 yaml key | `preset:`（单数） | `presets:`（复数，更符合英语习惯） |
| 预设标识 | `name`（中文名） | `id` + `name`（英文标识 + 中文显示） |

### 类型映射参考表（计划名称 → 实际类名/方法名）

> **重要**：本计划代码示例中使用的方法名/类名与实际代码库存在差异，执行时按下表替换。

#### InstanceFileService SPI 方法名映射
| 计划中使用 | 实际方法名 | 备注 |
|-----------|-----------|------|
| `readText(instanceId, path)` | `readTextFile(instanceId, path)` | 文本读取 |
| `writeText(instanceId, path, content)` | `writeTextFile(instanceId, path, content)` | 文本写入 |
| `readBytes(instanceId, path)` | `downloadFileToMemory(instanceId, path)` | 二进制读取 |
| `writeBytes(instanceId, path, bytes)` | `uploadLocalFile(instanceId, path, localPath)` 或先写本地临时文件再上传 | 无直接 writeBytes，需两步 |
| `fileExists(instanceId, path)` | `exists(instanceId, path)` | 文件存在检查 |
| `listDirectory(instanceId, path)` | `listFiles(instanceId, path)` | 列出目录 |
| `walkFiles(instanceId, path)` | 递归调用 `listFiles` 实现 | 无 walkFiles，需自行实现递归遍历 |
| `deleteFile(instanceId, path)` | `deleteFile(instanceId, path)` | 一致 ✓ |
| `createDirectory(instanceId, path)` | `createDirectory(instanceId, path)` | 一致 ✓ |
| `deleteDirectory(instanceId, path)` | `deleteDirectory(instanceId, path, true)` | 需传 recursive=true |

#### FileInfo 类型
- 实际位置：`com.gameplatform.plugin.service.FileAccessService.FileInfo`（嵌套类）
- 计划中使用：`com.gameplatform.plugin.service.FileInfo`（顶层类，错误）
- **执行时统一使用**：`FileAccessService.FileInfo`

#### 已存在的 VO/DTO 类（无需新建，需扩展）
| 计划中创建 | 实际类名 | 当前字段 | 需新增字段 |
|-----------|---------|---------|-----------|
| `StoreDownloadDTO` | `PluginStoreDownloadDTO` | instanceId, pluginId, targetPath | repo, proxyUrl, githubToken |
| `StoreDownloadTaskVO`（未显式创建） | `PluginStoreDownloadTaskVO` | taskId, instanceId, pluginId, status, progress, totalBytes, downloadedBytes, filename, error, startedAt, finishedAt | message, total, downloaded |
| `CandidatePathVO`（已存在） | `CandidatePathVO` | path, exists | alias |

#### PluginStoreDownloadTaskVO 状态值映射
| 计划中使用 | 实际状态值 |
|-----------|-----------|
| `"downloading"` | `"DOWNLOADING"` |
| `"completed"` | `"COMPLETED"` |
| `"failed"` | `"FAILED"` |
| `"pending"` | `"PENDING"` |
| `"cancelled"` | `"CANCELLED"` |

#### GitHubApiClient 实际架构
- 使用 `ExternalHttpClient`（非 `java.net.http.HttpClient`）
- 仓库/分支配置从 `L4D2Config.PluginStore` 读取（非 `defaultRepo`/`defaultBranch` 字段）
- 无 `treeCache`/`treeCacheTime` 字段（缓存需新增）
- `TreeEntry` 为 record：`(path, type, sha, size)`
- 已有 `downloadLfsObject(oid, target)` 方法
- 已有 `isLfsPointer(content)` 方法

#### GitHubApiClient 增强策略（修正）
- 新增方法时保留原签名（从 config 读取参数），新增带参数重载
- 带参数重载内部仍调用 `ExternalHttpClient`，不直接使用 `java.net.http.HttpClient`
- 代理参数通过 `ExternalHttpClient` 的扩展方法或 URL 前缀实现

#### SourceModCfgService 方法签名变更
| 方法 | 现有签名 | 新签名（Task 5.2） | 备注 |
|------|---------|-------------------|------|
| `getCandidatePaths` | `List<String> (String pluginName)` | `List<CandidatePathVO> (Long instanceId, String pluginName)` | 返回类型和参数都变，需更新调用方 |

#### PluginStoreService 现有结构
- 使用 `Map<String, PluginStoreDownloadTaskVO> tasks`（非 `storeDownloadTasks`）
- 现有方法 `listTasks(Long instanceId)` 返回 `List<PluginStoreDownloadTaskVO>`
- 现有方法 `getTask(String taskId)` 返回单个任务

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
| `service/PluginInstallService.java` | 重写为库/游戏分离 + RCON 回滚 + 并发复制 + 多插件 zip + Zip Slip 防护 |
| `service/PresetService.java` | 新预设结构应用流程（不调 RCON） |
| `config/PresetConfig.java` | 增加 `platform` 字段 |
| `vo/PresetDetailVO.java` | 改为 `plugins: List<PresetPlugin>` |
| `resources/preset.yaml` | 重写为新结构（platform + presets[].plugins[].configs[]） |
| `parser/SourceModCfgParser.java` | 新增 `restoreFormat` + 控制台黑名单 + 文件头过滤 |
| `service/SourceModCfgService.java` | 新增 3 方法 + l4d2↔l4d 互转候选 + 注入 RconService |
| `service/PluginStoreService.java` | 支持 DTO 参数 + 原子提交 + LFS 大小校验 + 3 并发 + 重试 + 任务去重 + README 在线读取 |
| `util/GitHubApiClient.java` | 新增带 proxyUrl/githubToken/repo 参数重载 + README 在线读取 + treeCache |
| `controller/PluginManageController.java` | 新增 `/readme` 端点 |
| `controller/PluginConfigController.java` | 新增 `/apply-temp` + `/restore-defaults` |
| `controller/PluginStoreController.java` | 签名对齐 Store DTOs + `/readme` 端点 |
| `resolver/L4D2PathResolver.java` | 标记 `getFileRefsPath` 为 `@Deprecated` |
| `L4D2Extension.java` | 在 `onInstanceCreate` 中调用 `PluginStoreMigration` |
| `vo/PluginConfigVO.java` | 增加 `candidates` 字段（含 l4d2↔l4d 互转结果） |
| `vo/CandidatePathVO.java` | 增加 `alias` 字段（l4d2↔l4d 互转关系） |
| `dto/PluginStoreDownloadDTO.java` | 扩展字段：repo, proxyUrl, githubToken |
| `vo/PluginStoreDownloadTaskVO.java` | 扩展字段：message, total, downloaded |
| `service/ExternalHttpClient.java` | 新增带代理支持的方法重载（如需） |

### 需新建
| 文件 | 责任 |
|------|------|
| `vo/PresetPlugin.java` | 预设插件 VO（name + configs） |
| `vo/PresetPluginConfig.java` | 预设配置 VO（name + values） |
| `dto/PluginStoreListDTO.java` | 商店列表请求（keyword/category/repo/proxyUrl/githubToken/forceRefresh） |
| `dto/PluginStoreQueryDTO.java` | 商店详情请求（pluginId/repo/proxyUrl/githubToken） |
| `migration/PluginStoreMigration.java` | 启动时迁移旧 plugins/ 到 plugins_store/ + 启动清理临时目录 |
| `service/PluginExportService.java` | 批量导出插件为 zip（参考 plugin_export.go） |
| `vo/PluginExportTaskVO.java` | 导出任务 VO（taskId/status/progress/expireAt） |
| `controller/PluginExportController.java` | 导出端点（start/status/download/cancel） |
| `util/ZipSlipGuard.java` | Zip Slip 防护工具类 |
| `util/RconFailureDetector.java` | RCON 失败检测工具类（10 个 marker） |

### 测试文件
所有新方法均需单元测试，置于 `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/...` 对应目录。

---

## 阶段 1：存储模型对齐（已完成 ✅）

跳过，保留已有成果。

---

## 阶段 2：插件列表与安装服务重写（含并发复制 + RCON 失败检测 + 多插件 zip + Zip Slip 防护）

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

- [ ] **Step 3: 修复引用 PluginListVO 旧字段（pluginName/pluginStatus）的代码**

使用 Grep 搜索 `pluginName` 和 `pluginStatus` 在 plugin-l4d2-core 中的使用位置，逐个替换为 `name` 和 `status`。重点关注：
- `service/PluginInstallService.java`（listPlugins 方法构建 VO 处）
- `controller/PluginManageController.java`（如有字段引用）
- `service/PluginExportService.java`（如有字段引用）
- `frontend/src/pages/Plugins.vue`（如有字段引用）

- [ ] **Step 4: 编译并运行测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS，已有测试全部通过

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginListVO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git commit -m "refactor(l4d2): PluginListVO 字段对齐 l4d2-server-next（name/status/source/hasSmx/hasConfig）"
```

---

### Task 2.6a: 创建 RconFailureDetector 工具类

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/RconFailureDetector.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/RconFailureDetectorTest.java`

**参考来源:** `plugins.go:sourceModPluginCommandFailed`（10 个失败标记）

- [ ] **Step 1: 编写失败测试**

```java
package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RconFailureDetectorTest {

    @Test
    void shouldDetectFailureMarkers() {
        assertThat(RconFailureDetector.isFailed("unknown command: sm plugins load")).isTrue();
        assertThat(RconFailureDetector.isFailed("No such command: foo")).isTrue();
        assertThat(RconFailureDetector.isFailed("Failed to load plugin")).isTrue();
        assertThat(RconFailureDetector.isFailed("Error: permission denied")).isTrue();
        assertThat(RconFailureDetector.isFailed("Plugin not found")).isTrue();
        assertThat(RconFailureDetector.isFailed("invalid argument")).isTrue();
        assertThat(RconFailureDetector.isFailed("could not connect")).isTrue();
        assertThat(RconFailureDetector.isFailed("unable to load")).isTrue();
        assertThat(RconFailureDetector.isFailed("Plugin is not loaded")).isTrue();
        assertThat(RconFailureDetector.isFailed("no matching plugin")).isTrue();
    }

    @Test
    void shouldNotFlagSuccessOutput() {
        assertThat(RconFailureDetector.isFailed("")).isFalse();
        assertThat(RconFailureDetector.isFailed("Plugin loaded successfully")).isFalse();
        assertThat(RconFailureDetector.isFailed("[SM] Plugin plugin-a.smx loaded")).isFalse();
        assertThat(RconFailureDetector.isFailed(null)).isFalse();
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertThat(RconFailureDetector.isFailed("FAILED TO LOAD")).isTrue();
        assertThat(RconFailureDetector.isFailed("ERROR")).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=RconFailureDetectorTest`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 RconFailureDetector**

```java
package com.gameplatform.plugin.l4d2.util;

import java.util.List;

/**
 * RCON 命令失败检测器（对齐 l4d2-server-next sourceModPluginCommandFailed）。
 * <p>通过关键字匹配判断 RCON 命令输出是否表示失败。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class RconFailureDetector {

    private static final List<String> FAILURE_MARKERS = List.of(
        "unknown command",
        "no such command",
        "failed",
        "error",
        "not found",
        "invalid",
        "could not",
        "unable to",
        "is not loaded",
        "no matching plugin"
    );

    private RconFailureDetector() {}

    /**
     * 检测 RCON 命令输出是否表示失败。
     *
     * @param output RCON 命令输出
     * @return true 表示失败
     */
    public static boolean isFailed(String output) {
        if (output == null || output.isEmpty()) return false;
        String lower = output.toLowerCase().trim();
        return FAILURE_MARKERS.stream().anyMatch(lower::contains);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=RconFailureDetectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/RconFailureDetector.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/RconFailureDetectorTest.java
git commit -m "feat(l4d2): 新增 RconFailureDetector 工具类（10 个失败标记，对齐 l4d2-server-next）"
```

---

### Task 2.6b: 创建 ZipSlipGuard 工具类

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/ZipSlipGuard.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/ZipSlipGuardTest.java`

**参考来源:** `plugins.go:extractFiles`（Zip Slip 防护）

- [ ] **Step 1: 编写失败测试**

```java
package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipSlipGuardTest {

    @Test
    void shouldAcceptNormalPath() {
        String safe = ZipSlipGuard.normalizeAndCheck("addons/sourcemod/plugins/plugin-a.smx", "addons/sourcemod/plugins_store/plugin-a/left4dead2");
        assertThat(safe).isEqualTo("addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx");
    }

    @Test
    void shouldRejectPathTraversal() {
        assertThatThrownBy(() -> ZipSlipGuard.normalizeAndCheck("../../etc/passwd", "addons/sourcemod/plugins_store/plugin-a/left4dead2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Zip Slip");
    }

    @Test
    void shouldRejectAbsolutePath() {
        assertThatThrownBy(() -> ZipSlipGuard.normalizeAndCheck("/etc/passwd", "addons/sourcemod/plugins_store/plugin-a/left4dead2"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNormalizeBackslash() {
        String safe = ZipSlipGuard.normalizeAndCheck("addons\\sourcemod\\plugins\\plugin-a.smx", "addons/sourcemod/plugins_store/plugin-a/left4dead2");
        assertThat(safe).isEqualTo("addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx");
    }

    @Test
    void shouldRejectMacosxJunk() {
        assertThat(ZipSlipGuard.isMacOSJunk("__MACOSX/._plugin-a.smx")).isTrue();
        assertThat(ZipSlipGuard.isMacOSJunk(".DS_Store")).isTrue();
        assertThat(ZipSlipGuard.isMacOSJunk("addons/sourcemod/plugins/plugin-a.smx")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=ZipSlipGuardTest`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 ZipSlipGuard**

```java
package com.gameplatform.plugin.l4d2.util;

/**
 * Zip Slip 防护工具类（对齐 l4d2-server-next extractFiles 安全检查）。
 * <p>防止恶意 zip 文件通过 ../ 路径遍历到目标目录之外。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class ZipSlipGuard {

    private ZipSlipGuard() {}

    /**
     * 归一化并校验 zip entry 路径，拼接目标目录。
     *
     * @param entryName zip entry 名称
     * @param targetDir 目标目录（相对路径，已归一化）
     * @return 完整的相对路径
     * @throws IllegalArgumentException 如果路径试图越界
     */
    public static String normalizeAndCheck(String entryName, String targetDir) {
        String normalized = entryName.replace('\\', '/');
        // 拒绝绝对路径
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip Slip 检测：禁止绝对路径: " + entryName);
        }
        // 拒绝 .. 路径段
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Zip Slip 检测：禁止路径遍历: " + entryName);
        }
        // 去除前导 ./
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        // 去除段内 /./
        while (normalized.contains("/./")) normalized = normalized.replace("/./", "/");
        // 拼接
        if (targetDir == null || targetDir.isEmpty()) return normalized;
        String separator = targetDir.endsWith("/") ? "" : "/";
        return targetDir + separator + normalized;
    }

    /**
     * 检测是否为 macOS 垃圾文件。
     *
     * @param entryName zip entry 名称
     * @return true 表示应跳过
     */
    public static boolean isMacOSJunk(String entryName) {
        if (entryName == null) return false;
        String normalized = entryName.replace('\\', '/');
        return normalized.startsWith("__MACOSX/") || normalized.endsWith(".DS_Store");
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=ZipSlipGuardTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/ZipSlipGuard.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/ZipSlipGuardTest.java
git commit -m "feat(l4d2): 新增 ZipSlipGuard 工具类（防 zip 路径遍历 + macOS 垃圾过滤）"
```

---

### Task 2.6: 重写 PluginInstallService 为库/游戏分离 + 并发复制 + RCON 失败检测 + 多插件 zip + Zip Slip 防护

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java`

**参考来源:** `plugins.go`（945 行，全部关键方法）

- [ ] **Step 1: 编写失败测试 — listPlugins 扫描 plugins_store**

```java
@Test
void listPlugins_shouldScanPluginsStoreAndMergeEnabledStatus() throws Exception {
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
    assertThat(result.get(1).getSource()).isEqualTo("panel");
    assertThat(result.get(1).getHasSmx()).isFalse();
}

@Test
void enableAndLoad_shouldRollbackOnRconFailure() throws Exception {
    when(instanceFileService.walkFiles(eq(INSTANCE_ID), contains("plugin-a/left4dead2")))
        .thenReturn(List.of(
            createFileInfo("addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx", false)
        ));
    when(instanceFileService.readBytes(eq(INSTANCE_ID), anyString())).thenReturn(new byte[]{1, 2, 3});
    when(rconService.executeCommand(eq(INSTANCE_ID), contains("sm plugins load")))
        .thenThrow(new RuntimeException("[SM] Plugin not found"));

    assertThatThrownBy(() -> pluginInstallService.enableAndLoad(INSTANCE_ID, "plugin-a"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("加载 smx");

    // 验证：已复制的文件被回滚删除
    verify(instanceFileService).deleteFile(eq(INSTANCE_ID), eq("addons/sourcemod/plugins/plugin-a.smx"));
    // 验证：yaml 中未添加该插件
    verify(enabledPluginsService, never()).add(eq(INSTANCE_ID), any());
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PluginInstallServiceTest#listPlugins_shouldScanPluginsStoreAndMergeEnabledStatus`
Expected: FAIL — 当前 listPlugins 仍是扫描 plugins/disabled 模型

- [ ] **Step 3: 重写 PluginInstallService 构造函数与字段**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.RconFailureDetector;
import com.gameplatform.plugin.l4d2.util.ZipSlipGuard;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.service.FileInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /** 并发复制线程池（对齐 l4d2-server-next ants 协程池） */
    private final ExecutorService copyExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()));
    /** 并发复制信号量（限制同时复制的文件数，防止过载） */
    private final Semaphore copySemaphore = new Semaphore(8);
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

- [ ] **Step 5: 实现 installFromLocalFile（入库到 plugins_store/{name}/left4dead2/，支持多插件结构 + Zip Slip 防护）**

```java
public void installFromLocalFile(Long instanceId, File localFile) throws Exception {
    String zipBaseName = localFile.getName().replaceFirst("\\.zip$", "");
    ensureStoreDirectory(instanceId);

    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.FileInputStream(localFile), Charset.forName("GBK"))) {
        java.util.zip.ZipEntry entry;
        // 检测是单插件还是多插件结构：多插件以 plugins/{name}/ 开头
        boolean isMultiPlugin = false;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String rawName = entry.getName().replace('\\', '/');
            if (ZipSlipGuard.isMacOSJunk(rawName)) continue;
            if (rawName.startsWith("plugins/") && rawName.split("/").length >= 3) {
                isMultiPlugin = true;
                break;
            }
        }
    }

    // 重新打开 zip 流处理
    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.FileInputStream(localFile), Charset.forName("GBK"))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String rawName = entry.getName();
            String filtered = ZipSlipGuard.isMacOSJunk(rawName) ? null : rawName.replace('\\', '/');
            if (filtered == null) continue;
            byte[] content = zis.readAllBytes();
            if (isMultiPlugin) {
                // 多插件结构：plugins/{name}/left4dead2/...
                handleMultiPluginEntry(instanceId, filtered, content);
            } else {
                // 单插件结构：直接 left4dead2/... 或裸文件
                handleSinglePluginEntry(instanceId, zipBaseName, filtered, content);
            }
        }
    }

    // 写 plugin.yaml 元数据（source=upload）
    if (isMultiPlugin) {
        // 多插件结构：扫描已写入的 plugins_store 子目录
        List<FileInfo> entries = instanceFileService.listDirectory(instanceId, pathResolver.getPluginsStorePath());
        for (FileInfo e : entries) {
            if (e.isDirectory()) {
                writePluginYaml(instanceId, e.getName(), "upload");
            }
        }
    } else {
        writePluginYaml(instanceId, zipBaseName, "upload");
    }
}

private void handleSinglePluginEntry(Long instanceId, String pluginName, String zipPath, byte[] content) throws Exception {
    String pluginDir = pathResolver.getPluginStorePath(pluginName) + "/left4dead2";
    String prefix = "left4dead2/";
    String targetRel;
    if (zipPath.startsWith(prefix)) {
        targetRel = ZipSlipGuard.normalizeAndCheck(zipPath.substring(prefix.length()), pluginDir);
    } else if (zipPath.startsWith("README") || zipPath.endsWith(".md")) {
        targetRel = ZipSlipGuard.normalizeAndCheck(zipPath, pathResolver.getPluginStorePath(pluginName));
    } else {
        targetRel = ZipSlipGuard.normalizeAndCheck(zipPath, pluginDir);
    }
    instanceFileService.writeBytes(instanceId, targetRel, content);
}

private void handleMultiPluginEntry(Long instanceId, String zipPath, byte[] content) throws Exception {
    // zipPath: plugins/{name}/left4dead2/...
    if (!zipPath.startsWith("plugins/")) return;
    String[] parts = zipPath.split("/", 3);
    if (parts.length < 3) return;
    String pluginName = parts[1];
    String rest = parts[2];
    String pluginDir = pathResolver.getPluginStorePath(pluginName) + "/left4dead2";
    String targetRel = ZipSlipGuard.normalizeAndCheck(rest, pluginDir);
    instanceFileService.writeBytes(instanceId, targetRel, content);
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

private void writePluginYaml(Long instanceId, String pluginName, String source) throws Exception {
    String yamlPath = pathResolver.getPluginYamlPath(pluginName);
    String content = "name: \"" + pluginName + "\"\nsource: \"" + source + "\"\n";
    instanceFileService.writeText(instanceId, yamlPath, content);
}
```

- [ ] **Step 6: 实现 enablePlugin（并发复制 store → 游戏 + 写 yaml，失败回滚已复制文件）**

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

    // 并发复制（对齐 l4d2-server-next ants 协程池）
    List<String> copiedFiles = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Exception> firstErr = new AtomicReference<>();

    List<CompletableFuture<Void>> futures = files.stream()
        .filter(f -> !f.isDirectory())
        .map(file -> CompletableFuture.runAsync(() -> {
            try {
                copySemaphore.acquire();
                try {
                    String relToStore = file.getPath();
                    String relToGameDir = extractRelativeToLeft4Dead2(relToStore, pluginName);
                    if (relToGameDir == null) return;
                    byte[] content = instanceFileService.readBytes(instanceId, file.getPath());
                    instanceFileService.writeBytes(instanceId, relToGameDir, content);
                    copiedFiles.add(relToGameDir);
                } finally {
                    copySemaphore.release();
                }
            } catch (Exception e) {
                firstErr.compareAndSet(null, e);
            }
        }, copyExecutor))
        .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    if (firstErr.get() != null) {
        // 回滚：删除已复制的文件
        rollbackCopiedFiles(instanceId, copiedFiles);
        throw new RuntimeException("启用插件 " + pluginName + " 失败: " + firstErr.get().getMessage(), firstErr.get());
    }

    // 写 yaml + 更新引用计数
    EnabledPlugin plugin = new EnabledPlugin();
    plugin.setName(pluginName);
    plugin.setSource(detectSource(instanceId, pluginName));
    plugin.setEnabledAt(System.currentTimeMillis());
    plugin.setFiles(new ArrayList<>(copiedFiles));
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

- [ ] **Step 7: 实现 enableAndLoad（复制 + RCON load + 失败回滚，使用 RconFailureDetector）**

```java
public void enableAndLoad(Long instanceId, String pluginName) throws Exception {
    List<String> smxIds = listPluginSmxIds(instanceId, pluginName);
    if (smxIds.isEmpty()) {
        throw new IllegalStateException("插件 " + pluginName + " 未找到 .smx 文件");
    }

    // 先复制文件 + 写 yaml
    enablePlugin(instanceId, pluginName);

    // RCON 加载（对齐 l4d2-server-next EnableAndLoadPlugin）
    List<String> loadedIds = new ArrayList<>();
    for (String smxId : smxIds) {
        try {
            String output = rconService.executeCommand(instanceId, "sm plugins load " + smxId);
            if (RconFailureDetector.isFailed(output)) {
                throw new RuntimeException("加载 smx " + smxId + " 失败: " + output);
            }
            loadedIds.add(smxId);
        } catch (Exception e) {
            // 回滚1：unload 已加载的 smx
            for (int i = loadedIds.size() - 1; i >= 0; i--) {
                try {
                    rconService.executeCommand(instanceId, "sm plugins unload " + loadedIds.get(i));
                } catch (Exception ignore) {}
            }
            // 回滚2：禁用插件（删除已复制文件 + 移除 yaml）
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

- [ ] **Step 9: 实现 disableAndUnload（RCON 逆序 unload + disablePlugin，使用 RconFailureDetector）**

```java
public void disableAndUnload(Long instanceId, String pluginName) throws Exception {
    List<String> smxIds = listPluginSmxIds(instanceId, pluginName);
    List<String> unloadedIds = new ArrayList<>();
    // 逆序 unload（对齐 l4d2-server-next DisableAndUnloadPlugin）
    for (int i = smxIds.size() - 1; i >= 0; i--) {
        String smxId = smxIds.get(i);
        try {
            String output = rconService.executeCommand(instanceId, "sm plugins unload " + smxId);
            if (RconFailureDetector.isFailed(output)) {
                throw new RuntimeException("卸载 smx " + smxId + " 失败: " + output);
            }
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
    // 清理 plugin_sources 记录（已在 enabledPluginsService.remove 中处理）
}
```

- [ ] **Step 11: 实现 enablePlatformPlugin + disableAllPlugins + 批量方法 + readReadme + 兼容包装**

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

- [ ] **Step 12: 实现 installFromUpload**

```java
public PluginListVO installFromUpload(Long instanceId, MultipartFile file) throws Exception {
    File tempFile = File.createTempFile("l4d2-plugin-", ".zip");
    try {
        file.transferTo(tempFile);
        installFromLocalFile(instanceId, tempFile);
        // 多插件结构：返回第一个插件的 VO；单插件：返回该插件
        List<PluginListVO> plugins = listPlugins(instanceId);
        // 简化处理：返回最后一个（最新添加的）
        if (plugins.isEmpty()) {
            PluginListVO vo = new PluginListVO();
            vo.setName(tempFile.getName().replaceFirst("\\.zip$", ""));
            vo.setStatus("disabled");
            vo.setSource("upload");
            return vo;
        }
        return plugins.get(plugins.size() - 1);
    } finally {
        if (!tempFile.delete()) {
            tempFile.deleteOnExit();
        }
    }
}
```

- [ ] **Step 13: 运行所有测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS

- [ ] **Step 14: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceTest.java
git commit -m "refactor(l4d2): PluginInstallService 重写为库/游戏分离 + 并发复制 + RCON 失败检测 + 多插件 zip + Zip Slip 防护"
```

---

## 阶段 3：预设系统重构（不调 RCON）

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
    gameMode: versus
    maxPlayers: 8
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
    gameMode: versus
    maxPlayers: 8
    plugins: []
  - id: pure-coop
    name: "纯净战役"
    description: "仅启用基础平台插件，无任何附加功能"
    gameMode: coop
    maxPlayers: 4
    plugins: []
  - id: official-roguelike
    name: "官图肉鸽模式"
    description: "官图肉鸽玩法"
    gameMode: coop
    maxPlayers: 4
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

### Task 3.3: 重写 PresetService（不调 RCON）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java`

**参考来源:** `preset.go:ApplyPreset`（仅 EnablePlugin，不调 RCON）

- [ ] **Step 1: 编写失败测试**

```java
@Test
void apply_shouldCallNewMethodChainWithoutRcon() throws Exception {
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

    verify(pluginInstallService).disableAllPlugins(eq(instanceId));
    verify(pluginInstallService).enablePlatformPlugin(eq(instanceId), contains("platform-plugin"));
    verify(pluginInstallService).enablePlugin(eq(instanceId), eq("plugin-a"));
    verify(cfgService).updateOrCreateConfig(eq(instanceId), eq("plugin-a.cfg"), eq(Map.of("key1", "value1")));
    // 验证：不调用 enableAndLoad（不调 RCON）
    verify(pluginInstallService, never()).enableAndLoad(eq(instanceId), anyString());
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=PresetServiceTest#apply_shouldCallNewMethodChainWithoutRcon`
Expected: FAIL

- [ ] **Step 3: 重写 PresetService.apply（仅复制文件，不调 RCON）**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.PresetConfig;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.l4d2.vo.PresetPlugin;
import com.gameplatform.plugin.l4d2.vo.PresetPluginConfig;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * 应用预设（对齐 l4d2-server-next ApplyPreset，仅复制文件，不调 RCON）。
     * <p>应用后需重启服务器或手动 sm plugins load 才能生效。
     */
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

        // 4. 顺序启用其他插件（跳过平台插件，仅复制文件不调 RCON）
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
git commit -m "refactor(l4d2): PresetService.apply 对齐 l4d2-server-next（仅复制文件不调 RCON）"
```

---

## 阶段 4：商店增强（DTO + 原子提交 + LFS 大小校验 + 3 并发 + 重试 + 任务去重 + README 在线读取）

### Task 4.1: 创建/扩展 Store DTOs

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreQueryDTO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java`（已存在，需扩展字段）

- [ ] **Step 1: 创建 PluginStoreListDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 插件商店列表请求 DTO（支持自定义仓库/代理/Token）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店列表请求")
public class PluginStoreListDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "搜索关键字")
    private String keyword;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "自定义仓库 owner/repo（可选，默认从 L4D2Config 读取）")
    private String repo;

    @Schema(description = "GitHub 代理 URL 前缀（可选）")
    private String proxyUrl;

    @Schema(description = "GitHub Token（可选，提升限流到 5000/h）")
    private String githubToken;

    @Schema(description = "是否强制刷新缓存")
    private Boolean forceRefresh;
}
```

- [ ] **Step 2: 创建 PluginStoreQueryDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 插件商店详情/README 查询请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店详情查询请求")
public class PluginStoreQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "插件ID不能为空")
    @Schema(description = "插件ID（仓库子目录名）", required = true)
    private String pluginId;

    @Schema(description = "自定义仓库 owner/repo（可选）")
    private String repo;

    @Schema(description = "GitHub 代理 URL 前缀（可选）")
    private String proxyUrl;

    @Schema(description = "GitHub Token（可选）")
    private String githubToken;
}
```

- [ ] **Step 3: 扩展 PluginStoreDownloadDTO（已存在，新增 repo/proxyUrl/githubToken 字段）**

读取现有 `PluginStoreDownloadDTO.java`，在现有字段基础上新增：

```java
// 在现有字段（instanceId, pluginId, targetPath）之后新增：

@Schema(description = "自定义仓库 owner/repo（可选）")
private String repo;

@Schema(description = "GitHub 代理 URL 前缀（可选）")
private String proxyUrl;

@Schema(description = "GitHub Token（可选）")
private String githubToken;
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreListDTO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreQueryDTO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginStoreDownloadDTO.java
git commit -m "feat(l4d2): 新增 PluginStoreListDTO/QueryDTO + 扩展 PluginStoreDownloadDTO（支持 repo/proxyUrl/githubToken）"
```

---

### Task 4.2: 增强 GitHubApiClient（支持参数重载 + LFS 大小校验 + treeCache + README 在线读取）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/ExternalHttpClient.java`（如需新增带代理的方法）

**参考来源:** `plugin_store.go:getTreeData` + `downloadGitLFSObjectIfNeeded`（LFS 大小校验）

**重要架构说明：**
- GitHubApiClient 使用 `ExternalHttpClient` 进行 HTTP 请求（非 `java.net.http.HttpClient`）
- 仓库/分支从 `L4D2Config.PluginStore` 读取（非字段）
- `TreeEntry` 是 record：`(path, type, sha, size)`
- 无 treeCache，需新增缓存字段

- [ ] **Step 1: 新增 treeCache 字段和辅助方法**

在 GitHubApiClient 类中新增：

```java
// 新增字段
private final Map<String, List<TreeEntry>> treeCache = new java.util.concurrent.ConcurrentHashMap<>();
private final Map<String, Long> treeCacheTime = new java.util.concurrent.ConcurrentHashMap<>();
private static final long TREE_CACHE_TTL_MS = 10 * 60 * 1000; // 10 分钟

// 新增辅助方法
private String resolveRepo(String repo) {
    if (repo != null && !repo.isBlank()) return repo.trim();
    return config.getPluginStore().getRepo();
}

private String resolveBranch(String branch) {
    if (branch != null && !branch.isBlank()) return branch.trim();
    return config.getPluginStore().getBranch();
}

private String applyProxy(String proxyUrl, String targetUrl) {
    if (proxyUrl == null || proxyUrl.isEmpty()) return targetUrl;
    // 代理 URL 拼接：proxyUrl + targetUrl
    return proxyUrl.endsWith("/") ? proxyUrl + targetUrl : proxyUrl + "/" + targetUrl;
}

private Map<String, ?> buildAuthParamsWithToken(String githubToken) {
    if (githubToken != null && !githubToken.isBlank()) {
        return Map.of("access_token", githubToken);
    }
    return buildAuthParams(); // 回退到环境变量
}
```

- [ ] **Step 2: 新增带参数的 getTree 重载**

```java
/**
 * 获取仓库分支的递归目录树（带参数，支持自定义仓库/代理/Token + 缓存）。
 */
@SuppressWarnings("unchecked")
public List<TreeEntry> getTree(String proxyUrl, String githubToken, String repo, Boolean forceRefresh) {
    String effectiveRepo = resolveRepo(repo);
    String effectiveBranch = resolveBranch(null);
    String cacheKey = effectiveRepo + ":" + effectiveBranch;

    // 缓存检查
    if (!Boolean.TRUE.equals(forceRefresh)) {
        Long cachedAt = treeCacheTime.get(cacheKey);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < TREE_CACHE_TTL_MS) {
            return treeCache.getOrDefault(cacheKey, List.of());
        }
    }

    String url = String.format("%s/%s/git/trees/%s?recursive=1",
            GITHUB_API_BASE, effectiveRepo, effectiveBranch);
    url = applyProxy(proxyUrl, url);

    Map<String, Object> resp = httpClient.getForObject(url, Map.class, buildAuthParamsWithToken(githubToken));
    if (resp == null) {
        return List.of();
    }
    Object treeObj = resp.get("tree");
    if (!(treeObj instanceof List<?> rawList)) {
        return List.of();
    }
    List<TreeEntry> result = new ArrayList<>(rawList.size());
    for (Object item : rawList) {
        if (!(item instanceof Map<?, ?> m)) continue;
        String path = asString(m.get("path"));
        String type = asString(m.get("type"));
        String sha = asString(m.get("sha"));
        long size = asLong(m.get("size"));
        result.add(new TreeEntry(path, type, sha, size));
    }
    treeCache.put(cacheKey, result);
    treeCacheTime.put(cacheKey, System.currentTimeMillis());
    return result;
}

/** 兼容原方法：从 config 读取参数 */
public List<TreeEntry> getTree() {
    return getTree(null, null, null, false);
}
```

- [ ] **Step 3: 新增带参数的 getBlobContent 重载**

```java
/**
 * 获取 Blob 内容（带参数）。
 */
@SuppressWarnings("unchecked")
public String getBlobContent(String sha, String proxyUrl, String githubToken, String repo) {
    if (sha == null || sha.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "Blob SHA 不能为空");
    }
    String effectiveRepo = resolveRepo(repo);
    String url = String.format("%s/%s/git/blobs/%s", GITHUB_API_BASE, effectiveRepo, sha);
    url = applyProxy(proxyUrl, url);

    Map<String, Object> resp = httpClient.getForObject(url, Map.class, buildAuthParamsWithToken(githubToken));
    if (resp == null) return null;
    String content = asString(resp.get("content"));
    String encoding = asString(resp.get("encoding"));
    if (content == null) return null;
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

/** 兼容原方法 */
public String getBlobContent(String sha) {
    return getBlobContent(sha, null, null, null);
}
```

- [ ] **Step 4: 新增带参数的 batchLfsObjects 重载**

```java
/**
 * 批量获取 LFS 对象的真实下载 URL（带参数）。
 */
@SuppressWarnings("unchecked")
public Map<String, String> batchLfsObjects(List<String> oids, String proxyUrl, String githubToken, String repo) {
    Map<String, String> result = new HashMap<>();
    if (oids == null || oids.isEmpty()) return result;

    String effectiveRepo = resolveRepo(repo);
    String url = String.format("%s/%s/info/lfs/objects/batch", GITHUB_API_BASE, effectiveRepo);
    url = applyProxy(proxyUrl, url);

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

    Map<String, Object> resp = httpClient.postForObject(url, body, Map.class);
    if (resp == null) return result;
    Object objsObj = resp.get("objects");
    if (!(objsObj instanceof List<?> list)) return result;
    for (Object item : list) {
        if (!(item instanceof Map<?, ?> m)) continue;
        String oid = asString(m.get("oid"));
        Object actions = m.get("actions");
        if (!(actions instanceof Map<?, ?> am)) continue;
        Object download = am.get("download");
        if (!(download instanceof Map<?, ?> dm)) continue;
        String href = asString(dm.get("href"));
        if (oid != null && href != null) {
            result.put(oid, href);
        }
    }
    return result;
}

/** 兼容原方法 */
public Map<String, String> batchLfsObjects(List<String> oids) {
    return batchLfsObjects(oids, null, null, null);
}
```

- [ ] **Step 5: 新增带参数的 downloadLfsObject 重载（含大小校验）**

```java
/**
 * 下载 LFS 对象到目标文件（带参数 + 大小校验）。
 *
 * <p>对齐 l4d2-server-next downloadGitLFSObjectIfNeeded，下载后校验文件大小。
 */
public void downloadLfsObject(String oid, long expectedSize, File target,
                               String proxyUrl, String githubToken, String repo) {
    if (oid == null || oid.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "LFS OID 不能为空");
    }
    Map<String, String> urls = batchLfsObjects(List.of(oid), proxyUrl, githubToken, repo);
    String url = urls.get(oid);
    if (url == null) {
        throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                "LFS 对象不存在或无可下载链接: " + oid);
    }
    // 应用代理到 LFS 下载 URL（如需）
    url = applyProxy(proxyUrl, url);

    File downloaded = httpClient.download(url, target.getName(), null, null, null);
    if (downloaded == null) {
        throw new L4D2PluginException(L4D2PluginException.NETWORK, "LFS 对象下载失败: " + oid);
    }

    // LFS 大小校验（对齐 l4d2-server-next）
    if (expectedSize > 0) {
        long actualSize = downloaded.length();
        if (actualSize != expectedSize) {
            downloaded.delete();
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    String.format("LFS 大小校验失败: expected=%d, actual=%d, oid=%s",
                            expectedSize, actualSize, oid));
        }
    }

    if (downloaded.getAbsolutePath().equals(target.getAbsolutePath())) return;
    File parent = target.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw new L4D2PluginException(L4D2PluginException.FILE, "创建目标目录失败: " + parent.getAbsolutePath());
    }
    try {
        Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE, "移动下载文件失败: " + e.getMessage(), e);
    }
}

/** 兼容原方法（无大小校验） */
public void downloadLfsObject(String oid, File target) {
    downloadLfsObject(oid, 0, target, null, null, null);
}
```

- [ ] **Step 6: 新增 fetchReadme 方法（GitHub raw 在线读取）**

```java
/**
 * 下载商店插件 README（从 GitHub raw，对齐 l4d2-server-next FetchStorePluginReadme）。
 *
 * @param pluginName 插件名（仓库内 plugins/{pluginName}/ 子目录名）
 * @return README 内容（不存在返回空字符串）
 */
@SuppressWarnings("unchecked")
public String fetchReadme(String pluginName, String proxyUrl, String githubToken, String repo) {
    String effectiveRepo = resolveRepo(repo);
    String effectiveBranch = resolveBranch(null);
    String url = String.format("https://raw.githubusercontent.com/%s/%s/plugins/%s/README.md",
            effectiveRepo, effectiveBranch, pluginName);
    url = applyProxy(proxyUrl, url);
    try {
        Map<String, Object> resp = httpClient.getForObject(url, Map.class, buildAuthParamsWithToken(githubToken));
        if (resp == null) return "";
        // raw.githubusercontent.com 返回纯文本，ExternalHttpClient 可能返回 {content: "..."} 或直接字符串
        Object content = resp.get("content");
        if (content == null) {
            // 尝试直接转为字符串
            return resp.toString();
        }
        return content.toString();
    } catch (Exception e) {
        log.warn("获取 README 失败 pluginName={}, err={}", pluginName, e.getMessage());
        return "";
    }
}
```

- [ ] **Step 7: 新增 LFS 指针解析辅助方法**

```java
/**
 * 从 LFS 指针文件内容解析 OID。
 * <p>LFS 指针格式：
 * <pre>
 * version https://git-lfs.github.com/spec/v1
 * oid sha256:abc123...
 * size 12345
 * </pre>
 */
public String parseLfsOid(String lfsPointerContent) {
    if (lfsPointerContent == null) return null;
    for (String line : lfsPointerContent.split("\n")) {
        if (line.startsWith("oid sha256:")) {
            return line.substring("oid sha256:".length()).trim();
        }
    }
    return null;
}

/**
 * 从 LFS 指针文件内容解析文件大小。
 */
public long parseLfsSize(String lfsPointerContent) {
    if (lfsPointerContent == null) return 0;
    for (String line : lfsPointerContent.split("\n")) {
        if (line.startsWith("size ")) {
            try {
                return Long.parseLong(line.substring("size ".length()).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
    return 0;
}
```

- [ ] **Step 8: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java
git commit -m "feat(l4d2): GitHubApiClient 新增 proxyUrl/githubToken/repo 参数重载 + treeCache + README 在线读取 + LFS 大小校验"
```

---

### Task 4.3: 增强 PluginStoreService（DTO 重载 + 原子提交 + LFS 大小校验 + 3 并发 + 1秒×3重试 + 任务去重）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginStoreDownloadTaskVO.java`（扩展字段）

**参考来源:** `plugin_store.go`（826 行，全部关键逻辑）

**重要：使用现有 PluginStoreDownloadTaskVO（非 StoreDownloadTaskVO），状态值用大写**

- [ ] **Step 1: 扩展 PluginStoreDownloadTaskVO 字段**

在现有 `PluginStoreDownloadTaskVO.java` 中新增字段（保留现有字段）：

```java
// 在现有字段之后新增：

@Schema(description = "总文件数")
private int total;

@Schema(description = "已下载文件数")
private int downloaded;

@Schema(description = "任务消息")
private String message;
```

- [ ] **Step 2: 新增 DTO 重载方法**

在 PluginStoreService 中新增：

```java
public List<PluginStoreItemVO> list(PluginStoreListDTO dto) {
    return listInternal(dto.getKeyword(), dto.getCategory(), dto.getRepo(),
        dto.getProxyUrl(), dto.getGithubToken(), Boolean.TRUE.equals(dto.getForceRefresh()));
}

public PluginStoreDetailVO detail(String pluginId, PluginStoreQueryDTO dto) {
    return detailInternal(pluginId, dto.getRepo(), dto.getProxyUrl(), dto.getGithubToken());
}

public String fetchReadme(String pluginId, PluginStoreQueryDTO dto) {
    return gitHubApiClient.fetchReadme(pluginId, dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
}

public String download(PluginStoreDownloadDTO dto) {
    return downloadInternal(dto);
}
```

- [ ] **Step 3: 实现下载临时目录原子提交 + 3 并发 + 1秒×3重试 + LFS 大小校验 + 任务去重**

修改 `downloadInternal` 方法（使用现有 PluginStoreDownloadTaskVO 和大写状态值）：

```java
/** 下载并发度（对齐 l4d2-server-next StorePluginDownloadConcurrency=3） */
private static final int DOWNLOAD_CONCURRENCY = 3;
/** 全局下载信号量（跨任务共享） */
private final Semaphore globalDownloadSemaphore = new Semaphore(DOWNLOAD_CONCURRENCY);
/** 下载重试次数 */
private static final int DOWNLOAD_RETRIES = 3;
/** 任务状态常量 */
private static final String STATUS_DOWNLOADING = "DOWNLOADING";
private static final String STATUS_COMPLETED = "COMPLETED";
private static final String STATUS_FAILED = "FAILED";
private static final String STATUS_PENDING = "PENDING";

private String downloadInternal(PluginStoreDownloadDTO dto) {
    // 任务去重（对齐 l4d2-server-next getStoreDownloadTaskKey）
    String taskKey = normalizeRepo(dto.getRepo()) + "\0" + dto.getPluginId();
    PluginStoreDownloadTaskVO existing = tasks.values().stream()
        .filter(t -> dto.getInstanceId().equals(t.getInstanceId())
                  && dto.getPluginId().equals(t.getPluginId())
                  && isActive(t.getStatus()))
        .findFirst().orElse(null);
    if (existing != null) {
        throw new IllegalStateException("该插件正在下载中: " + dto.getPluginId());
    }

    String taskId = UUID.randomUUID().toString();
    String localTempDir = System.getProperty("java.io.tmpdir") + "/game-platform-l4d2/store-tasks/" + taskId;
    new File(localTempDir).mkdirs();

    PluginStoreDownloadTaskVO task = new PluginStoreDownloadTaskVO();
    task.setTaskId(taskId);
    task.setInstanceId(dto.getInstanceId());
    task.setPluginId(dto.getPluginId());
    task.setStatus(STATUS_PENDING);
    task.setStartedAt(LocalDateTime.now());
    task.setProgress(0);
    task.setTotal(0);
    task.setDownloaded(0);
    tasks.put(taskId, task);

    final String tempDir = localTempDir;
    CompletableFuture.runAsync(() -> {
        try {
            globalDownloadSemaphore.acquire();
            try {
                task.setStatus(STATUS_DOWNLOADING);
                List<GitHubApiClient.TreeEntry> tree = gitHubApiClient.getTree(
                    dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo(), false);
                List<GitHubApiClient.TreeEntry> pluginFiles = tree.stream()
                    .filter(e -> "blob".equals(e.type()))
                    .filter(e -> e.path().startsWith("plugins/" + dto.getPluginId() + "/"))
                    .collect(Collectors.toList());

                task.setTotal(pluginFiles.size());

                // 1. 并发下载到本地临时目录（3 worker）
                downloadFilesConcurrent(pluginFiles, tempDir, dto, task);

                // 2. 上传到远程 plugins_store
                String remotePluginDir = pathResolver.getPluginStorePath(dto.getPluginId()) + "/left4dead2";
                uploadLocalToRemote(tempDir + "/plugins/" + dto.getPluginId() + "/left4dead2",
                    dto.getInstanceId(), remotePluginDir);

                // 3. 写 plugin.yaml（source=store）
                writePluginYaml(dto.getInstanceId(), dto.getPluginId(), "store");

                task.setStatus(STATUS_COMPLETED);
                task.setProgress(100);
                task.setMessage("下载完成");
                task.setFinishedAt(LocalDateTime.now());
            } finally {
                globalDownloadSemaphore.release();
            }
        } catch (Exception e) {
            log.error("商店下载失败 taskId={}, err={}", taskId, e.getMessage(), e);
            task.setStatus(STATUS_FAILED);
            task.setMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
        } finally {
            // 清理本地临时目录
            deleteLocalDirectory(new File(tempDir));
        }
    });

    return taskId;
}

private void downloadFilesConcurrent(List<GitHubApiClient.TreeEntry> files, String localTempDir,
                                       PluginStoreDownloadDTO dto, PluginStoreDownloadTaskVO task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(DOWNLOAD_CONCURRENCY, files.size()));
    AtomicReference<Exception> firstErr = new AtomicReference<>();
    AtomicInteger downloaded = new AtomicInteger(0);

    List<CompletableFuture<Void>> futures = files.stream()
        .map(file -> CompletableFuture.runAsync(() -> {
            try {
                downloadOneWithRetry(file, localTempDir, dto);
                downloaded.incrementAndGet();
                task.setDownloaded(downloaded.get());
                // progress = downloaded / total * 100
                if (task.getTotal() > 0) {
                    task.setProgress(downloaded.get() * 100 / task.getTotal());
                }
            } catch (Exception e) {
                firstErr.compareAndSet(null, e);
            }
        }, executor))
        .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    executor.shutdown();

    if (firstErr.get() != null) {
        throw firstErr.get();
    }
}

private void downloadOneWithRetry(GitHubApiClient.TreeEntry file, String localTempDir,
                                    PluginStoreDownloadDTO dto) throws Exception {
    Exception lastErr = null;
    for (int i = 0; i < DOWNLOAD_RETRIES; i++) {
        try {
            downloadOneToLocal(file, localTempDir, dto);
            return;
        } catch (Exception e) {
            lastErr = e;
            if (i < DOWNLOAD_RETRIES - 1) {
                Thread.sleep(1000); // 固定 1 秒退避
            }
        }
    }
    throw lastErr;
}

private void downloadOneToLocal(GitHubApiClient.TreeEntry file, String localTempDir,
                                 PluginStoreDownloadDTO dto) throws Exception {
    String relPath = file.path();
    String localPath = localTempDir + "/" + relPath;
    new File(localPath).getParentFile().mkdirs();

    String content = gitHubApiClient.getBlobContent(file.sha(), dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
    if (gitHubApiClient.isLfsPointer(content)) {
        // LFS 流程（含大小校验）
        String oid = gitHubApiClient.parseLfsOid(content);
        long expectedSize = gitHubApiClient.parseLfsSize(content);
        gitHubApiClient.downloadLfsObject(oid, expectedSize, new File(localPath),
            dto.getProxyUrl(), dto.getGithubToken(), dto.getRepo());
    } else {
        java.nio.file.Files.write(java.nio.file.Paths.get(localPath),
            content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            // 使用 SPI 的 uploadLocalFile 方法
            instanceFileService.uploadLocalFile(instanceId, remotePath, file.getAbsolutePath());
        }
    }
}

private boolean isActive(String status) {
    return STATUS_PENDING.equals(status) || STATUS_DOWNLOADING.equals(status);
}

private String normalizeRepo(String repo) {
    if (repo == null || repo.isBlank()) return "LaoYutang/l4d2-plugins-store";
    return repo.trim();
}

private void deleteLocalDirectory(File dir) {
    if (dir == null || !dir.exists()) return;
    File[] files = dir.listFiles();
    if (files != null) {
        for (File f : files) {
            if (f.isDirectory()) deleteLocalDirectory(f);
            else f.delete();
        }
    }
    dir.delete();
}

private void writePluginYaml(Long instanceId, String pluginName, String source) throws Exception {
    String yamlPath = pathResolver.getPluginYamlPath(pluginName);
    String content = "name: \"" + pluginName + "\"\nsource: \"" + source + "\"\n";
    instanceFileService.writeTextFile(instanceId, yamlPath, content);
}
```

- [ ] **Step 4: 旧方法改为包装**

```java
public List<PluginStoreItemVO> list(String keyword, String category) {
    PluginStoreListDTO dto = new PluginStoreListDTO();
    dto.setKeyword(keyword);
    dto.setCategory(category);
    return list(dto);
}
```

- [ ] **Step 5: 编译验证 + 测试**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginStoreDownloadTaskVO.java
git commit -m "feat(l4d2): PluginStoreService 支持 DTO 参数 + 临时目录原子提交 + LFS 大小校验 + 3 并发 + 1秒×3重试 + 任务去重"
```

---

## 阶段 5：配置编辑增强（restoreFormat + 控制台黑名单 + 文件头过滤 + l4d2↔l4d 互转 + 3 方法）

### Task 5.1: SourceModCfgParser 增强（restoreFormat + 控制台黑名单 + 文件头过滤）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`

**参考来源:** `config_parser.go`（259 行，全部关键逻辑）

- [ ] **Step 1: 编写失败测试**

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

@Test
void parse_shouldFilterConsoleCommands() {
    String content = "// 注释\nsm \"1\"\nexec \"config.cfg\"\nmeta list\nl4d_csm_admin_flags \"\"";
    List<ConfigItem> items = cfgParser.parse(content);
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getKey()).isEqualTo("l4d_csm_admin_flags");
}

@Test
void parse_shouldFilterFileHeader() {
    String content = "// This file was auto-generated by SourceMod\n// ConVars for plugin \"plugin-a.smx\"\n// 真正的注释\nl4d_csm_admin_flags \"\"";
    List<ConfigItem> items = cfgParser.parse(content);
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getDescription()).isEqualTo("真正的注释");
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest`
Expected: FAIL — restoreFormat 方法不存在，parse 未过滤控制台命令

- [ ] **Step 3: 增强 parse 方法（添加控制台黑名单 + 文件头过滤）**

```java
// 控制台命令黑名单（对齐 l4d2-server-next consoleCmdNames）
private static final Set<String> CONSOLE_CMD_BLACKLIST = Set.of("sm", "exec", "meta", "rcon");

// 文件头过滤标记（对齐 l4d2-server-next ParseSourceModConfig）
private static final List<String> FILE_HEADER_MARKERS = List.of(
    "This file was auto-generated",
    "ConVars for plugin"
);

public List<ConfigItem> parse(String content) {
    List<ConfigItem> items = new ArrayList<>();
    List<String> commentBuffer = new ArrayList<>();
    String[] lines = content.split("\n");

    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;

        if (trimmed.startsWith("//")) {
            commentBuffer.add(trimmed);
            continue;
        }

        // 尝试匹配 cvar 行
        Matcher matcher = CVAR_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            String name = matcher.group(1);
            String value = matcher.group(2);

            // 控制台命令过滤
            if (CONSOLE_CMD_BLACKLIST.contains(name.toLowerCase())) {
                commentBuffer.clear();
                continue;
            }

            ConfigItem item = new ConfigItem();
            item.setKey(name);
            item.setValue(value);

            List<String> descLines = new ArrayList<>();
            for (String comment : commentBuffer) {
                String cleanComment = comment.substring(2).trim();
                // 先检查元数据
                Matcher defaultMatcher = DEFAULT_PATTERN.matcher(comment);
                Matcher minMatcher = MIN_PATTERN.matcher(comment);
                Matcher maxMatcher = MAX_PATTERN.matcher(comment);
                if (defaultMatcher.matches()) {
                    item.setDefaultValue(defaultMatcher.group(1));
                } else if (minMatcher.matches()) {
                    item.setMin(minMatcher.group(1));
                } else if (maxMatcher.matches()) {
                    item.setMax(maxMatcher.group(1));
                } else if (cleanComment.equals("-")) {
                    // 分隔符，忽略
                } else if (isFileHeader(cleanComment)) {
                    // 文件头过滤
                    continue;
                } else {
                    descLines.add(cleanComment);
                }
            }
            item.setDescription(String.join("\n", descLines));
            items.add(item);
            commentBuffer.clear();
        } else {
            commentBuffer.clear();
        }
    }
    return items;
}

private boolean isFileHeader(String comment) {
    return FILE_HEADER_MARKERS.stream().anyMatch(comment::contains);
}
```

- [ ] **Step 4: 实现 restoreFormat**

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

- [ ] **Step 5: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgParserTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): SourceModCfgParser 新增 restoreFormat + 控制台黑名单 + 文件头过滤"
```

---

### Task 5.2: SourceModCfgService 增强（3 方法 + l4d2↔l4d 互转候选 + 注入 RconService）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**参考来源:** `plugin_config.go:getPluginConfigCandidates`（l4d2↔l4d 互转） + `config_parser.go`（三种写入操作）

- [ ] **Step 1: 编写失败测试**

```java
@Test
void applyTempConfig_shouldCallRconSmCvar() {
    cfgService.applyTempConfig(INSTANCE_ID, "l4d_csm_admin_flags", "z");
    verify(rconService).executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d_csm_admin_flags \"z\""));
}

@Test
void restoreDefaults_shouldUpdateValuesFromDefaultMetadata() throws Exception {
    when(instanceFileService.fileExists(eq(INSTANCE_ID), contains("plugin-a.cfg"))).thenReturn(true);
    when(instanceFileService.readText(eq(INSTANCE_ID), contains("plugin-a.cfg")))
        .thenReturn("// Default: \"0\"\nbots_give_slot0 \"2079\"");

    cfgService.restoreDefaults(INSTANCE_ID, "plugin-a");

    verify(instanceFileService).writeText(eq(INSTANCE_ID), contains("plugin-a.cfg"), contains("bots_give_slot0 \"0\""));
}

@Test
void updateOrCreateConfig_shouldCreateIfNotExist() throws Exception {
    when(instanceFileService.fileExists(eq(INSTANCE_ID), eq("cfg/sourcemod/new-cfg.cfg"))).thenReturn(false);

    cfgService.updateOrCreateConfig(INSTANCE_ID, "new-cfg.cfg", Map.of("key1", "value1"));

    verify(instanceFileService).writeText(eq(INSTANCE_ID), eq("cfg/sourcemod/new-cfg.cfg"), anyString());
}

@Test
void getCandidates_shouldIncludeL4d2L4dAliases() {
    // 插件名 l4d2_damage_show，应推导出 l4d2_damage_show.cfg 和 l4d_damage_show.cfg 两个候选
    List<CandidatePathVO> candidates = cfgService.getCandidatePaths(INSTANCE_ID, "l4d2_damage_show");
    assertThat(candidates).extracting("path").contains("cfg/sourcemod/l4d2_damage_show.cfg", "cfg/sourcemod/l4d_damage_show.cfg");
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

- [ ] **Step 4: 扩展 CandidatePathVO（新增 alias 字段）**

修改 `vo/CandidatePathVO.java`，在现有字段（path, exists）基础上新增 `alias` 字段：

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

/**
 * 候选 cfg 文件路径响应 VO。
 */
@Data
public class CandidatePathVO {
    /** 相对 left4dead2 目录的路径 */
    private String path;
    /** 文件是否存在 */
    private boolean exists;
    /** 别名配置文件路径（l4d2↔l4d 互转关系，null 表示无别名） */
    private String alias;
}
```

- [ ] **Step 5: 增强 getCandidatePaths（l4d2↔l4d 互转，对齐 plugin_config.go）**

```java
/**
 * 获取插件配置候选路径（含 l4d2↔l4d 互转，对齐 l4d2-server-next getPluginConfigCandidates）。
 */
public List<CandidatePathVO> getCandidatePaths(Long instanceId, String pluginName) {
    List<CandidatePathVO> candidates = new ArrayList<>();
    String baseName = pluginName.endsWith(".smx") ? pluginName.substring(0, pluginName.length() - 4) : pluginName;

    // 主候选
    candidates.add(createCandidate(instanceId, baseName + ".cfg"));

    // l4d2 ↔ l4d 互转
    if (baseName.startsWith("l4d2_")) {
        String alias = "l4d_" + baseName.substring(5);
        candidates.add(createCandidate(instanceId, alias + ".cfg", baseName + ".cfg"));
    } else if (baseName.startsWith("l4d_")) {
        String alias = "l4d2_" + baseName.substring(4);
        candidates.add(createCandidate(instanceId, alias + ".cfg", baseName + ".cfg"));
    }

    return candidates;
}

private CandidatePathVO createCandidate(Long instanceId, String cfgName) {
    return createCandidate(instanceId, cfgName, null);
}

private CandidatePathVO createCandidate(Long instanceId, String cfgName, String aliasOf) {
    String path = "cfg/sourcemod/" + cfgName;
    boolean exists = false;
    try {
        exists = instanceFileService.exists(instanceId, path);
    } catch (Exception ignore) {}
    CandidatePathVO vo = new CandidatePathVO();
    vo.setPath(path);
    vo.setExists(exists);
    vo.setAlias(aliasOf);
    return vo;
}
```

- [ ] **Step 6: 实现 applyTempConfig（RCON sm_cvar 即时生效）**

```java
/**
 * 临时应用 CVAR（通过 RCON sm_cvar，对齐 l4d2-server-next 前端 applyTempConfig）。
 * <p>仅运行中服务器内存生效，重启或 reload 后丢失。
 */
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
    String cmd = String.format("sm_cvar %s \"%s\"", cvarName, cvarValue);
    String output = rconService.executeCommand(instanceId, cmd);
    if (RconFailureDetector.isFailed(output)) {
        throw new RuntimeException("RCON sm_cvar 失败: " + output);
    }
}
```

- [ ] **Step 7: 实现 restoreDefaults（从注释 Default 元数据还原）**

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
    List<ConfigItem> items = resource.getSpec().getItems();
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().isEmpty()) {
            item.setValue(item.getDefaultValue());
        }
    }
    String cfgPath = resource.getSpec().getConfigPath();
    String original = resource.getSpec().getRawContent();
    String updated = cfgParser.serialize(items, original);
    instanceFileService.writeTextFile(instanceId, cfgPath, updated);

    resource.getSpec().setItems(items);
    resource.getSpec().setLastSyncedAt(LocalDateTime.now());
    extensionClient.update(resource);
}
```

- [ ] **Step 8: 实现 updateOrCreateConfig（文件存在更新，不存在创建）**

```java
/**
 * 更新或创建配置文件（对齐 l4d2-server-next UpdateOrCreateSourceModConfig）。
 * <p>文件存在时仅更新已存在 key，缺失 key 追加；不存在时用 restoreFormat 创建。
 */
public void updateOrCreateConfig(Long instanceId, String configName, Map<String, String> values) throws Exception {
    // 路径遍历防护（对齐 l4d2-server-next SavePluginConfig）
    if (configName.contains("/") || configName.contains("\\")) {
        throw new IllegalArgumentException("无效的配置文件名: " + configName);
    }
    String cfgPath = "cfg/sourcemod/" + configName;
    if (instanceFileService.exists(instanceId, cfgPath)) {
        String content = instanceFileService.readTextFile(instanceId, cfgPath);
        List<ConfigItem> items = cfgParser.parse(content);
        Set<String> updatedKeys = new HashSet<>();
        for (ConfigItem item : items) {
            if (values.containsKey(item.getKey())) {
                item.setValue(values.get(item.getKey()));
                updatedKeys.add(item.getKey());
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!updatedKeys.contains(entry.getKey())) {
                ConfigItem newItem = new ConfigItem();
                newItem.setKey(entry.getKey());
                newItem.setValue(entry.getValue());
                items.add(newItem);
            }
        }
        String updated = cfgParser.serialize(items, content);
        instanceFileService.writeTextFile(instanceId, cfgPath, updated);
    } else {
        List<ConfigItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            ConfigItem item = new ConfigItem();
            item.setKey(entry.getKey());
            item.setValue(entry.getValue());
            items.add(item);
        }
        String content = cfgParser.restoreFormat(items);
        instanceFileService.writeTextFile(instanceId, cfgPath, content);
    }
}
```

- [ ] **Step 9: 运行测试验证通过**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core test -Dtest=SourceModCfgServiceTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/CandidatePathVO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): SourceModCfgService 新增 applyTempConfig/restoreDefaults/updateOrCreateConfig + l4d2↔l4d 互转候选 + CandidatePathVO.alias"
```

---

## 阶段 6：插件导出（新增功能，对齐 plugin_export.go）

### Task 6.1: 创建 PluginExportService + VO + Controller

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginExportTaskVO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginExportController.java`

**参考来源:** `plugin_export.go`（398 行，全部关键逻辑）

- [ ] **Step 1: 创建 PluginExportTaskVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class PluginExportTaskVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private String taskId;
    private Long instanceId;
    private String status; // pending / running / completed / failed / cancelled
    private Integer totalPlugins;
    private Integer exportedPlugins;
    private String message;
    private String downloadUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
}
```

- [ ] **Step 2: 创建 PluginExportService**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginExportTaskVO;
import com.gameplatform.plugin.service.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件批量导出服务（对齐 l4d2-server-next plugin_export.go）。
 * <p>将 plugins_store 下所有插件打包为 zip，临时目录 30 分钟过期。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginExportService {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;

    /** 任务过期时间 30 分钟（对齐 l4d2-server-next pluginExportTaskExpireTime） */
    private static final long TASK_EXPIRE_MINUTES = 30;
    /** 单任务限制（对齐 l4d2-server-next：同时只允许一个活跃导出任务） */
    private volatile String activeTaskId = null;

    private final Map<String, PluginExportTaskVO> tasks = new ConcurrentHashMap<>();

    /**
     * 启动导出任务（异步）。
     */
    public PluginExportTaskVO startTask(Long instanceId) {
        if (activeTaskId != null) {
            PluginExportTaskVO active = tasks.get(activeTaskId);
            if (active != null && ("pending".equals(active.getStatus()) || "running".equals(active.getStatus()))) {
                throw new IllegalStateException("已有导出任务正在运行: " + activeTaskId);
            }
        }

        String taskId = UUID.randomUUID().toString();
        String localTempDir = System.getProperty("user.home") + "/game-platform-l4d2/export-tasks/" + taskId;
        new File(localTempDir).mkdirs();

        PluginExportTaskVO task = new PluginExportTaskVO();
        task.setTaskId(taskId);
        task.setInstanceId(instanceId);
        task.setStatus("pending");
        task.setExportedPlugins(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setExpireAt(LocalDateTime.now().plusMinutes(TASK_EXPIRE_MINUTES));
        tasks.put(taskId, task);
        activeTaskId = taskId;

        final String tempDir = localTempDir;
        CompletableFuture.runAsync(() -> {
            try {
                task.setStatus("running");
                String zipPath = tempDir + "/plugins_all.zip";
                exportAllPlugins(instanceId, zipPath, task);
                task.setStatus("completed");
                task.setDownloadUrl("/api/plugin/l4d2/plugins-export/" + taskId + "/download");
                task.setMessage("导出完成");
            } catch (Exception e) {
                log.error("导出失败 taskId={}", taskId, e);
                task.setStatus("failed");
                task.setMessage(e.getMessage());
            }
        });

        return task;
    }

    private void exportAllPlugins(Long instanceId, String zipPath, PluginExportTaskVO task) throws Exception {
        String storePath = pathResolver.getPluginsStorePath();
        List<FileInfo> entries = instanceFileService.listDirectory(instanceId, storePath);
        List<FileInfo> pluginDirs = entries.stream().filter(FileInfo::isDirectory).collect(java.util.stream.Collectors.toList());
        task.setTotalPlugins(pluginDirs.size());

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath))) {
            int exported = 0;
            for (FileInfo pluginDir : pluginDirs) {
                String pluginName = pluginDir.getName();
                List<FileInfo> files = instanceFileService.walkFiles(instanceId, pluginDir.getPath());
                for (FileInfo file : files) {
                    if (file.isDirectory()) continue;
                    byte[] content = instanceFileService.readBytes(instanceId, file.getPath());
                    String entryName = "plugins/" + pluginName + "/" + file.getPath().substring(pluginDir.getPath().length() + 1);
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(content);
                    zos.closeEntry();
                }
                exported++;
                task.setExportedPlugins(exported);
            }
        }
    }

    public PluginExportTaskVO getTask(String taskId) {
        return tasks.get(taskId);
    }

    public File getDownloadFile(String taskId) {
        PluginExportTaskVO task = tasks.get(taskId);
        if (task == null || !"completed".equals(task.getStatus())) {
            throw new IllegalStateException("任务不存在或未完成: " + taskId);
        }
        String zipPath = System.getProperty("user.home") + "/game-platform-l4d2/export-tasks/" + taskId + "/plugins_all.zip";
        File file = new File(zipPath);
        if (!file.exists()) {
            throw new IllegalStateException("下载文件不存在（可能已过期）: " + taskId);
        }
        return file;
    }

    public PluginExportTaskVO cancelTask(String taskId) {
        PluginExportTaskVO task = tasks.get(taskId);
        if (task == null) return null;
        task.setStatus("cancelled");
        task.setMessage("用户取消");
        if (taskId.equals(activeTaskId)) activeTaskId = null;
        return task;
    }

    /**
     * 启动时清理过期任务（对齐 l4d2-server-next CleanPluginExportTemp）。
     */
    public void cleanExpiredTasks() {
        String baseDir = System.getProperty("user.home") + "/game-platform-l4d2/export-tasks";
        File dir = new File(baseDir);
        if (!dir.exists()) return;
        File[] taskDirs = dir.listFiles();
        if (taskDirs == null) return;
        LocalDateTime now = LocalDateTime.now();
        for (File taskDir : taskDirs) {
            PluginExportTaskVO task = tasks.get(taskDir.getName());
            if (task == null || task.getExpireAt().isBefore(now)) {
                deleteDirectory(taskDir);
                if (task != null) tasks.remove(taskDir.getName());
            }
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
```

- [ ] **Step 3: 创建 PluginExportController**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.Result;
import com.gameplatform.plugin.l4d2.service.PluginExportService;
import com.gameplatform.plugin.l4d2.vo.PluginExportTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@Tag(name = "L4D2 插件导出")
@RestController
@RequestMapping("/api/plugin/l4d2/plugins-export")
@RequiredArgsConstructor
public class PluginExportController {

    private final PluginExportService exportService;

    @Operation(summary = "启动导出任务")
    @PostMapping("/start")
    public Result<PluginExportTaskVO> start(@RequestParam Long instanceId) {
        return Result.success(exportService.startTask(instanceId));
    }

    @Operation(summary = "查询导出任务状态")
    @GetMapping("/{taskId}/status")
    public Result<PluginExportTaskVO> status(@PathVariable String taskId) {
        return Result.success(exportService.getTask(taskId));
    }

    @Operation(summary = "下载导出文件")
    @GetMapping("/{taskId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String taskId) {
        File file = exportService.getDownloadFile(taskId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", "plugins_all.zip");
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }

    @Operation(summary = "取消导出任务")
    @PostMapping("/{taskId}/cancel")
    public Result<PluginExportTaskVO> cancel(@PathVariable String taskId) {
        return Result.success(exportService.cancelTask(taskId));
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginExportTaskVO.java
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginExportController.java
git commit -m "feat(l4d2): 新增 PluginExportService/VO/Controller（批量导出 zip，对齐 plugin_export.go）"
```

---

## 阶段 7：Controller 层对齐

### Task 7.1: PluginManageController 调整

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

### Task 7.2: PluginConfigController 新增端点

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

### Task 7.3: PluginStoreController 签名对齐 + README 端点

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java`

- [ ] **Step 1: 端点改用 PluginStore DTOs + 新增 README**

```java
@Operation(summary = "商店列表")
@PostMapping("/list")
public Result<List<PluginStoreItemVO>> list(@RequestBody PluginStoreListDTO dto) {
    return Result.success(storeService.list(dto));
}

@Operation(summary = "商店详情")
@PostMapping("/detail")
public Result<PluginStoreDetailVO> detail(@RequestBody PluginStoreQueryDTO dto) {
    return Result.success(storeService.detail(dto.getPluginId(), dto));
}

@Operation(summary = "商店下载")
@PostMapping("/download")
public Result<String> download(@RequestBody PluginStoreDownloadDTO dto) {
    return Result.success(storeService.download(dto));
}

@Operation(summary = "商店 README 在线读取")
@PostMapping("/readme")
public Result<String> readme(@RequestBody PluginStoreQueryDTO dto) {
    return Result.success(storeService.fetchReadme(dto.getPluginId(), dto));
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl plugin-l4d2/plugin-l4d2-core -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginStoreController.java
git commit -m "refactor(l4d2): PluginStoreController 签名对齐 PluginStore DTOs + 新增 /readme 端点"
```

---

## 阶段 8：迁移与启动清理

### Task 8.1: 创建 PluginStoreMigration

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/migration/PluginStoreMigration.java`

**参考来源:** `plugin_store.go:CleanDownloadTemp` + `plugin_export.go:CleanPluginExportTemp`

- [ ] **Step 1: 创建迁移类**

```java
package com.gameplatform.plugin.l4d2.migration;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.service.EnabledPluginsService;
import com.gameplatform.plugin.l4d2.service.PluginExportService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件库迁移与启动清理。
 * <p>1. 旧版 plugins/ + .file_refs.json → 新版 plugins_store/ + .enabled_plugins.yaml（幂等）
 * <p>2. 启动时清理商店下载临时目录（对齐 l4d2-server-next CleanDownloadTemp）
 * <p>3. 启动时清理导出任务临时目录（对齐 l4d2-server-next CleanPluginExportTemp）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStoreMigration {

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;
    private final PluginExportService pluginExportService;

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

    /**
     * 启动时清理本地临时目录（对齐 l4d2-server-next CleanDownloadTemp + CleanPluginExportTemp）。
     */
    public void cleanLocalTempDirs() {
        // 清理商店下载临时目录
        String storeTempDir = System.getProperty("user.home") + "/game-platform-l4d2/store-tasks";
        cleanLocalDir(storeTempDir);
        // 清理导出任务临时目录（含过期任务）
        pluginExportService.cleanExpiredTasks();
    }

    private void ensureStoreDirectory(Long instanceId) throws Exception {
        String storePath = pathResolver.getPluginsStorePath();
        try {
            instanceFileService.listDirectory(instanceId, storePath);
        } catch (Exception e) {
            instanceFileService.createDirectory(instanceId, "addons/sourcemod");
            instanceFileService.createDirectory(instanceId, storePath);
            log.info("创建 plugins_store 目录 instanceId={}", instanceId);
        }
    }

    private void migrateLegacyPlugins(Long instanceId) throws Exception {
        String legacyFileRefs = pathResolver.getFileRefsPath();
        if (!instanceFileService.fileExists(instanceId, legacyFileRefs)) {
            return;
        }
        log.info("检测到旧版 .file_refs.json，开始迁移 instanceId={}", instanceId);

        String storePath = pathResolver.getPluginsStorePath();
        List<com.gameplatform.plugin.service.FileInfo> storeEntries = instanceFileService.listDirectory(instanceId, storePath);
        if (storeEntries != null && !storeEntries.isEmpty()) {
            log.info("plugins_store 已有数据，跳过迁移 instanceId={}", instanceId);
            return;
        }

        log.warn("检测到旧版插件结构，建议手动迁移 plugins/ → plugins_store/，instanceId={}", instanceId);

        try {
            instanceFileService.deleteFile(instanceId, legacyFileRefs);
            log.info("已删除旧版 .file_refs.json instanceId={}", instanceId);
        } catch (Exception ignore) {}
    }

    private void ensureEnabledPluginsYaml(Long instanceId) {
        try {
            enabledPluginsService.loadYaml(instanceId);
        } catch (Exception e) {
            log.warn("初始化 .enabled_plugins.yaml 失败 instanceId={}", instanceId);
        }
    }

    private void cleanLocalDir(String dirPath) {
        java.io.File dir = new java.io.File(dirPath);
        if (!dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                cleanLocalDir(f.getAbsolutePath());
            }
            f.delete();
        }
        log.info("清理本地临时目录: {}", dirPath);
    }
}
```

- [ ] **Step 2: 修改 L4D2Extension.onInstanceCreate 触发迁移**

```java
@Override
public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
    log.info("L4D2 实例创建: instanceId={}, config={}", instanceId, config);
    try {
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
public class L4D2Extension implements GameEnhancementExtension, org.springframework.context.ApplicationContextAware {

    private org.springframework.context.ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    // ... 其他方法不变
}
```

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
git commit -m "feat(l4d2): 新增 PluginStoreMigration 幂等迁移 + 启动清理本地临时目录"
```

---

## 阶段 9：前端重写

### Task 9.1: 前端 API 封装重写

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
- `getStorePlugins(dto)` → `POST /plugin-store/list` body `PluginStoreListDTO`
- `downloadStorePlugin(dto)` → `POST /plugin-store/download` body `PluginStoreDownloadDTO`
- `getStoreReadme(dto)` → `POST /plugin-store/readme` body `PluginStoreQueryDTO`
- `startPluginExport(instanceId)` → `POST /plugins-export/start?instanceId=`
- `getPluginExportStatus(taskId)` → `GET /plugins-export/{taskId}/status`
- `downloadPluginExport(taskId)` → `GET /plugins-export/{taskId}/download`
- `cancelPluginExport(taskId)` → `POST /plugins-export/{taskId}/cancel`

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/api/index.ts
git commit -m "refactor(l4d2-frontend): API 封装对齐新后端端点 + 新增导出 API"
```

---

### Task 9.2: Plugins.vue 重写

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

- [ ] **Step 3: 新增"全量导出"按钮**

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\views\Plugins.vue:182-189` 的导出对话框。

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Plugins.vue
git commit -m "refactor(l4d2-frontend): Plugins.vue 对齐新字段与操作 + 新增导出功能"
```

---

### Task 9.3: PluginConfig.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue`

- [ ] **Step 1: 增加"临时应用"和"恢复默认"按钮**

每个 CVAR 行操作列：
- "保存"（持久化，已有）
- "临时应用"（applyTempConfig，新增）
- "恢复默认"（restoreDefaults，整文件级，放工具栏）

参考 `D:\program\open_source\l4d2-server-next-master\frontend\src\components\PluginConfigModal.vue:69-92`。

- [ ] **Step 2: 候选路径展示 l4d2↔l4d 互转关系**

在候选路径对话框中，显示 `alias` 字段（如 `l4d_damage_show.cfg` 是 `l4d2_damage_show.cfg` 的别名）。

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginConfig.vue
git commit -m "feat(l4d2-frontend): PluginConfig.vue 新增临时应用 + 恢复默认 + l4d2↔l4d 候选展示"
```

---

### Task 9.4: Preset.vue 重写

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/pages/Preset.vue`

- [ ] **Step 1: 显示插件数量 + 应用前 popconfirm**

```vue
<a-table-column title="插件数量" :width="100">
  <template #default="{ record }">
    <a-tag>{{ record.pluginCount }} 个</a-tag>
  </template>
</a-table-column>

<a-popconfirm content="应用预设将重置所有插件状态，禁用当前所有插件并按预设启用。配置项也会被覆盖。应用后需重启服务器才能生效。确认继续？" @ok="handleApply(record)">
  <a-button type="primary" status="warning">应用预设</a-button>
</a-popconfirm>
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/Preset.vue
git commit -m "refactor(l4d2-frontend): Preset.vue 显示插件数量 + 应用前二次确认 + 提示需重启"
```

---

### Task 9.5: PluginStore.vue 重写

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

- [ ] **Step 3: 在线 README 读取**

新增"在线 README"按钮，调用 `getStoreReadme(dto)` 获取 GitHub raw 的 README 内容。

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/frontend/src/pages/PluginStore.vue
git commit -m "feat(l4d2-frontend): PluginStore.vue 支持自定义仓库/Token/代理 + 在线 README"
```

---

## 阶段 10：清理与验证

### Task 10.1: 清理旧字段与兼容代码

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigSpec.java`

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

### Task 10.2: 全量编译 + 测试

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

### Task 10.3: 前端构建验证

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

### Task 10.4: 全栈启动验证

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
5. 验证插件导出功能（启动任务 → 查询状态 → 下载 zip）
6. 验证配置编辑的"临时应用"和"恢复默认"
7. 验证候选路径显示 l4d2↔l4d 互转关系

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| PluginInstallService 重写影响面大 | 分步实现 + 单元测试覆盖关键路径（listPlugins/enablePlugin/disablePlugin/回滚/并发复制） |
| 旧版数据迁移失败 | PluginStoreMigration 设计为幂等，失败仅记录日志不阻塞 |
| RCON 回滚不完整 | 仅在 enableAndLoad/disableAndUnload 中执行 RCON，失败时先回滚 RCON 再回滚文件 |
| 商店下载 LFS 检测失败 | 保留原下载流程作为兜底，仅当 isLfsPointer 返回 true 时走 LFS |
| LFS 大小校验失败 | 校验失败立即抛异常，触发任务整体回滚 |
| 并发复制线程池过载 | 使用 Semaphore(8) 限制同时复制的文件数 |
| 前端字段变更导致兼容问题 | 前端重写紧跟后端，避免半改造状态 |
| 扩展资源双写性能 | EnabledPluginsService 在 saveYaml 时批量同步资源，避免频繁 IO |
| InstanceFileService SPI 调用频繁 | 在 listPlugins 中聚合目录扫描，减少远程调用次数 |
| 多插件 zip 上传结构识别错误 | 通过首条 entry 是否以 `plugins/` 开头判断，单插件结构直接使用 zip 文件名作为插件名 |
| l4d2↔l4d 互转误判 | 仅对 `l4d2_` 和 `l4d_` 前缀的插件名生成别名，其他情况不生成 |
| 导出任务并发限制 | 单任务限制（activeTaskId），同时只允许一个活跃导出任务 |
| 导出临时目录磁盘占用 | 30 分钟过期清理 + 启动时整体清空 |

---

## Self-Review 检查清单

完成所有任务后，对照本计划逐项检查：

- [ ] **存储模型**：plugins_store/{name}/left4dead2/ + .enabled_plugins.yaml 双写 ✅
- [ ] **插件来源**：EnabledPlugin.source 字段（panel/store/upload） + plugin.yaml 元数据 ✅
- [ ] **删除语义**：硬删除 + 必须先禁用，引用计数仅在禁用时生效 ✅
- [ ] **回滚机制**：enableAndLoad/disableAndUnload RCON 失败回滚（使用 RconFailureDetector），enablePlugin 文件复制失败回滚已复制文件 ✅
- [ ] **并发复制**：CompletableFuture + Semaphore(8) + AtomicReference（对齐 ants 协程池） ✅
- [ ] **多插件 zip**：自动识别单插件/多插件结构 + GBK 编码 ✅
- [ ] **Zip Slip 防护**：ZipSlipGuard 工具类 + macOS 垃圾过滤 ✅
- [ ] **RCON 失败检测**：RconFailureDetector 工具类（10 个失败标记） ✅
- [ ] **预设**：preset.yaml 新结构（platform + plugins.configs），apply 流程对齐，不调 RCON ✅
- [ ] **商店**：DTO 参数支持 + 临时目录原子提交 + LFS 大小校验 + 3 并发 + 1秒×3重试 + 任务去重 + README 在线读取 + 10 分钟缓存 ✅
- [ ] **配置编辑**：restoreFormat + applyTempConfig (RCON sm_cvar) + restoreDefaults + updateOrCreateConfig + 控制台黑名单 + 文件头过滤 ✅
- [ ] **l4d2↔l4d 互转**：getCandidatePaths 生成别名候选 ✅
- [ ] **插件导出**：PluginExportService 批量导出 zip + 30 分钟过期 + 单任务限制 ✅
- [ ] **迁移**：PluginStoreMigration 幂等迁移 + 启动清理本地临时目录 ✅
- [ ] **前端**：Plugins.vue / PluginConfig.vue / Preset.vue / PluginStore.vue 全面对齐 ✅

---

## 执行交付

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-l4d2-plugin-management-refactor-v3.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
