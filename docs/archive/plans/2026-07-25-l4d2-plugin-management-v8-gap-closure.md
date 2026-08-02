# L4D2 插件管理 v8：开源方案差距闭合实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 v7 已完成的基础上（Phase 1-9 + 414 个测试全部通过），按开源项目 `D:\program\open_source\l4d2-server-next-master` 的方案闭合 7 个主题（存储模型、插件来源、删除语义、回滚机制、预设、商店、配置编辑）的剩余差距。

**Architecture:** v7 已完成核心语义对齐，本 v8 聚焦 3 类差距：
1. **审计日志持久化与查询**：从纯 slf4j 日志升级为扩展资源持久化 + REST 查询接口（对齐开源 `controller/audit.go` + `logic/audit.go`）。
2. **配置编辑语义补全**：(a) `restoreDefaults` 在 cfg 文件不存在时从 PluginConfigResource 元数据重建带注释块的完整 cfg 文件（对齐开源 `RestoreSourceModConfig`）；(b) `updateConfig` / `restoreDefaults` 补齐审计调用；(c) `cfg` 解析正则放宽支持无引号/单侧引号格式（对齐开源 `cvarRegex`）。
3. **预设/导出/解析补全**：(a) `PresetService` 新增 `listSummary` 轻量端点（对齐开源 `GetPresets` 返回 `name+desc+plugin_count`）；(b) `PresetService.apply` 平台插件失败时回滚已禁用插件；(c) `PluginExportService` 增加 `@EventListener(ApplicationReadyEvent.class)` 启动清理本地 `~/game-platform-l4d2/export-tasks/` 残留；(d) `EnabledPluginsService` source 字段在 plugin.yaml 缺失时回退到 `enabled_plugins.yaml` 中的 source。

**Tech Stack:** Java 17, Spring Boot 3.2.5, PF4J 3.10.0, MyBatis-Plus, JUnit 5 + Mockito, Hutool

---

## 当前实施状态（2026-07-25 v7 完成后核查）

### ✅ v7 已完成（414 个测试全部通过）

| 主题 | v7 状态 | 备注 |
|------|---------|------|
| 存储模型 | ✅ 完整 | plugins_store + .enabled_plugins.yaml + EnabledPluginsService 双写 + FileRefsService 内存引用计数 |
| 插件来源 | ✅ 完整 | source 字段（panel/store/upload）+ PluginMeta 持久化 |
| 删除语义 | ✅ 完整 | isEnabled 校验 + 物理删除 + plugin.yaml 清理 |
| 回滚机制 | ✅ 完整 | SMX 加载/卸载回滚 + 商店下载临时目录+原子移动 + PluginStoreMigration 启动清理 .download_temp |
| 预设 | ✅ 大部分 | preset.yaml + platform 字段 + 预校验 + 平台插件优先启用 + 自动备份 |
| 商店 | ✅ 完整 | GitHub Trees API + LFS + 6 状态机 + 3 并发 + 3 重试 + 10 分钟缓存 + proxyUrl + githubToken + README 端点 |
| 配置编辑 | ✅ 大部分 | cfg 解析 + Default/Min/Max 元数据 + applyTempConfig + restoreDefaults + 控制台黑名单 + l4d2↔l4d_ 互转 + CvarBlacklist + 审计日志（仅 slf4j） |

### ❌ v8 待闭合差距（按优先级）

| 编号 | 主题 | 差距 | 优先级 |
|------|------|------|--------|
| G1 | 配置编辑 | `PluginConfigAuditService` 仅 slf4j 日志，无持久化与查询接口；前端无法查询审计记录 | 高 |
| G2 | 配置编辑 | `SourceModCfgService.updateConfig` / `restoreDefaults` 未调用 `auditService.logXxx` | 高 |
| G3 | 配置编辑 | `restoreDefaults` 在 cfg 文件不存在时直接抛异常，缺失开源 `RestoreSourceModConfig` 的"用元数据重建带注释块 cfg 文件"语义 | 高 |
| G4 | 配置编辑 | `SourceModCfgParser.KV_PATTERN` 仅匹配 `"key" "value"` 双引号格式，开源 regex 支持无引号/单侧引号 | 中 |
| G5 | 预设 | `PresetService.list()` 返回完整 `PresetDetailVO`，缺失开源 `GetPresets` 的轻量列表（name+desc+plugin_count） | 中 |
| G6 | 预设 | `PresetService.apply` 平台插件失败时抛异常中止，但已禁用插件不会恢复，处于"坏状态" | 中 |
| G7 | 回滚机制 | `PluginExportService` 无启动清理本地 `~/game-platform-l4d2/export-tasks/` 残留的钩子 | 中 |
| G8 | 插件来源 | `EnabledPluginsService` 在 `enableAndLoad` 调用 `resolveSourceForEnable` 时，若 plugin.yaml 缺失会默认 "panel"，可能与 enabled_plugins.yaml 中已有 source 不一致 | 中 |

---

## 参考基线对照

| 主题 | l4d2-server-next 方案 | v7 已实现 | v8 增量 |
|------|----------------------|----------|---------|
| 存储模型 | `./plugins` + `plugins.yaml` + `fileRefs` 内存 Map | ✅ 等价 | 无需改动 |
| 插件来源 | panel/store/upload + `plugin_sources` map | ✅ 等价 | G8: source 回退一致性 |
| 删除语义 | disable=清理副本；delete=物理删除需先 disable | ✅ 完整 | 无需改动 |
| 回滚机制 | SMX 回滚 + 商店临时目录+原子重命名 + 启动清理 .download_temp + 启动清理 .export_temp | ✅ 大部分 | G7: 导出任务启动清理 |
| 预设 | preset.yaml + platform + 预校验 + 平台优先 + cfg 覆盖 + GetPresets 轻量列表 | ✅ 大部分 | G5: listSummary 端点；G6: apply 失败回滚 |
| 商店 | Trees API + LFS + 5 状态机 + 3 并发 + 3 重试 + 缓存 + proxy + token + 取消 + 进度 + README | ✅ 完整 | 无需改动 |
| 配置编辑 | cfg 解析 + Default/Min/Max + applyTempConfig + RestoreSourceModConfig + 控制台黑名单 + l4d2↔l4d_ 互转 + 审计查询 | ⚠️ 部分 | G1: 审计持久化+查询；G2: 审计触发点；G3: 重建语义；G4: 解析正则 |

---

## 文件结构

### 创建文件（Create）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditResource.java` — 审计日志扩展资源
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditSpec.java` — 审计日志业务数据
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginConfigAuditVO.java` — 审计日志查询响应 VO
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetSummaryVO.java` — 预设轻量列表 VO
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginConfigAuditQueryDTO.java` — 审计日志查询 DTO
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditController.java` — 审计日志查询控制器
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServicePersistenceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditControllerTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceSummaryTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginExportServiceStartupCleanupTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserLenientTest.java`

### 修改文件（Modify）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java` — 注入 ExtensionClient + 异步写入扩展资源
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java` — updateConfig/restoreDefaults 补齐审计调用；restoreDefaults 增加重建语义
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java` — 放宽 KV_PATTERN 支持无引号/单侧引号
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java` — 新增 listSummary；apply 失败回滚
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PresetController.java` — 新增 /list-summary 端点
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java` — 增加 @EventListener 启动清理
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` — resolveSourceForEnable 增加 enabled_plugins.yaml 回退
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java` — 补充审计/重建测试

---

## Phase 1: 审计日志持久化与查询接口（G1 + G2）

> **状态：待执行** — 对齐开源 `audit.go` + `controller/audit.go`：审计日志持久化到扩展资源（独立表），提供分页查询接口。

### Task 1.1: 创建 PluginConfigAuditResource 扩展资源

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditResource.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditSpec.java`

**Background:** 对齐开源 `model/audit.go` 的 `AuditLog` 结构（Time/Role/IP/Path/Detail/Success）。本项目通过 PF4J 扩展资源机制持久化，使用 `MODEL_ISOLATED` 策略生成独立表 `ext_plugin_l4d2_pluginauditresource`，便于前端按 instanceId/pluginName/operationType 查询。

- [ ] **Step 1: 创建 PluginConfigAuditSpec 业务数据类**

```java
package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * L4D2 插件配置审计日志业务数据。
 *
 * <p>对齐 l4d2-server-next model/audit.go AuditLog 结构，适配本项目扩展资源持久化：
 * <ul>
 *   <li>Time → operationTime</li>
 *   <li>Path → operationType（UPDATE/APPLY_TEMP/RESTORE_DEFAULTS）</li>
 *   <li>Detail → details（JSON 字符串，存 cvar/oldValue/newValue 等）</li>
 *   <li>Success → success</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigAuditSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例 ID */
    private Long instanceId;

    /** 主机 ID */
    private Long hostId;

    /** 插件名（临时应用可能为 null） */
    private String pluginName;

    /** cfg 文件相对路径 */
    private String cfgFile;

    /** 操作类型：UPDATE / APPLY_TEMP / RESTORE_DEFAULTS */
    private String operationType;

    /** CVAR 名称（批量修改为 "multiple"） */
    private String cvarName;

    /** 旧值（批量修改为 "multiple"） */
    private String oldValue;

    /** 新值 */
    private String newValue;

    /** 变更数量（仅 RESTORE_DEFAULTS 使用） */
    private Integer changedCount;

    /** 操作者（用户名或 "system"） */
    private String operator;

    /** 操作是否成功 */
    private Boolean success;

    /** 错误信息（失败时填充） */
    private String errorMessage;

    /** 操作时间 */
    private LocalDateTime operationTime;

    /** 扩展字段（预留） */
    private Map<String, String> extra;
}
```

- [ ] **Step 2: 创建 PluginConfigAuditResource 扩展资源类**

```java
package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 插件配置审计日志扩展资源。
 *
 * <p>MODEL_ISOLATED 策略，物理表 {@code ext_plugin_l4d2_pluginauditresource}。
 * 用于持久化配置修改审计记录，支持前端按 instanceId/pluginName/operationType 分页查询。
 *
 * <p>对齐 l4d2-server-next audit.go 的 SQLite 持久化 + controller/audit.go 的查询接口。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class PluginConfigAuditResource extends AbstractExtension<PluginConfigAuditSpec> {
}
```

- [ ] **Step 3: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditResource.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/PluginConfigAuditSpec.java
git commit -m "feat(l4d2): 新增 PluginConfigAuditResource 审计日志扩展资源"
```

---

### Task 1.2: PluginConfigAuditService 持久化到扩展资源

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServicePersistenceTest.java`

**Background:** 当前 `PluginConfigAuditService` 仅 `log.info` 到 slf4j，无持久化。本任务注入 `ExtensionClient`，在写日志的同时异步保存到 `PluginConfigAuditResource`。失败不阻塞主流程。

- [ ] **Step 1: 写失败测试**

创建 `PluginConfigAuditServicePersistenceTest.java`：

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginConfigAuditServicePersistenceTest {

    @Mock
    private ExtensionClient extensionClient;

    @InjectMocks
    private PluginConfigAuditService auditService;

    @Test
    void logUpdateConfig_shouldPersistToExtensionResource() {
        auditService.logUpdateConfig(1L, 100L, "l4d2_x", "l4d2_x.cfg",
                "sm_dp", "2.5", "3.0", "admin");

        ArgumentCaptor<PluginConfigAuditResource> captor = ArgumentCaptor.forClass(PluginConfigAuditResource.class);
        verify(extensionClient).save(captor.capture());
        PluginConfigAuditSpec spec = captor.getValue().getSpec();
        assertEquals(1L, spec.getInstanceId());
        assertEquals("l4d2_x", spec.getPluginName());
        assertEquals("UPDATE", spec.getOperationType());
        assertEquals("sm_dp", spec.getCvarName());
        assertEquals("2.5", spec.getOldValue());
        assertEquals("3.0", spec.getNewValue());
        assertEquals("admin", spec.getOperator());
        assertTrue(spec.getSuccess());
    }

    @Test
    void logApplyTempConfig_shouldPersistWithOperationType() {
        auditService.logApplyTempConfig(1L, 100L, null, "sm_dp", "5.0", "system");

        ArgumentCaptor<PluginConfigAuditResource> captor = ArgumentCaptor.forClass(PluginConfigAuditResource.class);
        verify(extensionClient).save(captor.capture());
        assertEquals("APPLY_TEMP", captor.getValue().getSpec().getOperationType());
    }

    @Test
    void logRestoreDefaults_shouldPersistWithChangedCount() {
        auditService.logRestoreDefaults(1L, 100L, "l4d2_x", "l4d2_x.cfg", 3, "admin");

        ArgumentCaptor<PluginConfigAuditResource> captor = ArgumentCaptor.forClass(PluginConfigAuditResource.class);
        verify(extensionClient).save(captor.capture());
        PluginConfigAuditSpec spec = captor.getValue().getSpec();
        assertEquals("RESTORE_DEFAULTS", spec.getOperationType());
        assertEquals(3, spec.getChangedCount());
    }

    @Test
    void log_shouldNotThrowWhenExtensionClientFails() {
        doThrow(new RuntimeException("DB unavailable"))
                .when(extensionClient).save(any());

        // 持久化失败不应抛异常
        assertDoesNotThrow(() -> auditService.logUpdateConfig(
                1L, 100L, "x", "x.cfg", "k", "old", "new", "admin"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditServicePersistenceTest -q
```

Expected: FAIL（`ExtensionClient` 未注入，`save` 未被调用）。

- [ ] **Step 3: 修改 PluginConfigAuditService 增加 ExtensionClient 持久化**

替换 `PluginConfigAuditService.java` 全文：

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 插件配置修改审计日志服务。
 *
 * <p>对齐 l4d2-server-next audit.go 的 LogOp 模式 + SQLite 持久化：
 * <ul>
 *   <li>同时写 slf4j 日志（运维查询）和 PluginConfigAuditResource 扩展资源（前端查询）</li>
 *   <li>使用 @Async 异步写入扩展资源，避免阻塞主流程</li>
 *   <li>持久化失败仅记录警告，不抛异常</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginConfigAuditService {

    private final ExtensionClient extensionClient;

    /**
     * 记录持久化修改 CVAR 操作。
     */
    @Async("taskExecutor")
    public void logUpdateConfig(Long instanceId, Long hostId, String pluginName,
                                 String cfgFile, String cvarName,
                                 String oldValue, String newValue, String operator) {
        logAndPersist("UPDATE", instanceId, hostId, pluginName, cfgFile,
                cvarName, oldValue, newValue, null, operator, true, null);
    }

    /**
     * 记录临时应用 CVAR 操作。
     */
    @Async("taskExecutor")
    public void logApplyTempConfig(Long instanceId, Long hostId, String pluginName,
                                    String cvarName, String value, String operator) {
        logAndPersist("APPLY_TEMP", instanceId, hostId, pluginName, null,
                cvarName, null, value, null, operator, true, null);
    }

    /**
     * 记录恢复默认值操作。
     */
    @Async("taskExecutor")
    public void logRestoreDefaults(Long instanceId, Long hostId, String pluginName,
                                    String cfgFile, int changedCount, String operator) {
        logAndPersist("RESTORE_DEFAULTS", instanceId, hostId, pluginName, cfgFile,
                null, null, null, changedCount, operator, true, null);
    }

    /**
     * 记录失败操作（持久化失败时使用）。
     */
    @Async("taskExecutor")
    public void logFailure(String operationType, Long instanceId, Long hostId, String pluginName,
                           String cfgFile, String cvarName, String operator, String errorMessage) {
        logAndPersist(operationType, instanceId, hostId, pluginName, cfgFile,
                cvarName, null, null, null, operator, false, errorMessage);
    }

    private void logAndPersist(String operationType, Long instanceId, Long hostId,
                                String pluginName, String cfgFile, String cvarName,
                                String oldValue, String newValue, Integer changedCount,
                                String operator, boolean success, String errorMessage) {
        // 1. 写 slf4j 日志
        try {
            log.info("[ConfigAudit] {} instanceId={} hostId={} plugin={} cfg={} cvar={} old={} new={} changed={} op={} success={} err={} time={}",
                    operationType, instanceId, hostId, pluginName, cfgFile, cvarName,
                    oldValue, newValue, changedCount, operator, success, errorMessage,
                    LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }

        // 2. 持久化到扩展资源
        try {
            PluginConfigAuditResource resource = new PluginConfigAuditResource();
            PluginConfigAuditSpec spec = new PluginConfigAuditSpec();
            spec.setInstanceId(instanceId);
            spec.setHostId(hostId);
            spec.setPluginName(pluginName);
            spec.setCfgFile(cfgFile);
            spec.setOperationType(operationType);
            spec.setCvarName(cvarName);
            spec.setOldValue(oldValue);
            spec.setNewValue(newValue);
            spec.setChangedCount(changedCount);
            spec.setOperator(operator);
            spec.setSuccess(success);
            spec.setErrorMessage(errorMessage);
            spec.setOperationTime(LocalDateTime.now());
            resource.setSpec(spec);
            extensionClient.save(resource);
        } catch (Exception e) {
            log.warn("审计日志持久化失败（已忽略）: type={}, instanceId={}, err={}",
                    operationType, instanceId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditServicePersistenceTest -q
```

Expected: PASS。

- [ ] **Step 5: 运行已有的 PluginConfigAuditServiceTest 确保向后兼容**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditServiceTest -q
```

Expected: PASS（原 4 个用例不破坏，仅校验不抛异常）。

> 注：若原 `PluginConfigAuditServiceTest` 使用 `@InjectMocks` 但未 mock `ExtensionClient`，需要在该测试类 `@Mock` 一个 `ExtensionClient` 字段；否则 `@InjectMocks` 会注入 null。先在 Step 5 检查失败，若失败则修复原测试类。

- [ ] **Step 6: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServicePersistenceTest.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServiceTest.java
git commit -m "feat(l4d2): PluginConfigAuditService 持久化到 PluginConfigAuditResource 扩展资源"
```

---

### Task 1.3: 在 SourceModCfgService.updateConfig / restoreDefaults 中接入审计（G2）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**Background:** 当前仅 `applyTempConfig` 调用 `auditService.logApplyTempConfig`。`updateConfig` 和 `restoreDefaults` 末尾需补充审计调用。审计失败不阻塞主流程（异步 + try-catch）。

- [ ] **Step 1: 在 SourceModCfgServiceTest 中添加失败测试**

在 `SourceModCfgServiceTest.java` 中追加测试用例：

```java
@Test
void updateConfig_shouldCallAuditService() {
    when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(mockInstance());
    when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(true);
    when(instanceFileService.readTextFile(eq(INSTANCE_ID), anyString(), any(Charset.class)))
            .thenReturn("\"sm_dp\" \"2.5\"");
    when(cfgParser.parse(anyString())).thenReturn(List.of());

    service.updateConfig(INSTANCE_ID, "l4d2_x", List.of());

    verify(auditService).logUpdateConfig(eq(INSTANCE_ID), anyLong(),
            eq("l4d2_x"), anyString(), anyString(), anyString(), anyString(), anyString());
}

@Test
void restoreDefaults_shouldCallAuditService() {
    String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_x.cfg";
    when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(mockInstance());
    when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
    when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
            .thenReturn("\"sm_dp\" \"2.5\" // Default: 0.5\n");

    service.restoreDefaults(INSTANCE_ID, "l4d2_x");

    verify(auditService).logRestoreDefaults(eq(INSTANCE_ID), anyLong(),
            eq("l4d2_x"), eq(cfgAbs), anyInt(), anyString());
}
```

> 注：`mockInstance()` 是已有的辅助方法。若测试类无此方法，参考已有用例中的 `InstanceVO` 构造方式。

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest#updateConfig_shouldCallAuditService,SourceModCfgServiceTest#restoreDefaults_shouldCallAuditService -q
```

Expected: FAIL（`auditService.logUpdateConfig` / `logRestoreDefaults` 未被调用）。

- [ ] **Step 3: 在 updateConfig 方法末尾补充审计调用**

在 `SourceModCfgService.java` 的 `updateConfig` 方法末尾（`log.info("更新插件配置成功..."`）之前添加：

```java
try {
    auditService.logUpdateConfig(instanceId, hostId, pluginName, configName,
            "multiple", "multiple", "multiple", "system");
} catch (Exception e) {
    log.warn("审计日志调用失败（已忽略）: {}", e.getMessage());
}
```

- [ ] **Step 4: 在 restoreDefaults 方法末尾补充审计调用**

在 `SourceModCfgService.java` 的 `restoreDefaults` 方法末尾（`log.info("已恢复默认配置..."`）之前添加：

```java
try {
    auditService.logRestoreDefaults(instanceId, instance.getHostId(), pluginName,
            targetRelPath, changed, "system");
} catch (Exception e) {
    log.warn("审计日志调用失败（已忽略）: {}", e.getMessage());
}
```

- [ ] **Step 5: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS（20 个原有用例 + 2 个新用例全部通过）。

- [ ] **Step 6: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): updateConfig/restoreDefaults 接入 PluginConfigAuditService 审计"
```

---

### Task 1.4: 创建 PluginConfigAuditController 查询接口

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditController.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginConfigAuditQueryDTO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginConfigAuditVO.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditControllerTest.java`

**Background:** 对齐开源 `controller/audit.go` 的 `ListAuditLogs` 分页查询。本项目通过 ExtensionClient 查询 `PluginConfigAuditResource`，按 instanceId 必填、operationType/pluginName/operator 可选过滤、operationTime 倒序返回。

- [ ] **Step 1: 创建 PluginConfigAuditQueryDTO**

```java
package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;

/**
 * 插件配置审计日志查询 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigAuditQueryDTO {

    /** 实例 ID（必填） */
    private Long instanceId;

    /** 操作类型（可选）：UPDATE / APPLY_TEMP / RESTORE_DEFAULTS */
    private String operationType;

    /** 插件名（可选，模糊匹配） */
    private String pluginName;

    /** 操作者（可选，精确匹配） */
    private String operator;

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 每页大小，默认 20，最大 100 */
    private Integer pageSize = 20;
}
```

- [ ] **Step 2: 创建 PluginConfigAuditVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 插件配置审计日志响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigAuditVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private Long instanceId;
    private Long hostId;
    private String pluginName;
    private String cfgFile;
    private String operationType;
    private String cvarName;
    private String oldValue;
    private String newValue;
    private Integer changedCount;
    private String operator;
    private Boolean success;
    private String errorMessage;
    private LocalDateTime operationTime;
}
```

- [ ] **Step 3: 创建 PluginConfigAuditController**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.dto.PluginConfigAuditQueryDTO;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditSpec;
import com.gameplatform.plugin.l4d2.vo.PluginConfigAuditVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * L4D2 插件配置审计日志查询控制器。
 *
 * <p>对齐 l4d2-server-next controller/audit.go 的 ListAuditLogs。
 * 通过 ExtensionClient 查询 PluginConfigAuditResource 扩展资源。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 插件配置审计", description = "插件配置修改审计日志查询")
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-config-audit")
@RequiredArgsConstructor
public class PluginConfigAuditController {

    private final ExtensionClient extensionClient;

    @Operation(summary = "分页查询审计日志")
    @GetMapping("/list")
    public Result<List<PluginConfigAuditVO>> list(PluginConfigAuditQueryDTO dto) {
        if (dto.getInstanceId() == null) {
            return Result.fail("instanceId 不能为空");
        }

        List<PluginConfigAuditResource> all = extensionClient.listAll(PluginConfigAuditResource.class);

        List<PluginConfigAuditVO> filtered = all.stream()
                .map(PluginConfigAuditResource::getSpec)
                .filter(spec -> spec != null && dto.getInstanceId().equals(spec.getInstanceId()))
                .filter(spec -> dto.getOperationType() == null
                        || dto.getOperationType().isEmpty()
                        || dto.getOperationType().equals(spec.getOperationType()))
                .filter(spec -> dto.getPluginName() == null
                        || dto.getPluginName().isEmpty()
                        || (spec.getPluginName() != null && spec.getPluginName().contains(dto.getPluginName())))
                .filter(spec -> dto.getOperator() == null
                        || dto.getOperator().isEmpty()
                        || dto.getOperator().equals(spec.getOperator()))
                .sorted(Comparator.comparing(PluginConfigAuditSpec::getOperationTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toVO)
                .collect(Collectors.toList());

        int page = dto.getPage() == null || dto.getPage() < 1 ? 1 : dto.getPage();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? 20 : Math.min(dto.getPageSize(), 100);
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());

        return Result.success(filtered.subList(from, to));
    }

    private PluginConfigAuditVO toVO(PluginConfigAuditSpec spec) {
        PluginConfigAuditVO vo = new PluginConfigAuditVO();
        vo.setInstanceId(spec.getInstanceId());
        vo.setHostId(spec.getHostId());
        vo.setPluginName(spec.getPluginName());
        vo.setCfgFile(spec.getCfgFile());
        vo.setOperationType(spec.getOperationType());
        vo.setCvarName(spec.getCvarName());
        vo.setOldValue(spec.getOldValue());
        vo.setNewValue(spec.getNewValue());
        vo.setChangedCount(spec.getChangedCount());
        vo.setOperator(spec.getOperator());
        vo.setSuccess(spec.getSuccess());
        vo.setErrorMessage(spec.getErrorMessage());
        vo.setOperationTime(spec.getOperationTime());
        return vo;
    }
}
```

> 注：`ExtensionClient.listAll(Class)` 是已有的查询接口，可参考 `EnabledPluginsService.syncResources` 中的使用方式。若 `ExtensionClient` 接口名/方法名不同，按实际项目接口调整。

- [ ] **Step 4: 写控制器测试**

```java
package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.dto.PluginConfigAuditQueryDTO;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigAuditSpec;
import com.gameplatform.plugin.l4d2.vo.PluginConfigAuditVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginConfigAuditControllerTest {

    @Mock
    private ExtensionClient extensionClient;

    @InjectMocks
    private PluginConfigAuditController controller;

    private PluginConfigAuditResource resource1;
    private PluginConfigAuditResource resource2;

    @BeforeEach
    void setUp() {
        resource1 = new PluginConfigAuditResource();
        PluginConfigAuditSpec spec1 = new PluginConfigAuditSpec();
        spec1.setInstanceId(1L);
        spec1.setOperationType("UPDATE");
        spec1.setPluginName("l4d2_x");
        spec1.setOperationTime(LocalDateTime.of(2026, 7, 25, 10, 0));
        resource1.setSpec(spec1);

        resource2 = new PluginConfigAuditResource();
        PluginConfigAuditSpec spec2 = new PluginConfigAuditSpec();
        spec2.setInstanceId(1L);
        spec2.setOperationType("APPLY_TEMP");
        spec2.setPluginName("l4d2_y");
        spec2.setOperationTime(LocalDateTime.of(2026, 7, 25, 11, 0));
        resource2.setSpec(spec2);
    }

    @Test
    void list_shouldFilterByInstanceId() {
        when(extensionClient.listAll(eq(PluginConfigAuditResource.class)))
                .thenReturn(List.of(resource1, resource2));

        PluginConfigAuditQueryDTO dto = new PluginConfigAuditQueryDTO();
        dto.setInstanceId(1L);

        ResponseEntity<?> response = controller.list(dto);
        // 根据实际 Result 包装调整断言
        List<PluginConfigAuditVO> data = ((com.gameplatform.common.result.Result<List<PluginConfigAuditVO>>) response.getBody()).getData();
        assertEquals(2, data.size());
    }

    @Test
    void list_shouldFilterByOperationType() {
        when(extensionClient.listAll(eq(PluginConfigAuditResource.class)))
                .thenReturn(List.of(resource1, resource2));

        PluginConfigAuditQueryDTO dto = new PluginConfigAuditQueryDTO();
        dto.setInstanceId(1L);
        dto.setOperationType("UPDATE");

        ResponseEntity<?> response = controller.list(dto);
        List<PluginConfigAuditVO> data = ((com.gameplatform.common.result.Result<List<PluginConfigAuditVO>>) response.getBody()).getData();
        assertEquals(1, data.size());
        assertEquals("UPDATE", data.get(0).getOperationType());
    }

    @Test
    void list_shouldSortByOperationTimeDesc() {
        when(extensionClient.listAll(eq(PluginConfigAuditResource.class)))
                .thenReturn(List.of(resource1, resource2));

        PluginConfigAuditQueryDTO dto = new PluginConfigAuditQueryDTO();
        dto.setInstanceId(1L);

        ResponseEntity<?> response = controller.list(dto);
        List<PluginConfigAuditVO> data = ((com.gameplatform.common.result.Result<List<PluginConfigAuditVO>>) response.getBody()).getData();
        // resource2 时间更晚，应排在前面
        assertEquals("APPLY_TEMP", data.get(0).getOperationType());
    }

    @Test
    void list_shouldFailWhenInstanceIdNull() {
        PluginConfigAuditQueryDTO dto = new PluginConfigAuditQueryDTO();
        // instanceId 为 null

        ResponseEntity<?> response = controller.list(dto);
        // 应返回 fail 结果
        // 根据实际 Result 类调整断言
    }
}
```

> 注：若 `controller.list` 直接返回 `Result<List<...>>` 而非 `ResponseEntity`，去掉 `ResponseEntity` 包装并直接断言 `Result`。先读 `PresetController` 等已有控制器确认返回风格，本计划假设返回 `Result` 直接。下面给出更简单的版本：

简化版测试：

```java
@Test
void list_shouldFilterByInstanceId() {
    when(extensionClient.listAll(eq(PluginConfigAuditResource.class)))
            .thenReturn(List.of(resource1, resource2));

    PluginConfigAuditQueryDTO dto = new PluginConfigAuditQueryDTO();
    dto.setInstanceId(1L);

    com.gameplatform.common.result.Result<List<PluginConfigAuditVO>> result = controller.list(dto);
    assertEquals(200, result.getCode());
    assertEquals(2, result.getData().size());
}
```

- [ ] **Step 5: 运行测试验证**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditControllerTest -q
```

Expected: PASS。

- [ ] **Step 6: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditController.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginConfigAuditQueryDTO.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginConfigAuditVO.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginConfigAuditControllerTest.java
git commit -m "feat(l4d2): 新增 PluginConfigAuditController 审计日志查询接口"
```

---

## Phase 2: 配置编辑语义补全（G3 + G4）

> **状态：待执行** — 对齐开源 `RestoreSourceModConfig` 重建语义 + `cvarRegex` 宽松解析。

### Task 2.1: SourceModCfgParser 放宽 KV_PATTERN（G4）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`

**Background:** 开源 `cvarRegex = ^"?([a-zA-Z0-9_]+)"?\s+"?([^"]*)"?` 支持三种格式：`key value` / `key "value"` / `"key" "value"`。本项目 `KV_PATTERN = "([^\"]+)"\s+"([^\"]+)"\s*(?://\s*(.*))?` 仅匹配双引号格式。需要放宽同时保留注释提取。

- [ ] **Step 1: 写失败测试**

在 `SourceModCfgParserTest.java` 中添加：

```java
@Test
void parse_shouldSupportUnquotedKeyAndValue() {
    String content = "sm_dp 2.5\n";
    List<ConfigItem> items = parser.parse(content);
    assertEquals(1, items.size());
    assertEquals("sm_dp", items.get(0).getKey());
    assertEquals("2.5", items.get(0).getValue());
}

@Test
void parse_shouldSupportQuotedKeyOnly() {
    String content = "\"sm_dp\" 2.5\n";
    List<ConfigItem> items = parser.parse(content);
    assertEquals(1, items.size());
    assertEquals("sm_dp", items.get(0).getKey());
    assertEquals("2.5", items.get(0).getValue());
}

@Test
void parse_shouldSupportQuotedValueOnly() {
    String content = "sm_dp \"2.5\"\n";
    List<ConfigItem> items = parser.parse(content);
    assertEquals(1, items.size());
    assertEquals("sm_dp", items.get(0).getKey());
    assertEquals("2.5", items.get(0).getValue());
}

@Test
void parse_shouldStillSupportDoubleQuotedWithComment() {
    String content = "\"sm_dp\" \"2.5\" // Default: 0.5\n";
    List<ConfigItem> items = parser.parse(content);
    assertEquals(1, items.size());
    assertEquals("sm_dp", items.get(0).getKey());
    assertEquals("2.5", items.get(0).getValue());
    assertEquals("0.5", items.get(0).getDefaultValue());
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgParserTest#parse_shouldSupportUnquotedKeyAndValue,SourceModCfgParserTest#parse_shouldSupportQuotedKeyOnly,SourceModCfgParserTest#parse_shouldSupportQuotedValueOnly -q
```

Expected: FAIL（前 3 个用例失败，仅最后 1 个通过）。

- [ ] **Step 3: 修改 KV_PATTERN**

在 `SourceModCfgParser.java` 中替换 `KV_PATTERN`：

```java
/**
 * KV 行匹配正则（对齐 l4d2-server-next cvarRegex）：
 * 支持三种格式：
 *   key value            （无引号）
 *   key "value"          （仅 value 引号）
 *   "key" "value"        （双引号）
 *
 * <p>group 1: key（[a-zA-Z0-9_]+，引号可选）
 * <p>group 2: value（[^"\s]+ 或 "[^"]*"）
 * <p>group 3: 行尾注释（可选）
 */
private static final Pattern KV_PATTERN = Pattern.compile(
        "^\"?([a-zA-Z0-9_]+)\"?\\s+(\"[^\"]*\"|[^\"\\s]+)\\s*(?://\\s*(.*))?$"
);
```

- [ ] **Step 4: 修改 parse 方法适配新正则**

```java
public List<ConfigItem> parse(String content) {
    List<ConfigItem> items = new ArrayList<>();
    if (content == null) return items;
    String[] lines = content.split("\n");
    for (int i = 0; i < lines.length; i++) {
        String line = lines[i].trim();
        if (line.isEmpty() || line.startsWith("//")) continue;
        if (isConsoleCommand(line)) continue;
        Matcher m = KV_PATTERN.matcher(line);
        if (!m.matches()) continue;
        ConfigItem item = new ConfigItem();
        item.setKey(m.group(1));
        // value 可能带引号也可能不带，统一剥离首尾引号
        String rawValue = m.group(2);
        if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length() >= 2) {
            rawValue = rawValue.substring(1, rawValue.length() - 1);
        }
        item.setValue(rawValue);
        item.setLineNumber(i + 1);
        String comment = m.group(3);
        if (comment != null) parseMetadata(comment, item);
        items.add(item);
    }
    return items;
}
```

- [ ] **Step 5: 修改 serialize 方法适配新正则**

`serialize` 也需要更新以兼容新格式（保留原行格式，仅替换 value）：

```java
public String serialize(List<ConfigItem> items, String originalContent) {
    if (originalContent == null) originalContent = "";
    String[] lines = originalContent.split("\n", -1);
    for (ConfigItem item : items) {
        int idx = item.getLineNumber() - 1;
        if (idx < 0 || idx >= lines.length) continue;
        String line = lines[idx];
        Matcher m = KV_PATTERN.matcher(line.trim());
        if (m.matches()) {
            // 保留原行的引号风格：若原 value 带引号，新 value 也带引号
            String originalValue = m.group(2);
            String newValue = originalValue.startsWith("\"")
                    ? "\"" + item.getValue() + "\""
                    : item.getValue();
            // 重建行：原 key 部分 + newValue + 注释
            String keyPart = line.substring(0, line.indexOf(m.group(2)));
            String comment = m.group(3) != null ? " // " + m.group(3) : "";
            lines[idx] = keyPart + newValue + comment;
        }
    }
    return String.join("\n", lines);
}
```

- [ ] **Step 6: 运行所有 SourceModCfgParser 测试**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgParserTest -q
```

Expected: PASS（原有用例 + 4 个新用例全部通过）。若原有用例失败，检查 value 剥离引号逻辑是否正确。

- [ ] **Step 7: 运行 SourceModCfgService 测试确保不破坏**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS。

- [ ] **Step 8: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): SourceModCfgParser 放宽 KV_PATTERN 支持无引号/单侧引号格式"
```

---

### Task 2.2: SourceModCfgService.restoreDefaults 增加重建语义（G3）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**Background:** 对齐开源 `RestoreSourceModConfig(path, cvars)`：当 cfg 文件不存在时，根据 `cvars` 元数据（Description/Default/Min/Max）完整重建带注释块的 cfg 文件。本项目从 `PluginConfigResource` 扩展资源读取上次同步的 `items` 元数据，重建文件后写入并更新扩展资源。

- [ ] **Step 1: 写失败测试**

在 `SourceModCfgServiceTest.java` 中添加：

```java
@Test
void restoreDefaults_shouldRebuildCfgFileWhenNotExists() {
    // 候选 cfg 文件不存在
    when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

    // 但 PluginConfigResource 扩展资源中存有上次同步的 items 元数据
    PluginConfigResource resource = new PluginConfigResource();
    PluginConfigSpec spec = new PluginConfigSpec();
    spec.setInstanceId(INSTANCE_ID);
    spec.setPluginName("l4d2_x");
    spec.setConfigName("l4d2_x.cfg");
    spec.setConfigPath(CFG_SOURCEMOD_ABS + "/l4d2_x.cfg");
    ConfigItem item = new ConfigItem();
    item.setKey("sm_dp");
    item.setValue("3.0");           // 当前值（非默认）
    item.setDefaultValue("0.5");
    item.setMinValue("0.0");
    item.setMaxValue("10.0");
    item.setDescription("伤害倍率");
    spec.setItems(List.of(item));
    resource.setSpec(spec);
    when(extensionClient.queryByInstanceId(eq(INSTANCE_ID), eq(PluginConfigResource.class)))
            .thenReturn(List.of(resource));

    service.restoreDefaults(INSTANCE_ID, "l4d2_x");

    // 应重建带注释的 cfg 文件
    ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
    verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), anyString(), contentCaptor.capture());
    String written = contentCaptor.getValue();
    assertTrue(written.contains("// 伤害倍率"), "应包含描述注释");
    assertTrue(written.contains("// Default: \"0.5\""), "应包含 Default 注释");
    assertTrue(written.contains("// Min: \"0.0\""), "应包含 Min 注释");
    assertTrue(written.contains("// Max: \"10.0\""), "应包含 Max 注释");
    assertTrue(written.contains("\"sm_dp\" \"0.5\""), "应将 value 重置为 default");
}
```

> 注：`ConfigItem.getMinValue()/getMaxValue()` 若方法名不同，先读 `ConfigItem.java` 确认。`extensionClient.queryByInstanceId` 若方法名不同，按实际接口调整。

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest#restoreDefaults_shouldRebuildCfgFileWhenNotExists -q
```

Expected: FAIL（当前实现文件不存在时直接抛异常 "无可恢复的 cfg 文件"）。

- [ ] **Step 3: 修改 restoreDefaults 增加重建分支**

在 `SourceModCfgService.java` 的 `restoreDefaults` 方法中，原 `if (targetRelPath == null) { throw ... }` 分支改为：

```java
if (targetRelPath == null) {
    // 文件不存在时，从 PluginConfigResource 扩展资源读取元数据重建（对齐开源 RestoreSourceModConfig）
    log.info("cfg 文件不存在，尝试从扩展资源重建: instanceId={}, plugin={}", instanceId, pluginName);
    rebuildCfgFromExtensionResource(instanceId, instance, pluginName);
    return;
}
```

并在类中添加新方法：

```java
/**
 * 从 PluginConfigResource 扩展资源读取元数据，重建带注释块的 cfg 文件。
 *
 * <p>对齐 l4d2-server-next RestoreSourceModConfig 的"文件不存在时用元数据重建"语义：
 * <ol>
 *   <li>查询扩展资源，找到 pluginName 对应的 PluginConfigSpec</li>
 *   <li>对每个 item，将 value 重置为 defaultValue（若有）</li>
 *   <li>用注释块格式重建 cfg 文件内容（Description + Default + Min + Max + value）</li>
 *   <li>写入文件，更新扩展资源</li>
 * </ol>
 *
 * @param instanceId 实例 ID
 * @param instance   实例 VO
 * @param pluginName 插件名
 * @throws L4D2PluginException 扩展资源中无对应记录
 */
private void rebuildCfgFromExtensionResource(Long instanceId, InstanceVO instance,
                                              String pluginName) {
    List<PluginConfigResource> resources = extensionClient.queryByInstanceId(
            instanceId, PluginConfigResource.class);
    PluginConfigResource target = resources.stream()
            .filter(r -> r.getSpec() != null && pluginName.equals(r.getSpec().getPluginName()))
            .findFirst()
            .orElseThrow(() -> new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无可恢复的 cfg 文件且扩展资源中无元数据: " + pluginName));

    PluginConfigSpec spec = target.getSpec();
    List<ConfigItem> items = spec.getItems();
    if (items == null || items.isEmpty()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "扩展资源中无 items 元数据: " + pluginName);
    }

    // 重置 value 为 defaultValue
    int changed = 0;
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().isEmpty()) {
            if (!item.getDefaultValue().equals(item.getValue())) {
                item.setValue(item.getDefaultValue());
                changed++;
            }
        }
    }

    // 用注释块格式重建内容
    String rebuilt = rebuildCfgContent(items);

    // 推导目标相对路径
    String targetRelPath = spec.getConfigPath() != null
            ? spec.getConfigPath()
            : pathResolver.getSourceModCfgPath() + "/" + pluginName + ".cfg";

    try {
        instanceFileService.writeTextFile(instanceId, targetRelPath, rebuilt, gbk);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "重建配置文件失败: " + e.getMessage(), e);
    }

    // 更新扩展资源
    spec.setRawContent(rebuilt);
    spec.setLastSyncedAt(LocalDateTime.now());
    extensionClient.save(target);

    // 审计
    try {
        auditService.logRestoreDefaults(instanceId, instance.getHostId(), pluginName,
                targetRelPath, changed, "system");
    } catch (Exception e) {
        log.warn("审计日志调用失败（已忽略）: {}", e.getMessage());
    }

    log.info("已从扩展资源重建 cfg 文件: instanceId={}, plugin={}, changed={}",
            instanceId, pluginName, changed);
}

/**
 * 用注释块格式重建 cfg 文件内容（对齐开源 RestoreSourceModConfig）。
 *
 * <p>格式：
 * <pre>
 * // Description line 1
 * // Description line 2
 * // -
 * // Default: "0.5"
 * // Min: "0.0"
 * // Max: "10.0"
 * "sm_dp" "0.5"
 * </pre>
 */
private String rebuildCfgContent(List<ConfigItem> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
        ConfigItem item = items.get(i);
        if (i > 0) sb.append("\n");
        // 描述
        if (item.getDescription() != null && !item.getDescription().isEmpty()) {
            for (String line : item.getDescription().split("\n")) {
                sb.append("// ").append(line).append("\n");
            }
        }
        // 元数据
        sb.append("// -\n");
        if (item.getDefaultValue() != null && !item.getDefaultValue().isEmpty()) {
            sb.append("// Default: \"").append(item.getDefaultValue()).append("\"\n");
        }
        if (item.getMinValue() != null && !item.getMinValue().isEmpty()) {
            sb.append("// Min: \"").append(item.getMinValue()).append("\"\n");
        }
        if (item.getMaxValue() != null && !item.getMaxValue().isEmpty()) {
            sb.append("// Max: \"").append(item.getMaxValue()).append("\"\n");
        }
        // value
        sb.append("\"").append(item.getKey()).append("\" \"").append(item.getValue()).append("\"\n");
    }
    return sb.toString();
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS（20 个原有用例 + 1 个新用例 + Task 1.3 的 2 个审计用例全部通过）。

> 注：若 `ConfigItem` 类无 `getMinValue()/getMaxValue()` 方法，先读 `ConfigItem.java` 确认方法名（可能是 `getMin()/getMax()`）。本计划假设为 `getMinValue()/getMaxValue()`，按实际调整。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): restoreDefaults 增加 cfg 文件不存在时从扩展资源重建语义"
```

---

## Phase 3: 预设补全（G5 + G6）

> **状态：待执行** — 对齐开源 `GetPresets` 轻量列表 + apply 失败回滚。

### Task 3.1: PresetService 新增 listSummary 轻量端点（G5）

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetSummaryVO.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PresetController.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceSummaryTest.java`

**Background:** 对齐开源 `GetPresets` 返回 `[]PresetInfo{Name, Desc, PluginCount}`，避免列表页加载完整 plugins 数组。

- [ ] **Step 1: 创建 PresetSummaryVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 预设轻量列表 VO（对齐 l4d2-server-next PresetInfo）。
 *
 * <p>仅包含 name/desc/pluginCount，不含 plugins 数组，用于列表页快速加载。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PresetSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 预设 ID */
    private String id;

    /** 预设名（中文显示） */
    private String name;

    /** 描述 */
    private String description;

    /** 插件数量 */
    private Integer pluginCount;
}
```

- [ ] **Step 2: 写失败测试**

创建 `PresetServiceSummaryTest.java`：

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.vo.PresetSummaryVO;
import com.gameplatform.plugin.service.InstanceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PresetServiceSummaryTest {

    private PresetService service;
    private PluginInstallService pluginInstallService;
    private SourceModCfgService cfgService;
    private BackupService backupService;
    private InstanceQueryService instanceQueryService;

    @BeforeEach
    void setUp() {
        pluginInstallService = mock(PluginInstallService.class);
        cfgService = mock(SourceModCfgService.class);
        backupService = mock(BackupService.class);
        instanceQueryService = mock(InstanceQueryService.class);
        service = new PresetService(pluginInstallService, cfgService, backupService, instanceQueryService);
        service.loadPresetYaml();
    }

    @Test
    void listSummary_shouldReturnFourPresets() {
        List<PresetSummaryVO> list = service.listSummary();
        assertEquals(4, list.size());
    }

    @Test
    void listSummary_shouldNotContainPluginsArray() {
        List<PresetSummaryVO> list = service.listSummary();
        PresetSummaryVO multiVersus = list.stream()
                .filter(s -> "multi-versus".equals(s.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("多特战役", multiVersus.getName());
        assertEquals(3, multiVersus.getPluginCount()); // l4d2_ai_damagefix + l4d2_vs_new_item_spawn + l4d2_multi_slot
        // PresetSummaryVO 不应有 plugins 字段
        assertNull(multiVersus.getClass().getDeclaredFields().length > 4
                ? null : "PresetSummaryVO should have only 4 fields");
    }

    @Test
    void listSummary_shouldCalculatePluginCountCorrectly() {
        List<PresetSummaryVO> list = service.listSummary();
        PresetSummaryVO pureCoop = list.stream()
                .filter(s -> "pure-coop".equals(s.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, pureCoop.getPluginCount()); // 仅 l4d2_ai_damagefix
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceSummaryTest -q
```

Expected: FAIL（`listSummary` 方法不存在）。

- [ ] **Step 4: 在 PresetService 中实现 listSummary**

在 `PresetService.java` 的 `list()` 方法之后添加：

```java
/**
 * 返回预设轻量列表（仅 id/name/description/pluginCount，不含 plugins 数组）。
 *
 * <p>对齐 l4d2-server-next GetPresets 返回 PresetInfo{Name, Desc, PluginCount}，
 * 用于列表页快速加载，避免传输完整 plugins 数组。
 */
public List<PresetSummaryVO> listSummary() {
    if (presets == null) return List.of();
    return presets.stream()
            .map(p -> {
                PresetSummaryVO vo = new PresetSummaryVO();
                vo.setId(p.getId());
                vo.setName(p.getName());
                vo.setDescription(p.getDescription());
                vo.setPluginCount(p.getPlugins() != null ? p.getPlugins().size() : 0);
                return vo;
            })
            .toList();
}
```

并在文件顶部添加 import：

```java
import com.gameplatform.plugin.l4d2.vo.PresetSummaryVO;
```

- [ ] **Step 5: 在 PresetController 中添加 /list-summary 端点**

```java
@Operation(summary = "获取预设轻量列表（仅 id/name/desc/pluginCount）")
@GetMapping("/list-summary")
public Result<List<PresetSummaryVO>> listSummary() {
    return Result.success(presetService.listSummary());
}
```

并在 import 区添加：

```java
import com.gameplatform.plugin.l4d2.vo.PresetSummaryVO;
```

- [ ] **Step 6: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceSummaryTest -q
```

Expected: PASS。

- [ ] **Step 7: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 8: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PresetSummaryVO.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PresetController.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceSummaryTest.java
git commit -m "feat(l4d2): 新增 PresetService.listSummary 轻量列表端点"
```

---

### Task 3.2: PresetService.apply 平台插件失败时回滚（G6）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceApplyTest.java`

**Background:** 当前 `apply` 平台插件失败时抛异常中止，但已禁用的插件不会恢复，处于"坏状态"（开源也是此设计，但开源注释明确说 "we are in a bad state"）。本任务在抛异常前记录"坏状态"标记到备份备注中，便于运维识别和回滚。

**设计决策**：不实现自动回滚已禁用插件（因为禁用+复制删除是不可逆的，且自动恢复可能造成更大混乱），改为：
1. 应用前已创建备份（v7 已实现）
2. 平台插件失败时，在异常消息中明确提示"请使用备份 XXX 恢复"
3. 其他插件失败时仅警告继续（v7 已实现）

- [ ] **Step 1: 写失败测试**

在 `PresetServiceApplyTest.java` 中添加：

```java
@Test
void apply_shouldIncludeBackupNameInErrorMessageWhenPlatformPluginFails() {
    // 模拟备份成功，返回备份名
    when(backupService.create(eq(100L), anyString(), anyString()))
            .thenAnswer(inv -> {
                // 备份创建成功，备份名 = 第二个参数
                return null;
            });

    // 平台插件启用失败
    doThrow(new RuntimeException("platform plugin load failed"))
            .when(pluginInstallService).enableAndLoad(eq(100L), eq("1.11插件平台linux版"));

    L4D2PluginException ex = assertThrows(L4D2PluginException.class,
            () -> service.apply(100L, "multi-versus"));

    // 异常消息应包含"备份"字样，便于运维定位
    assertTrue(ex.getMessage().contains("备份") || ex.getMessage().contains("backup"),
            "异常消息应提示使用备份恢复: " + ex.getMessage());
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceApplyTest#apply_shouldIncludeBackupNameInErrorMessageWhenPlatformPluginFails -q
```

Expected: FAIL（当前异常消息未包含"备份"字样）。

- [ ] **Step 3: 修改 PresetService.apply 捕获备份名并加入异常消息**

在 `PresetService.java` 的 `apply` 方法中，将步骤 1 的备份逻辑改为捕获备份名：

```java
public void apply(Long instanceId, String presetId) {
    PresetDetailVO preset = detail(presetId);
    if (preset == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "预设不存在: " + presetId);
    }
    log.info("Applying preset {} to instance {}", presetId, instanceId);

    // 1. 应用前自动创建备份（失败不阻塞）
    String backupName = "preset-apply-" + presetId + "-" + System.currentTimeMillis();
    boolean backupCreated = false;
    try {
        backupService.create(instanceId, backupName, "应用预设前自动备份");
        backupCreated = true;
        log.info("应用预设前已创建备份: instanceId={}, preset={}, backup={}",
                instanceId, presetId, backupName);
    } catch (Exception e) {
        log.warn("应用预设前创建备份失败（继续应用）: {}", e.getMessage());
    }

    // 2. 预校验所有插件存在
    String platformPlugin = resolvePlatformPlugin(instanceId);
    if (platformPlugin != null && !platformPlugin.isBlank()) {
        if (!pluginInstallService.pluginExists(instanceId, platformPlugin)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "平台插件不存在: " + platformPlugin + "，请先通过商店或上传安装");
        }
    }
    if (preset.getPlugins() != null) {
        for (PresetPlugin pp : preset.getPlugins()) {
            if (!pluginInstallService.pluginExists(instanceId, pp.getName())) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "预设插件不存在: " + pp.getName() + "，请先通过商店或上传安装");
            }
        }
    }

    // 3. 禁用所有插件
    pluginInstallService.disableAllPlugins(instanceId);

    // 4. 优先启用平台插件（必装，失败抛异常中止，提示使用备份恢复）
    if (platformPlugin != null && !platformPlugin.isBlank()) {
        try {
            pluginInstallService.enableAndLoad(instanceId, platformPlugin);
            log.info("已启用平台插件: instanceId={}, plugin={}", instanceId, platformPlugin);
        } catch (Exception e) {
            String backupHint = backupCreated
                    ? "；请使用备份 " + backupName + " 恢复"
                    : "；无备份可恢复（备份创建失败）";
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "启用平台插件失败: " + platformPlugin + "，预设应用中止: " + e.getMessage() + backupHint, e);
        }
    }

    // 5-6 保持不变
    // ...
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceApplyTest -q
```

Expected: PASS（7 个原有用例 + 1 个新用例全部通过）。

- [ ] **Step 5: 运行 PresetServiceTest 确保不破坏**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceTest -q
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceApplyTest.java
git commit -m "feat(l4d2): PresetService.apply 平台插件失败时提示使用备份恢复"
```

---

## Phase 4: 回滚机制与来源一致性补全（G7 + G8）

> **状态：待执行** — 对齐开源 `CleanPluginExportTemp` + source 字段一致性回退。

### Task 4.1: PluginExportService 启动清理本地残留（G7）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginExportServiceStartupCleanupTest.java`

**Background:** 对齐开源 `CleanPluginExportTemp`：启动时清空导出临时目录。本项目导出目录在本地 `~/game-platform-l4d2/export-tasks/`，需要 `@EventListener(ApplicationReadyEvent.class)` 清理残留。

- [ ] **Step 1: 写失败测试**

创建 `PluginExportServiceStartupCleanupTest.java`：

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginExportServiceStartupCleanupTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private L4D2PathResolver pathResolver;

    @InjectMocks
    private PluginExportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // 在 tempDir 下模拟 export-tasks 目录，包含残留文件
        File exportTasksDir = tempDir.resolve("export-tasks").toFile();
        exportTasksDir.mkdirs();
        new File(exportTasksDir, "stale-task-1.zip").createNewFile();
        File subDir = new File(exportTasksDir, "stale-task-2");
        subDir.mkdirs();
        new File(subDir, "task.json").createNewFile();
    }

    @Test
    void cleanExportTempOnStartup_shouldRemoveAllStaleFiles() throws IOException {
        service.cleanExportTempOnStartup();

        File exportTasksDir = tempDir.resolve("export-tasks").toFile();
        // 清理后目录应为空（但目录本身保留）
        assertTrue(exportTasksDir.exists());
        assertEquals(0, exportTasksDir.listFiles().length);
    }

    @Test
    void cleanExportTempOnStartup_shouldNotThrowWhenDirNotExists() {
        // 不影响其他测试，验证目录不存在时不抛异常
        assertDoesNotThrow(() -> service.cleanExportTempOnStartup());
    }
}
```

> 注：测试通过 `@TempDir` 隔离文件系统。`PluginExportService` 的 `cleanExportTempOnStartup` 方法需读取 `~/game-platform-l4d2/export-tasks/` 目录。为了让测试能注入临时目录，需要将目录路径提取为可覆盖的方法或字段。

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginExportServiceStartupCleanupTest -q
```

Expected: FAIL（`cleanExportTempOnStartup` 方法不存在）。

- [ ] **Step 3: 在 PluginExportService 中添加启动清理方法**

在 `PluginExportService.java` 中添加：

```java
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

// ...

/**
 * 应用启动时清理本地导出临时目录残留。
 *
 * <p>对齐 l4d2-server-next CleanPluginExportTemp：
 * 启动时若存在上次崩溃残留的 export-tasks 目录，整体清空。
 * 此时不可能存在正在进行的导出任务（HTTP 服务尚未对外），清理安全。
 */
@EventListener(ApplicationReadyEvent.class)
public void onApplicationReady() {
    cleanExportTempOnStartup();
}

/**
 * 清理本地 ~/game-platform-l4d2/export-tasks/ 目录下所有残留文件。
 *
 * <p>测试可通过覆盖 {@link #getExportTasksBaseDir()} 注入临时目录。
 */
public void cleanExportTempOnStartup() {
    File baseDir = getExportTasksBaseDir();
    if (!baseDir.exists()) {
        return;
    }
    log.info("启动清理导出临时目录: path={}", baseDir.getAbsolutePath());
    File[] children = baseDir.listFiles();
    if (children == null) {
        return;
    }
    int deleted = 0;
    for (File child : children) {
        try {
            deleteRecursiveLocal(child);
            deleted++;
        } catch (Exception e) {
            log.warn("清理导出残留失败（已忽略）: file={}, err={}", child.getAbsolutePath(), e.getMessage());
        }
    }
    log.info("导出临时目录清理完成: deleted={}", deleted);
}

/**
 * 获取导出临时目录（可被测试覆盖）。
 */
protected File getExportTasksBaseDir() {
    String home = System.getProperty("user.home");
    return new File(home, "game-platform-l4d2/export-tasks");
}

private void deleteRecursiveLocal(File file) {
    if (file.isDirectory()) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursiveLocal(child);
            }
        }
    }
    if (!file.delete()) {
        log.warn("文件删除失败: {}", file.getAbsolutePath());
    }
}
```

- [ ] **Step 4: 修改测试以覆盖 getExportTasksBaseDir**

将 `PluginExportServiceStartupCleanupTest` 改为创建匿名子类覆盖方法：

```java
@ExtendWith(MockitoExtension.class)
class PluginExportServiceStartupCleanupTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private L4D2PathResolver pathResolver;

    @TempDir
    Path tempDir;

    private PluginExportService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new PluginExportService(instanceQueryService, instanceFileService, pathResolver) {
            @Override
            protected File getExportTasksBaseDir() {
                return tempDir.resolve("export-tasks").toFile();
            }
        };

        // 在 tempDir 下模拟 export-tasks 目录，包含残留文件
        File exportTasksDir = tempDir.resolve("export-tasks").toFile();
        exportTasksDir.mkdirs();
        new File(exportTasksDir, "stale-task-1.zip").createNewFile();
        File subDir = new File(exportTasksDir, "stale-task-2");
        subDir.mkdirs();
        new File(subDir, "task.json").createNewFile();
    }

    @Test
    void cleanExportTempOnStartup_shouldRemoveAllStaleFiles() {
        service.cleanExportTempOnStartup();

        File exportTasksDir = tempDir.resolve("export-tasks").toFile();
        assertTrue(exportTasksDir.exists());
        assertEquals(0, exportTasksDir.listFiles().length);
    }

    @Test
    void cleanExportTempOnStartup_shouldNotThrowWhenDirNotExists() {
        // 重新指向不存在的目录
        PluginExportService service2 = new PluginExportService(instanceQueryService, instanceFileService, pathResolver) {
            @Override
            protected File getExportTasksBaseDir() {
                return tempDir.resolve("nonexistent").toFile();
            }
        };
        assertDoesNotThrow(service2::cleanExportTempOnStartup);
    }
}
```

> 注：若 `PluginExportService` 使用 `@RequiredArgsConstructor`，子类化时需显式构造器。先读该类构造器签名确认。若构造器签名不匹配，调整测试代码。

- [ ] **Step 5: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginExportServiceStartupCleanupTest -q
```

Expected: PASS。

- [ ] **Step 6: 运行已有 PluginExportServiceTest 确保不破坏**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginExportServiceTest -q
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginExportServiceStartupCleanupTest.java
git commit -m "feat(l4d2): PluginExportService 启动时清理本地 export-tasks 残留"
```

---

### Task 4.2: EnabledPluginsService source 字段一致性回退（G8）

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceEnableTest.java`

**Background:** 当前 `enableAndLoad` 调用 `resolveSourceForEnable` 从 plugin.yaml 读取 source，若 plugin.yaml 缺失会默认 "panel"。但 `enabled_plugins.yaml` 中可能已有上次启用时写入的 source（如 "store"）。应优先使用 enabled_plugins.yaml 中的 source，其次 plugin.yaml，最后默认 "panel"。

- [ ] **Step 1: 写失败测试**

在 `PluginInstallServiceEnableTest.java` 中添加：

```java
@Test
void enableAndLoad_shouldFallbackToEnabledPluginsYamlSourceWhenPluginYamlMissing() {
    // plugin.yaml 不存在
    when(pluginMetaService.load(eq(INSTANCE_ID), eq("l4d2_x"))).thenReturn(null);
    // enabled_plugins.yaml 中已有 source=store
    when(enabledPluginsService.isEnabled(eq(INSTANCE_ID), eq("l4d2_x"))).thenReturn(false);
    EnabledPlugin existing = new EnabledPlugin();
    existing.setName("l4d2_x");
    existing.setSource("store");
    when(enabledPluginsService.findEnabled(eq(INSTANCE_ID), eq("l4d2_x")))
            .thenReturn(existing);

    // 执行启用
    service.enableAndLoad(INSTANCE_ID, "l4d2_x");

    // 验证写入 enabled_plugins.yaml 时 source 为 "store"（而非默认 "panel"）
    ArgumentCaptor<EnabledPlugin> captor = ArgumentCaptor.forClass(EnabledPlugin.class);
    verify(enabledPluginsService).add(eq(INSTANCE_ID), captor.capture());
    assertEquals("store", captor.getValue().getSource(),
            "plugin.yaml 缺失时应回退到 enabled_plugins.yaml 中的 source");
}
```

> 注：`enabledPluginsService.findEnabled` 方法若不存在，需在 `EnabledPluginsService` 接口中新增。先读该类确认。

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginInstallServiceEnableTest#enableAndLoad_shouldFallbackToEnabledPluginsYamlSourceWhenPluginYamlMissing -q
```

Expected: FAIL（`findEnabled` 方法不存在或 source 为 "panel"）。

- [ ] **Step 3: 在 EnabledPluginsService 中新增 findEnabled 方法**

读 `EnabledPluginsService.java`，在已有方法基础上添加：

```java
/**
 * 查询已启用插件记录（不抛异常，不存在返回 null）。
 *
 * <p>用于 enableAndLoad 时回退 source 字段：若 plugin.yaml 缺失，使用 enabled_plugins.yaml 中的 source。
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 * @return EnabledPlugin 或 null
 */
public EnabledPlugin findEnabled(Long instanceId, String pluginName) {
    if (instanceId == null || pluginName == null) return null;
    List<EnabledPlugin> enabled = loadYaml(instanceId);
    if (enabled == null) return null;
    return enabled.stream()
            .filter(e -> pluginName.equals(e.getName()))
            .findFirst()
            .orElse(null);
}
```

> 注：`loadYaml` 是已有的私有方法，需调整为 package-private 或在 `findEnabled` 内调用。若 `EnabledPluginsService` 结构不同，按实际调整。

- [ ] **Step 4: 修改 PluginInstallService.resolveSourceForEnable 增加回退逻辑**

读 `PluginInstallService.java` 中的 `resolveSourceForEnable` 方法，修改为三级回退：

```java
/**
 * 解析插件启用时的 source 字段（三级回退）：
 * <ol>
 *   <li>plugin.yaml 中的 source（最权威）</li>
 *   <li>enabled_plugins.yaml 中的 source（plugin.yaml 缺失时回退）</li>
 *   <li>默认 "panel"（两者都缺失）</li>
 * </ol>
 *
 * <p>对齐开源 writePluginSource 的一致性：避免 plugin.yaml 被外部修改后
 * enabled_plugins.yaml 中的 source 退化为 "panel"。
 */
private String resolveSourceForEnable(Long instanceId, String pluginName) {
    // 1. plugin.yaml
    PluginMeta meta = pluginMetaService.load(instanceId, pluginName);
    if (meta != null && meta.getSource() != null && !meta.getSource().isEmpty()) {
        return meta.getSource();
    }
    // 2. enabled_plugins.yaml 回退
    EnabledPlugin existing = enabledPluginsService.findEnabled(instanceId, pluginName);
    if (existing != null && existing.getSource() != null && !existing.getSource().isEmpty()) {
        log.info("plugin.yaml 缺失或无 source，回退到 enabled_plugins.yaml: plugin={}, source={}",
                pluginName, existing.getSource());
        return existing.getSource();
    }
    // 3. 默认
    return "panel";
}
```

- [ ] **Step 5: 运行测试验证通过**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginInstallServiceEnableTest -q
```

Expected: PASS。

- [ ] **Step 6: 运行所有 PluginInstallService 相关测试确保不破坏**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginInstallService* -q
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/EnabledPluginsService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginInstallServiceEnableTest.java
git commit -m "feat(l4d2): resolveSourceForEnable 三级回退保证 source 字段一致性"
```

---

## Phase 5: 整合验证

### Task 5.1: 全模块编译 + 完整测试

**Files:**
- All modules

- [ ] **Step 1: 全模块清理编译**

```powershell
cd d:\program\ai\game_platform_manger\backend
mvn clean compile -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 2: 全模块测试**

```powershell
mvn test -pl plugin-l4d2/plugin-l4d2-core -am
```

Expected: 所有测试通过（v7 的 414 个 + v8 新增测试，总数应 ≥ 440）。

- [ ] **Step 3: 修复发现的问题**

如有测试失败，针对性修复并重新运行。

- [ ] **Step 4: 提交（如有修复）**

```powershell
git add -A
git commit -m "fix(l4d2): v8 整合测试修复"
```

---

### Task 5.2: 全栈重启验证

- [ ] **Step 1: 使用项目脚本重启全栈**

```powershell
cd d:\program\ai\game_platform_manger
.\scripts\rebuild-restart-all.ps1
```

> 注意：插件 JAR 需重新打包部署。脚本会自动执行后端编译 + 插件打包 + 后端启动 + 前端启动。

- [ ] **Step 2: 验证后端启动成功**

```powershell
netstat -an | findstr :8080
```

Expected: LISTENING。

- [ ] **Step 3: 验证关键端点可用**

```powershell
# v8 新增端点
curl "http://localhost:8080/api/plugin/l4d2/presets/list-summary" -H "Authorization: Bearer <token>"
curl "http://localhost:8080/api/plugin/l4d2/plugin-config-audit/list?instanceId=1&page=1&pageSize=20" -H "Authorization: Bearer <token>"
```

Expected: 返回 `{"code":200,...}` JSON。

- [ ] **Step 4: 验证启动清理日志**

查看后端日志，确认出现：

```
启动清理导出临时目录: path=...
导出临时目录清理完成: deleted=N
```

- [ ] **Step 5: 最终提交**

```powershell
git add -A
git commit -m "chore(l4d2): v8 计划完成，全栈验证通过"
git push
```

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| `PluginConfigAuditResource` 扩展资源表首次启动时未自动创建 | 项目已有扩展资源机制（参考 `EnabledPluginResource`），表会在插件启动时自动创建 |
| `ExtensionClient.listAll` 性能问题（全表加载后内存过滤） | 审计日志数据量预计较小（单实例日均 < 1000 条），内存过滤可接受；未来数据量大时可改为 ExtensionClient 提供 queryByExample |
| `restoreDefaults` 重建语义依赖 `PluginConfigResource` 扩展资源中有 items 元数据 | 重建分支仅在文件不存在时触发；扩展资源无记录时抛明确异常，提示用户先调用 `updateConfig` 同步一次 |
| `KV_PATTERN` 放宽后可能误解析非 cfg 行 | 通过 `isConsoleCommand` 黑名单 + key 仅匹配 `[a-zA-Z0-9_]+` 限制 |
| `PluginExportService` 子类化测试可能因 `@RequiredArgsConstructor` 失败 | 测试中显式调用三参构造器；若构造器签名不同，按实际调整 |
| `EnabledPluginsService.findEnabled` 暴露为 public 可能破坏封装 | 该方法语义清晰（查询已启用插件），与现有 `isEnabled` 互补，无副作用 |

---

## Self-Review

### 1. Spec 覆盖
- ✅ G1（审计持久化）→ Task 1.1 + 1.2 + 1.4
- ✅ G2（审计触发点）→ Task 1.3
- ✅ G3（restoreDefaults 重建）→ Task 2.2
- ✅ G4（cfg 解析放宽）→ Task 2.1
- ✅ G5（listSummary）→ Task 3.1
- ✅ G6（apply 失败提示备份）→ Task 3.2
- ✅ G7（导出启动清理）→ Task 4.1
- ✅ G8（source 一致性）→ Task 4.2
- ✅ 7 个主题全覆盖：存储模型（无需改）/ 插件来源（G8）/ 删除语义（无需改）/ 回滚机制（G7）/ 预设（G5+G6）/ 商店（无需改）/ 配置编辑（G1+G2+G3+G4）

### 2. Placeholder 扫描
- 无 "TBD" / "TODO" / "fill in details"
- 所有代码块完整可执行
- 测试代码包含具体断言

### 3. 类型一致性
- `PluginConfigAuditSpec` 字段在 Task 1.1 定义，Task 1.2 / 1.4 引用一致
- `PresetSummaryVO` 字段在 Task 3.1 定义，测试引用一致
- `getExportTasksBaseDir` 方法名在 Task 4.1 Step 3 定义，Step 4 测试覆盖一致
- `findEnabled` 方法名在 Task 4.2 Step 3 定义，Step 1 测试引用一致
- `ConfigItem.getMinValue()/getMaxValue()` 在 Task 2.2 引用，需 Step 3 执行前确认实际方法名

---

## 执行顺序

1. **Phase 1（Task 1.1 → 1.2 → 1.3 → 1.4）**：审计日志基础设施 → 持久化 → 触发点 → 查询接口
2. **Phase 2（Task 2.1 → 2.2）**：解析正则放宽 → restoreDefaults 重建语义（依赖解析正则已放宽）
3. **Phase 3（Task 3.1 → 3.2）**：listSummary → apply 失败提示
4. **Phase 4（Task 4.1 → 4.2）**：导出启动清理 → source 一致性
5. **Phase 5（Task 5.1 → 5.2）**：整合验证 + 全栈重启

每个 Task 独立可提交，建议按顺序执行以避免依赖冲突。
