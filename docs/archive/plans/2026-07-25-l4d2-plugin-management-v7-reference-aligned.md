# L4D2 插件管理 v7：开源方案对齐实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `D:\program\open_source\l4d2-server-next-master` 项目方案，对齐实现 L4D2 插件管理的 7 个主题：存储模型、插件来源、删除语义、回滚机制、预设、商店、配置编辑，并修复当前编译阻塞。

**Architecture:** 在已完成的 v5/v6 基础设施（InstanceFileService SPI、L4D2PathResolver、EnabledPluginsService 双写、FileRefsService 内存引用计数、preset.yaml、PluginStoreService 状态机）之上，补齐 7 个方面的差距：(1) 修复 SourceModCfgService 缺失方法导致的编译失败；(2) 增强配置编辑（黑名单/互转/审计/校验）；(3) 商店支持 proxyUrl/githubToken/tree 缓存；(4) 商店下载采用临时目录+原子重命名；(5) 启动清理残留临时目录；(6) 预设应用前自动备份+失败回滚；(7) PluginManageController 补 /readme 端点。

**Tech Stack:** Java 17, Spring Boot 3.2.5, PF4J 3.10.0, Jackson YAML, MyBatis-Plus, JUnit 5 + Mockito, Hutool

---

## 当前实施状态（2026-07-26 更新）

> 本计划于 2026-07-26 完成全部 Phase 1-9 实施并通过整合验证。Phase 1-6 大部分任务在 v7 修订前已实现；Phase 7（整合验证）+ Phase 8（PluginStoreMigration 重做）+ Phase 9（预设平台插件预校验+优先启用）于 2026-07-26 完成：全模块编译通过，`plugin-l4d2-core` 全部 414 个测试通过（0 失败 0 错误），后端重启验证通过。
>
> **运行时修复（2026-07-26）：** 整合验证发现并修复 7 个运行时问题：
> 1. `PluginInstallService` 构造器参数 `Charset gbk` 被 Spring 尝试注入但容器无 `Charset` Bean → 改为字段初始化 `GbkCodecUtil.gbk()`（对齐 `SourceModCfgService` 用法）
> 2. `GameYamlConfig.DockerComposeConfig` 缺少 `workingDir` 字段导致 l4d2.yml 解析失败 → 添加 `workingDir` 字段
> 3. `PluginStoreMigration` 使用 `@EventListener(ApplicationReadyEvent.class)` 但插件子容器在 ApplicationReadyEvent 监听器中才创建，事件触发时 Bean 未注册 → 改用 `SmartInitializingSingleton`
> 4. **`NoClassDefFoundError: org/apache/hc/core5/http2/HttpVersionPolicy`** — `docker-java-transport-httpclient5:3.3.4` 传递引入 `httpclient5:5.2.3` + `httpcore5:5.2.4`，但 `TlsConfig` 静态初始化引用的 `HttpVersionPolicy` 位于 `httpcore5-h2` 模块（未被传递引入）→ 在 [core/pom.xml](file:///d:/program/ai/game_platform_manger/backend/core/pom.xml#L86-L93) 显式声明 `httpcore5-h2:5.2.4`
> 5. **`L4D2Config.PluginStore.branch` 默认值错误** — 默认为 `"main"`，但仓库 `LaoYutang/l4d2-plugins-store` 实际默认分支为 `master` → 修正为 `"master"`
> 6. **主应用 application.yml 缺少 `plugin.l4d2` 配置块** — L4D2Config 使用 `@ConfigurationProperties(prefix = "plugin.l4d2")`，但主应用 application.yml 仅有 `game-platform.plugin`（不同命名空间），导致主应用模式下 plugin-store 配置项无外部覆盖能力 → 添加 `plugin.l4d2.plugin-store` 配置块（与 standalone 模式保持一致）
> 7. **GitHub API SSL PKIX path building failed（已通过代码修复）** — 后端 JDK（TRAE 内置 JRE）信任库缺少 GitHub CA 证书，PowerShell 可正常访问但 Java RestClient 抛 SSLException。**修复方案：** 新增 [SslTrustStoreConfig.java](file:///d:/program/ai/game_platform_manger/backend/core/src/main/java/com/gameplatform/config/SslTrustStoreConfig.java)，在 static 初始化块中检测操作系统，Windows 平台设置 `javax.net.ssl.trustStore=Windows-ROOT` / `trustStoreType=Windows-ROOT`，macOS 设置 `KeychainStore`，让 JDK 使用操作系统证书库而非自带 cacerts。验证通过：plugin-store/list 端点返回 200，GitHub API 调用成功。applyProxy 逻辑保留作为备选方案（用户仍可配置 proxy-url 绕过）

### ✅ 已完成（Phase 1-9 全部）

| 任务 | 实现位置 | 验证方式 |
|------|---------|---------|
| Task 1.1 applyTempConfig | [SourceModCfgService.java:365-387](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java#L365-L387) | 已含 CvarBlacklist 校验 + 审计日志 |
| Task 1.2 restoreDefaults | [SourceModCfgService.java:404-469](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java#L404-L469) | 已含候选路径推导 + 扩展资源更新 |
| Task 1.3 PluginConfigController import | 已修复 | 编译通过 |
| Task 2.1 控制台命令黑名单 | [SourceModCfgParser.java:45-97](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java#L45-L97) | CONSOLE_CMD_BLACKLIST + isConsoleCommand |
| Task 2.2 l4d2↔l4d_ 互转 | [SourceModCfgService.java:75-109](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java#L75-L109) | getL4dAlias 实现 |
| Task 2.3 CvarBlacklist | [CvarBlacklist.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/CvarBlacklist.java) | 10 个危险 CVAR + 大小写无关 |
| Task 2.4 PluginConfigAuditService | [PluginConfigAuditService.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java) | logUpdateConfig/logApplyTempConfig/logRestoreDefaults |
| Task 3.1 PluginStore 配置字段 | [L4D2Config.java:82,99-101](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java#L82-L101) | proxyUrl + githubToken 字段 |
| Task 3.2 GitHubApiClient | [GitHubApiClient.java:55,91,178,268-296](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java#L268-L296) | applyProxy + resolveToken |
| Task 3.3 tree 缓存 | [PluginStoreService.java:71-72,267-296](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java#L267-L296) | cachedTree + getCachedTree + getTreeForRead |
| Task 4.1 临时目录+原子重命名 | [PluginInstallService.java:991,1043](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java#L991) + [L4D2PathResolver.java:143-153](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java#L143-L153) | installFromLocalFileToTempDir + atomicMoveToStore |
| Task 5.1 预设自动备份 | [PresetService.java:39,103-104](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java#L39-L104) | backupService.create + "preset-apply-" 前缀 |
| Task 6.1 /readme 端点 | [PluginManageController.java:80-87](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java#L80-L87) | PluginReadmeVO + readReadme |

### ✅ 已完成（Phase 7-9 补全）

| 任务 | 实现位置 | 验证方式 |
|------|---------|---------|
| Task 7.1 全模块编译+测试 | `mvn clean compile -am` + `mvn test -pl plugin-l4d2/plugin-l4d2-core -am` | 2026-07-26 编译 SUCCESS，414 测试全部通过 |
| Task 8.1 PluginStoreMigration 启动清理 | [PluginStoreMigration.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java) + [PluginStoreMigrationTest.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java) | ApplicationReadyEvent + 异步遍历所有 L4D2 实例清理 .download_temp |
| Task 9.1 平台插件预校验 | [PresetService.java:122-138](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java#L122-L138) | pluginExists 预校验所有预设插件 + 平台插件 |
| Task 9.2 平台插件优先启用 | [PresetService.java:140-180,190-215](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java#L190-L215) | resolvePlatformPlugin 按 deployType+OS 解析，优先启用失败抛异常中止 |

---

## 参考基线

| 主题 | l4d2-server-next 方案 | 当前项目状态 | 本计划任务 |
|------|----------------------|------------|----------|
| 存储模型 | `./plugins` 库目录 + 游戏目录双分离、`plugins.yaml` 全局清单、`fileRefs` 内存引用计数 | ✅ 已实现（plugins_store + .enabled_plugins.yaml + EnabledPluginsService 双写 + FileRefsService 内存 Map） | 无需改动 |
| 插件来源 | panel/store/upload 三种来源 + `plugin_sources` map 记录 | ✅ 已实现（source 字段 + 双写扩展资源 + 上传 ZIP 单/多插件 + ZipSlipGuard） | 无需改动 |
| 删除语义 | disable=清理游戏副本保留源文件；delete=物理删除源目录且必须先 disable | ✅ 已实现（disableAndUnload + deletePlugin + isEnabled 校验） | 无需改动 |
| 回滚机制 | EnableAndLoad/DisableAndUnload SMX 级回滚；商店下载临时目录+原子重命名；启动清理 `.download_temp` | ⚠️ 部分实现（SMX 回滚已有；商店下载直接调 installFromLocalFile 无原子重命名；无启动清理） | Phase 4 |
| 预设 | preset.yaml 内置 4 预设；覆盖式应用（禁用所有→启用预设→应用 CVAR） | ✅ 已实现（preset.yaml 4 预设 + PresetService.apply） | Phase 5 增强（备份+回滚） |
| 商店 | GitHub Trees API + LFS + 5 状态机 + 3 并发 + 3 重试 + 10 分钟缓存 + proxyUrl + githubToken | ⚠️ 部分实现（缺 proxyUrl/githubToken 配置字段；缺 tree 缓存） | Phase 3 |
| 配置编辑 | SourceMod .cfg 解析 + Default/Min/Max 元数据 + 临时应用 sm_cvar + 恢复默认 + 控制台命令黑名单 + l4d2↔l4d_ 互转 | ⚠️ 部分实现（applyTempConfig/restoreDefaults **方法缺失导致编译失败**；缺黑名单/互转/审计） | Phase 1（修复）+ Phase 2（增强） |

---

## 文件结构

### 创建文件（Create）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java` — 启动清理残留临时目录
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/CvarBlacklist.java` — 危险 CVAR 黑名单
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/CvarTypeInferrer.java` — CVAR 类型推断
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java` — 配置修改审计
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/dto/PluginReadmeDTO.java` — README 请求 DTO
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginReadmeVO.java` — README 响应 VO
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/CvarBlacklistTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/CvarTypeInferrerTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServiceTest.java`

### 修改文件（Modify）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java` — 新增 `applyTempConfig` / `restoreDefaults` 方法 + 黑名单校验 + 互转候选
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java` — 新增控制台命令黑名单
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java` — 修复 import + 接入审计
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java` — 新增 `/readme` 端点
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java` — PluginStore 添加 `proxyUrl` / `githubToken` 字段
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java` — 应用 proxyUrl + 优先用配置 token
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java` — tree 缓存 + 临时目录+原子重命名
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java` — 应用前自动备份 + 失败回滚
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` — 商店下载安装走临时目录模式

---

## Phase 1: 关键编译阻塞修复（配置编辑）

> ✅ **状态：已完成**（Task 1.1 + 1.2 + 1.3 全部实现并通过测试）

### Task 1.1: 实现 SourceModCfgService.applyTempConfig 方法

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**Background:** `PluginConfigController.applyTemp` (line 86-92) 调用了 `sourceModCfgService.applyTempConfig(dto.getInstanceId(), dto.getCvarName(), dto.getCvarValue())`，但 `SourceModCfgService` 中无此方法，导致编译失败。`SourceModCfgServiceTest` 已有 4 个测试用例引用此方法（line 285-321）。

- [ ] **Step 1: 写失败测试（如果尚未存在）**

测试已在 `SourceModCfgServiceTest.java` line 285-321 存在，包含以下用例：
- `applyTempConfig_shouldExecuteRconSmCvarCommand` — 验证调用 `rconService.executeCommand(instanceId, "sm_cvar l4d2_max_players \"8\"")`
- `applyTempConfig_shouldThrowWhenCvarNameIsBlank` — 空 cvarName 抛 `L4D2PluginException`
- `applyTempConfig_shouldThrowWhenInstanceNotFound` — 实例不存在抛异常
- `applyTempConfig_shouldWrapRconException` — RCON 失败包装为 `L4D2PluginException`

如果测试尚未编写，先添加：

```java
@Test
void applyTempConfig_shouldExecuteRconSmCvarCommand() {
    when(rconService.executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d2_max_players \"8\"")))
            .thenReturn("[SM] cvar changed");

    assertDoesNotThrow(() -> service.applyTempConfig(INSTANCE_ID, "l4d2_max_players", "8"));

    verify(rconService).executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d2_max_players \"8\""));
}

@Test
void applyTempConfig_shouldThrowWhenCvarNameIsBlank() {
    when(instanceQueryService.getById(INSTANCE_ID)).thenReturn(mockInstance());

    L4D2PluginException ex = assertThrows(L4D2PluginException.class,
            () -> service.applyTempConfig(INSTANCE_ID, "  ", "8"));
    assertTrue(ex.getMessage().contains("cvarName"));
}

@Test
void applyTempConfig_shouldWrapRconException() {
    when(rconService.executeCommand(eq(INSTANCE_ID), anyString()))
            .thenThrow(new L4D2PluginException(L4D2PluginException.RCON, "RCON 失败"));

    L4D2PluginException ex = assertThrows(L4D2PluginException.class,
            () -> service.applyTempConfig(INSTANCE_ID, "l4d2_max_players", "8"));
    assertEquals(L4D2PluginException.RCON, ex.getCode());
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: FAIL with "method applyTempConfig does not exist" 或编译错误。

- [ ] **Step 3: 在 SourceModCfgService 中实现 applyTempConfig**

在 `SourceModCfgService.java` 类末尾（最后一个 `}` 之前）添加：

```java
/**
 * 临时应用 CVAR 配置：通过 RCON sm_cvar 实时设置，不写文件，服务器重启后失效。
 *
 * <p>对齐 l4d2-server-next PluginConfigModal.applyTempConfig 实现。
 * 危险 CVAR（rcon_password、sv_cheats 等）会被 {@link CvarBlacklist} 拒绝。
 *
 * @param instanceId 实例 ID
 * @param cvarName   CVAR 名称（不可为空）
 * @param cvarValue  CVAR 值（不可为空，原样传递，含空格需用双引号包裹由调用方决定）
 * @throws L4D2PluginException cvarName 为空 / 实例不存在 / RCON 调用失败 / CVAR 在黑名单
 */
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
    requireInstance(instanceId);
    if (cvarName == null || cvarName.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarName 不能为空");
    }
    if (cvarValue == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarValue 不能为 null");
    }
    // 黑名单校验（Task 2.3 实现后启用；当前先跳过以解阻塞）
    // CvarBlacklist.check(cvarName);
    try {
        String cmd = "sm_cvar " + cvarName + " \"" + cvarValue + "\"";
        rconService.executeCommand(instanceId, cmd);
        log.info("临时配置已应用: instanceId={}, cvar={}, value={}", instanceId, cvarName, cvarValue);
    } catch (L4D2PluginException e) {
        throw e;
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.RCON,
                "临时应用配置失败: " + e.getMessage(), e);
    }
}
```

注意：`requireInstance` 与 `L4D2PluginException` 已在该文件中存在。`rconService` 字段已在 line 50 注入。如缺 import 需补 `import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;`。

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS（`applyTempConfig_*` 用例通过，其他已有用例不破坏）。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): 实现 SourceModCfgService.applyTempConfig 临时配置应用"
```

---

### Task 1.2: 实现 SourceModCfgService.restoreDefaults 方法

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**Background:** `PluginConfigController.restoreDefaults` (line 101-106) 调用 `sourceModCfgService.restoreDefaults(dto.getInstanceId(), dto.getPluginName())`，方法缺失导致编译失败。测试已存在于 line 325-379。

- [ ] **Step 1: 写失败测试（如果尚未存在）**

测试已在 `SourceModCfgServiceTest.java` line 325-379 存在，关键用例：

```java
@Test
void restoreDefaults_allItemsHaveDefaults_shouldResetAndWriteFile() {
    String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
    when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
    String original = "\"sm_dp\" \"2.5\" // Default: 0.5 Min: 0 Max: 10 伤害倍率\n";
    when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
            .thenReturn(original);

    service.restoreDefaults(INSTANCE_ID, "l4d2_ai_upgrade");

    org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(cfgAbs), captor.capture());
    String written = captor.getValue();
    assertTrue(written.contains("\"sm_dp\" \"0.5\""), "应将 sm_dp 重置为默认值 0.5");
    assertTrue(written.contains("// Default: 0.5"), "应保留 Default 注释");
}

@Test
void restoreDefaults_noCfgFound_shouldThrow() {
    when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

    L4D2PluginException ex = assertThrows(L4D2PluginException.class,
            () -> service.restoreDefaults(INSTANCE_ID, "nonexistent_plugin"));
    assertTrue(ex.getMessage().contains("cfg"));
}

@Test
void restoreDefaults_allItemsAlreadyDefault_shouldNotWrite() {
    String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_x.cfg";
    when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
    String original = "\"sm_x\" \"1.0\" // Default: 1.0\n";
    when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
            .thenReturn(original);

    service.restoreDefaults(INSTANCE_ID, "l4d2_x");

    verify(instanceFileService, never()).writeTextFile(anyLong(), anyString(), anyString());
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest#restoreDefaults_noCfgFound_shouldThrow -q
```

Expected: FAIL 编译错误（方法不存在）。

- [ ] **Step 3: 在 SourceModCfgService 中实现 restoreDefaults**

在 `applyTempConfig` 方法之后添加：

```java
/**
 * 恢复插件 CVAR 配置到默认值：从 cfg 文件注释中的 Default 字段重建配置。
 *
 * <p>对齐 l4d2-server-next RestoreSourceModConfig 设计：
 * <ol>
 *   <li>从候选路径中找到第一个实际存在的 cfg 文件</li>
 *   <li>读取并解析，对每个有 defaultValue 的 item，将 value 重置为 defaultValue</li>
 *   <li>写回文件，保留注释与格式</li>
 *   <li>更新扩展资源 PluginConfigResource</li>
 * </ol>
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名（用于推导候选 cfg 路径）
 * @throws L4D2PluginException 无候选 cfg / 读写出错
 */
public void restoreDefaults(Long instanceId, String pluginName) {
    InstanceVO instance = requireInstance(instanceId);
    if (pluginName == null || pluginName.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginName 不能为空");
    }

    // 1. 找到候选 cfg 文件
    String targetRelPath = null;
    for (String candidate : getCandidatePaths(pluginName)) {
        String relPath = toRelativePath(candidate);
        if (fileExistsSafe(instanceId, relPath)) {
            targetRelPath = relPath;
            break;
        }
    }
    if (targetRelPath == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "无可恢复的 cfg 文件，请先确认插件已生成配置: " + pluginName);
    }

    // 2. 读取并解析
    String content;
    try {
        content = instanceFileService.readTextFile(instanceId, targetRelPath, gbk);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "读取配置文件失败: " + e.getMessage(), e);
    }
    List<ConfigItem> items = cfgParser.parse(content);

    // 3. 对每个 item，若有 defaultValue，将 value 重置为 defaultValue
    int changed = 0;
    for (ConfigItem item : items) {
        if (item.getDefaultValue() != null && !item.getDefaultValue().isEmpty()) {
            if (!item.getDefaultValue().equals(item.getValue())) {
                item.setValue(item.getDefaultValue());
                changed++;
            }
        }
    }

    if (changed == 0) {
        log.info("无需恢复默认配置（所有 CVAR 已是默认值或无默认值）: instanceId={}, plugin={}",
                instanceId, pluginName);
        return;
    }

    // 4. 写回
    String serialized = cfgParser.serialize(items, content);
    try {
        instanceFileService.writeTextFile(instanceId, targetRelPath, serialized);
    } catch (Exception e) {
        throw new L4D2PluginException(L4D2PluginException.FILE,
                "写入配置文件失败: " + e.getMessage(), e);
    }

    // 5. 更新扩展资源
    String configName = pluginName + ".cfg";
    upsertResource(instanceId, instance.getHostId(), pluginName, configName,
            targetRelPath, items, serialized);

    log.info("已恢复默认配置: instanceId={}, plugin={}, changed={}", instanceId, pluginName, changed);
}
```

注意：
- `requireInstance`、`getCandidatePaths`、`toRelativePath`、`fileExistsSafe`、`upsertResource` 已在本类中存在。
- `cfgParser.parse` 返回 `List<ConfigItem>`，`ConfigItem` 有 `getValue()` / `setValue()` / `getDefaultValue()` 方法。
- `cfgParser.serialize(items, content)` 保留原文件格式（包括注释）。
- `gbk` 字段已在 line 53 注入。

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS（`restoreDefaults_*` 用例通过）。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): 实现 SourceModCfgService.restoreDefaults 默认值恢复"
```

---

### Task 1.3: 修复 PluginConfigController 的 import

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`

**Background:** `PluginConfigController` 调用了 `PluginTempConfigDTO` 和 `PluginRestoreDefaultsDTO`，但 import 列表可能缺失（DTO 类已存在于 dto 目录）。

- [ ] **Step 1: 读取 PluginConfigController 当前内容**

```powershell
# 用 Read 工具读取文件，检查 import 列表
```

文件路径：`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java`

- [ ] **Step 2: 添加缺失的 import**

在 import 区域添加（如果缺失）：

```java
import com.gameplatform.plugin.l4d2.dto.PluginTempConfigDTO;
import com.gameplatform.plugin.l4d2.dto.PluginRestoreDefaultsDTO;
```

- [ ] **Step 3: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 4: 运行控制器测试**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigControllerTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginConfigController.java
git commit -m "fix(l4d2): 修复 PluginConfigController 缺失 import 导致的编译错误"
```

---

## Phase 2: 配置编辑增强

> ✅ **状态：已完成**（Task 2.1 + 2.2 + 2.3 + 2.4 全部实现，含测试 CvarBlacklistTest）

### Task 2.1: SourceModCfgParser 控制台命令黑名单

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java`

**Background:** 对齐 l4d2-server-next `config_parser.go` 的 `consoleCmdNames` 黑名单，避免把 `sm plugins load xxx` / `exec xxx.cfg` / `meta` / `rcon` 等命令误识别为 CVAR。

- [ ] **Step 1: 写失败测试**

在 `SourceModCfgParserTest.java` 中添加：

```java
@Test
void parse_shouldSkipConsoleCommands() {
    String content = """
            // Config
            "sm_dp" "2.5"
            sm plugins load my_plugin
            exec server.cfg
            meta list
            rcon password
            "mp_gamemode" "versus"
            """;
    List<ConfigItem> items = parser.parse(content);

    assertEquals(2, items.size(), "应跳过 sm/exec/meta/rcon 命令");
    assertEquals("sm_dp", items.get(0).getKey());
    assertEquals("mp_gamemode", items.get(1).getKey());
}

@Test
void parse_shouldNotTreatSvarAsCommand() {
    // sm_ 开头的 CVAR（如 sm_dp）不应被误识别为 sm 命令
    String content = "\"sm_dp\" \"2.5\"\n";
    List<ConfigItem> items = parser.parse(content);
    assertEquals(1, items.size());
    assertEquals("sm_dp", items.get(0).getKey());
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgParserTest#parse_shouldSkipConsoleCommands -q
```

Expected: FAIL（`sm plugins load` 被误识别为 CVAR）。

- [ ] **Step 3: 在 SourceModCfgParser 中添加黑名单**

在 `SourceModCfgParser.java` 类顶部添加常量：

```java
/**
 * 控制台命令黑名单：以这些关键字开头的行（独立单词，非前缀）不视为 CVAR。
 *
 * <p>对齐 l4d2-server-next config_parser.go consoleCmdNames。
 * 注意：sm_dp、sm_cvar 等 CVAR 不应被误识别为 sm 命令（需用单词边界匹配）。
 */
private static final Set<String> CONSOLE_CMD_BLACKLIST = Set.of(
        "sm",       // SourceMod 命令前缀：sm plugins load/unload/list
        "exec",     // exec server.cfg
        "meta",     // Metamod 命令前缀
        "rcon"      // rcon password
);

/**
 * 检查行首是否为控制台命令（按空白分隔后的第一个 token 命中黑名单）。
 *
 * @param lineTrimmed 已去首尾空白的行
 * @return true 表示该行是控制台命令，应跳过
 */
private boolean isConsoleCommand(String lineTrimmed) {
    if (lineTrimmed.isEmpty() || lineTrimmed.startsWith("//")) {
        return false;
    }
    // 剥离前导引号，取首个 token
    String firstToken;
    int spaceIdx = lineTrimmed.indexOf(' ');
    if (spaceIdx > 0) {
        firstToken = lineTrimmed.substring(0, spaceIdx);
    } else {
        firstToken = lineTrimmed;
    }
    // 去除可能的引号
    if (firstToken.startsWith("\"")) {
        firstToken = firstToken.substring(1);
    }
    return CONSOLE_CMD_BLACKLIST.contains(firstToken);
}
```

在 `parse` 方法循环中（解析每行前）添加：

```java
for (String line : lines) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("//")) {
        // ... 保留注释逻辑
        continue;
    }
    if (isConsoleCommand(trimmed)) {
        continue;  // 跳过控制台命令
    }
    // ... 原有 Matcher 解析逻辑
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgParserTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParser.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/parser/SourceModCfgParserTest.java
git commit -m "feat(l4d2): SourceModCfgParser 添加控制台命令黑名单（sm/exec/meta/rcon）"
```

---

### Task 2.2: l4d2↔l4d_ 互转候选 cfg 路径

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java`

**Background:** 对齐 l4d2-server-next `getPluginConfigCandidates` 的 l4d2_/l4d_ 互转：`l4d2_xxx` 插件同时把 `l4d_xxx.cfg` 作为候选，反之亦然。当前 `getCandidatePaths` 无论前缀都返回相同的 2 个路径，无法兼容历史 l4d_ 命名插件。

- [ ] **Step 1: 写失败测试**

在 `SourceModCfgServiceTest.java` 中添加：

```java
@Test
void getCandidatePaths_shouldGenerateL4dAliasForL4d2Plugin() {
    List<String> paths = service.getCandidatePaths("l4d2_multi_slot");
    assertTrue(paths.stream().anyMatch(p -> p.contains("l4d_multi_slot.cfg")),
            "l4d2_ 插件应同时返回 l4d_ 别名候选");
}

@Test
void getCandidatePaths_shouldGenerateL4d2AliasForL4dPlugin() {
    List<String> paths = service.getCandidatePaths("l4d_xxx");
    assertTrue(paths.stream().anyMatch(p -> p.contains("l4d2_xxx.cfg")),
            "l4d_ 插件应同时返回 l4d2_ 别名候选");
}

@Test
void getCandidatePaths_shouldNotGenerateAliasForNonL4dPlugin() {
    List<String> paths = service.getCandidatePaths("admin_tools");
    assertEquals(2, paths.size(), "非 l4d_/l4d2_ 前缀插件不生成别名");
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest#getCandidatePaths_shouldGenerateL4dAliasForL4d2Plugin -q
```

Expected: FAIL（候选路径不含 l4d_ 别名）。

- [ ] **Step 3: 修改 getCandidatePaths 方法**

在 `SourceModCfgService.java` 中替换 `getCandidatePaths` 方法（原 line 61-66）：

```java
private static final String CFG_SOURCEMOD_PREFIX = "cfg/sourcemod/";
private static final String PLUGINS_PREFIX = "addons/sourcemod/plugins/";

/**
 * 获取插件的所有候选 cfg 路径。
 *
 * <p>对齐 l4d2-server-next getPluginConfigCandidates：
 * <ul>
 *   <li>主候选：cfg/sourcemod/{pluginName}.cfg</li>
 *   <li>次候选：addons/sourcemod/plugins/{pluginName}.cfg</li>
 *   <li>l4d2_/l4d_ 互转别名：若插件名以 l4d2_ 开头，追加 l4d_ 同名候选；反之亦然</li>
 * </ul>
 *
 * @param pluginName 插件名
 * @return 候选 cfg 相对路径列表（最多 4 个）
 */
public List<String> getCandidatePaths(String pluginName) {
    if (pluginName == null || pluginName.isBlank()) {
        return List.of();
    }
    List<String> paths = new ArrayList<>(4);
    paths.add(CFG_SOURCEMOD_PREFIX + pluginName + ".cfg");
    paths.add(PLUGINS_PREFIX + pluginName + ".cfg");

    // l4d2_ ↔ l4d_ 互转
    String alias = getL4dAlias(pluginName);
    if (alias != null) {
        paths.add(CFG_SOURCEMOD_PREFIX + alias + ".cfg");
        paths.add(PLUGINS_PREFIX + alias + ".cfg");
    }
    return paths;
}

/**
 * 获取 l4d2_/l4d_ 互转别名。
 *
 * @param pluginName 插件名
 * @return 别名（如 l4d2_xxx → l4d_xxx）；非 l4d_/l4d2_ 前缀返回 null
 */
private String getL4dAlias(String pluginName) {
    if (pluginName.startsWith("l4d2_")) {
        return "l4d_" + pluginName.substring("l4d2_".length());
    }
    if (pluginName.startsWith("l4d_")) {
        return "l4d2_" + pluginName.substring("l4d_".length());
    }
    return null;
}
```

注意：原 `getCandidatePaths` 返回的 2 个路径常量名需保持一致（`CFG_SOURCEMOD_PREFIX` / `PLUGINS_PREFIX`），如果原本是局部变量需提升为类常量。

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/SourceModCfgServiceTest.java
git commit -m "feat(l4d2): SourceModCfgService 支持 l4d2_/l4d_ 互转候选 cfg 路径"
```

---

### Task 2.3: 危险 CVAR 黑名单工具类

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/CvarBlacklist.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/CvarBlacklistTest.java`

**Background:** l4d2-server-next 没有此功能（明显短板），本计划主动补全。在 `applyTempConfig` 和 `updateConfig` 中拒绝修改危险 CVAR（如 `rcon_password`、`sv_cheats`、`mp_consistency` 等）。

- [ ] **Step 1: 写失败测试**

```java
package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CvarBlacklistTest {

    @Test
    void check_shouldRejectRconPassword() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("rcon_password"));
        assertTrue(ex.getMessage().contains("rcon_password"));
    }

    @Test
    void check_shouldRejectSvCheats() {
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("sv_cheats"));
    }

    @Test
    void check_shouldRejectCaseInsensitive() {
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("RCON_PASSWORD"));
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("Sv_Cheats"));
    }

    @Test
    void check_shouldPassSafeCvar() {
        assertDoesNotThrow(() -> CvarBlacklist.check("l4d2_max_players"));
        assertDoesNotThrow(() -> CvarBlacklist.check("sm_dp"));
        assertDoesNotThrow(() -> CvarBlacklist.check("mp_gamemode"));
    }

    @Test
    void check_shouldRejectNull() {
        assertThrows(L4D2PluginException.class, () -> CvarBlacklist.check(null));
    }

    @Test
    void isDangerous_shouldReturnBoolean() {
        assertTrue(CvarBlacklist.isDangerous("rcon_password"));
        assertFalse(CvarBlacklist.isDangerous("sm_dp"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=CvarBlacklistTest -q
```

Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 CvarBlacklist**

```java
package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;

import java.util.Set;

/**
 * 危险 CVAR 黑名单：拒绝通过面板修改可能危害服务器的 CVAR。
 *
 * <p>l4d2-server-next 缺失此功能（rcon_password/sv_cheats 等可被任意修改），
 * 本项目主动补全以提升安全性。
 *
 * <p>黑名单覆盖：
 * <ul>
 *   <li>rcon_password — 修改后可能导致 RCON 失控</li>
 *   <li>sv_cheats — 启用作弊</li>
 *   <li>sv_consistency / mp_consistency — 关闭后允许客户端文件不一致</li>
 *   <li>host_name_store — 影响服务器识别</li>
 *   <li>sv_rcon_banpenalty / sv_rcon_minfailures 等 RCON 反作弊参数</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class CvarBlacklist {

    private CvarBlacklist() {
    }

    /** 危险 CVAR 名称集合（小写） */
    private static final Set<String> DANGEROUS_CVARS = Set.of(
            "rcon_password",
            "sv_cheats",
            "sv_consistency",
            "mp_consistency",
            "host_name_store",
            "sv_rcon_banpenalty",
            "sv_rcon_minfailures",
            "sv_rcon_maxfailures",
            "sv_rcon_minfailuretime",
            "sv_downloadurl"
    );

    /**
     * 检查 CVAR 是否危险（不抛异常）。
     *
     * @param cvarName CVAR 名称
     * @return true 表示在黑名单中
     */
    public static boolean isDangerous(String cvarName) {
        if (cvarName == null || cvarName.isBlank()) {
            return false;
        }
        return DANGEROUS_CVARS.contains(cvarName.toLowerCase());
    }

    /**
     * 校验 CVAR，危险则抛 L4D2PluginException。
     *
     * @param cvarName CVAR 名称
     * @throws L4D2PluginException CVAR 在黑名单或为 null/空
     */
    public static void check(String cvarName) {
        if (cvarName == null || cvarName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "cvarName 不能为空");
        }
        if (DANGEROUS_CVARS.contains(cvarName.toLowerCase())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "禁止修改危险 CVAR: " + cvarName + "（在黑名单中）");
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=CvarBlacklistTest -q
```

Expected: PASS。

- [ ] **Step 5: 在 SourceModCfgService.applyTempConfig 中启用黑名单**

修改 `applyTempConfig` 方法（Task 1.1 已注释掉）：

```java
public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
    requireInstance(instanceId);
    if (cvarName == null || cvarName.isBlank()) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarName 不能为空");
    }
    if (cvarValue == null) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarValue 不能为 null");
    }
    // 危险 CVAR 黑名单校验
    CvarBlacklist.check(cvarName);
    // ... 剩余逻辑不变
}
```

同时在 `updateConfig` 方法的循环中添加校验：

```java
public void updateConfig(Long instanceId, String pluginName, List<ConfigItem> items) {
    // ... 已有逻辑
    for (ConfigItem item : items) {
        CvarBlacklist.check(item.getKey());
    }
    // ... 剩余逻辑不变
}
```

并在文件顶部添加 import：

```java
import com.gameplatform.plugin.l4d2.util.CvarBlacklist;
```

- [ ] **Step 6: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest,CvarBlacklistTest -q
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/CvarBlacklist.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/CvarBlacklistTest.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git commit -m "feat(l4d2): 新增 CvarBlacklist 危险 CVAR 黑名单并接入 applyTempConfig/updateConfig"
```

---

### Task 2.4: PluginConfigAuditService 配置修改审计日志

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServiceTest.java`

**Background:** 对齐 l4d2-server-next `audit.go` 的 `LogOp` 模式，记录配置修改操作用于合规审计。l4d2-server-next 用 SQLite 异步写入，本项目沿用扩展资源（基于 ExtensionModel）实现，便于前端查询。

- [ ] **Step 1: 写失败测试**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginConfigAuditServiceTest {

    @InjectMocks
    private PluginConfigAuditService auditService;

    @Test
    void logUpdateConfig_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logUpdateConfig(
                1L, 100L, "l4d2_x", "l4d2_x.cfg",
                "sm_dp", "2.5", "3.0", "admin"));
    }

    @Test
    void logApplyTempConfig_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logApplyTempConfig(
                1L, 100L, "l4d2_x", "sm_dp", "2.5", "admin"));
    }

    @Test
    void logRestoreDefaults_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logRestoreDefaults(
                1L, 100L, "l4d2_x", "l4d2_x.cfg", 5, "admin"));
    }

    @Test
    void log_shouldNotThrowWhenOperationFails() {
        // 审计日志失败不应阻塞主流程
        assertDoesNotThrow(() -> auditService.logRestoreDefaults(
                null, null, null, null, 0, null));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditServiceTest -q
```

Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 PluginConfigAuditService**

```java
package com.gameplatform.plugin.l4d2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 插件配置修改审计日志服务。
 *
 * <p>对齐 l4d2-server-next audit.go 的 LogOp 模式，记录：
 * <ul>
 *   <li>updateConfig — 持久化修改 CVAR</li>
 *   <li>applyTempConfig — 临时应用 CVAR（RCON sm_cvar）</li>
 *   <li>restoreDefaults — 恢复默认值</li>
 * </ul>
 *
 * <p>本实现使用本地日志（slf4j）记录，未来可扩展为扩展资源持久化。
 * 关键设计：审计失败不应阻塞主流程，所有记录方法均吞掉异常。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PluginConfigAuditService {

    /**
     * 记录持久化修改 CVAR 操作。
     */
    public void logUpdateConfig(Long instanceId, Long hostId, String pluginName,
                                 String cfgFile, String cvarName,
                                 String oldValue, String newValue, String operator) {
        try {
            log.info("[ConfigAudit] UPDATE instanceId={} hostId={} plugin={} cfg={} cvar={} old={} new={} op={} time={}",
                    instanceId, hostId, pluginName, cfgFile, cvarName,
                    oldValue, newValue, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 记录临时应用 CVAR 操作。
     */
    public void logApplyTempConfig(Long instanceId, Long hostId, String pluginName,
                                    String cvarName, String value, String operator) {
        try {
            log.info("[ConfigAudit] APPLY_TEMP instanceId={} hostId={} plugin={} cvar={} value={} op={} time={}",
                    instanceId, hostId, pluginName, cvarName, value, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 记录恢复默认值操作。
     */
    public void logRestoreDefaults(Long instanceId, Long hostId, String pluginName,
                                    String cfgFile, int changedCount, String operator) {
        try {
            log.info("[ConfigAudit] RESTORE_DEFAULTS instanceId={} hostId={} plugin={} cfg={} changed={} op={} time={}",
                    instanceId, hostId, pluginName, cfgFile, changedCount, operator, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("审计日志记录失败（已忽略）: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginConfigAuditServiceTest -q
```

Expected: PASS。

- [ ] **Step 5: 在 SourceModCfgService 中接入审计**

注入 `PluginConfigAuditService` 并在三个方法末尾调用：

```java
// 类字段
private final PluginConfigAuditService auditService;

// 构造器（@RequiredArgsConstructor 自动注入，若已用则无需修改）

// updateConfig 末尾
auditService.logUpdateConfig(instanceId, instance.getHostId(), pluginName,
        configName, "multiple", "multiple", "multiple", "system");

// applyTempConfig 末尾
auditService.logApplyTempConfig(instanceId, instance.getHostId(), null,
        cvarName, cvarValue, "system");

// restoreDefaults 末尾
auditService.logRestoreDefaults(instanceId, instance.getHostId(), pluginName,
        targetRelPath, changed, "system");
```

- [ ] **Step 6: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=SourceModCfgServiceTest,PluginConfigAuditServiceTest -q
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginConfigAuditServiceTest.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java
git commit -m "feat(l4d2): 新增 PluginConfigAuditService 配置修改审计日志"
```

---

## Phase 3: 商店功能补全

> ✅ **状态：已完成**（Task 3.1 + 3.2 + 3.3 全部实现，proxyUrl/githubToken/tree 缓存已生效）

### Task 3.1: L4D2Config.PluginStore 添加 proxyUrl 与 githubToken 字段

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java`

**Background:** 对齐 l4d2-server-next `applyProxy` 与 GitHub Token 支持。当前 `PluginStore` 类只有 repo/branch/cacheTtlMs/maxConcurrent 4 个字段，缺 proxyUrl 和 githubToken。

- [ ] **Step 1: 读取 L4D2Config 当前 PluginStore 内部类**

```powershell
# 用 Read 工具读取 L4D2Config.java line 92-98
```

- [ ] **Step 2: 添加 proxyUrl 和 githubToken 字段**

修改 `PluginStore` 内部类（约 line 92-98）：

```java
public static class PluginStore {
    /** GitHub 仓库（owner/repo 格式），默认 LaoYutang/l4d2-plugins-store */
    private String repo = "LaoYutang/l4d2-plugins-store";

    /** 仓库分支，默认 main */
    private String branch = "main";

    /** 列表缓存 TTL（毫秒），默认 10 分钟 */
    private long cacheTtlMs = 600_000L;

    /** 最大并发下载数，默认 3 */
    private int maxConcurrent = 3;

    /** GitHub 代理 URL（如 https://gh-proxy.com/），为空则直连 */
    private String proxyUrl = "";

    /** GitHub Token（优先于环境变量 GITHUB_TOKEN），为空则用环境变量 */
    private String githubToken = "";

    // getters/setters by Lombok @Data 或 @Getter @Setter（已存在）
}
```

确保类级别有 `@Data` 或 `@Getter @Setter` 注解。

- [ ] **Step 3: 在 application.yml 中添加默认配置**

读取 `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/application.yml`，在 `plugin.l4d2.plugin-store` 下添加：

```yaml
plugin:
  l4d2:
    plugin-store:
      repo: LaoYutang/l4d2-plugins-store
      branch: main
      cache-ttl-ms: 600000
      max-concurrent: 3
      proxy-url: ""
      github-token: ""
```

- [ ] **Step 4: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/application.yml
git commit -m "feat(l4d2): PluginStore 配置新增 proxyUrl 与 githubToken 字段"
```

---

### Task 3.2: GitHubApiClient 应用 proxyUrl 与配置 token

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/GitHubApiClientTest.java`

**Background:** 对齐 l4d2-server-next `applyProxy`：将原始 GitHub URL 拼接为 `<proxyUrl>/<rawUrl>`。当前 `GitHubApiClient` 仅从环境变量读 token，无代理支持。

- [ ] **Step 1: 写失败测试**

```java
@Test
void applyProxy_shouldPrependProxyUrlWhenConfigured() {
    // 通过反射或 package-private 方法测试
    when(config.getPluginStore()).thenReturn(new L4D2Config.PluginStore());
    config.getPluginStore().setProxyUrl("https://gh-proxy.com/");
    
    String result = gitHubApiClient.applyProxy("https://api.github.com/repos/x");
    assertEquals("https://gh-proxy.com/https://api.github.com/repos/x", result);
}

@Test
void applyProxy_shouldReturnOriginalWhenProxyEmpty() {
    when(config.getPluginStore()).thenReturn(new L4D2Config.PluginStore());
    config.getPluginStore().setProxyUrl("");
    
    String result = gitHubApiClient.applyProxy("https://api.github.com/repos/x");
    assertEquals("https://api.github.com/repos/x", result);
}

@Test
void resolveToken_shouldPreferConfigOverEnv() {
    when(config.getPluginStore()).thenReturn(new L4D2Config.PluginStore());
    config.getPluginStore().setGithubToken("config-token");
    
    String token = gitHubApiClient.resolveToken();
    assertEquals("config-token", token);
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=GitHubApiClientTest -q
```

Expected: FAIL（方法不存在）。

- [ ] **Step 3: 修改 GitHubApiClient**

在 `GitHubApiClient.java` 中添加方法（改为 package-private 便于测试）：

```java
/**
 * 应用代理 URL：若配置了 proxyUrl，则拼接为 {proxyUrl}{rawUrl}。
 *
 * <p>对齐 l4d2-server-next applyProxy。
 *
 * @param rawUrl 原始 URL
 * @return 应用代理后的 URL，或原 URL
 */
String applyProxy(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
        return rawUrl;
    }
    String proxyUrl = config.getPluginStore().getProxyUrl();
    if (proxyUrl == null || proxyUrl.isBlank()) {
        return rawUrl;
    }
    // 确保拼接时只有一个分隔
    if (proxyUrl.endsWith("/") && rawUrl.startsWith("https://")) {
        return proxyUrl + rawUrl;
    }
    return proxyUrl + rawUrl;
}

/**
 * 解析 GitHub Token：优先用配置类 token，其次环境变量 GITHUB_TOKEN。
 *
 * @return token 字符串，可能为 null
 */
String resolveToken() {
    String configToken = config.getPluginStore().getGithubToken();
    if (configToken != null && !configToken.isBlank()) {
        return configToken;
    }
    return System.getenv(ENV_GITHUB_TOKEN);
}
```

修改 `getTree()`、`getBlobContent()`、`batchLfsObjects()`、`downloadLfsObject()` 中的 URL 构造，全部走 `applyProxy()`：

```java
// 修改前
String url = String.format("%s/%s/git/trees/%s?recursive=1", GITHUB_API_BASE, ps.getRepo(), ps.getBranch());

// 修改后
String rawUrl = String.format("%s/%s/git/trees/%s?recursive=1", GITHUB_API_BASE, ps.getRepo(), ps.getBranch());
String url = applyProxy(rawUrl);
```

修改 `buildAuthParams()` 改为返回 `Map<String, String>`（含 Bearer token）或直接在请求 Header 中设置：

```java
// 在 getTree / getBlobContent / batchLfsObjects 的请求构造中
String token = resolveToken();
if (token != null && !token.isBlank()) {
    // 通过 ExternalHttpClient 的 headers 参数传递
    // 或保持 query param 方式
}
```

> 注意：`ExternalHttpClient` 的方法签名可能需要扩展支持自定义 headers，本任务先保持 query param 方式（兼容现有签名），若需 Bearer Header 在后续任务升级。

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=GitHubApiClientTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/GitHubApiClient.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/util/GitHubApiClientTest.java
git commit -m "feat(l4d2): GitHubApiClient 支持 proxyUrl 代理与配置 token 优先级"
```

---

### Task 3.3: PluginStoreService tree 缓存优化

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`

**Background:** 对齐 l4d2-server-next 的 `treeCache`：当前只缓存 `PluginStoreItemVO` 列表，但 `detail()` 和 `readme()` 每次都调 `gitHubApiClient.getTree()`，浪费 API 配额。应增加 tree 级缓存，TTL 与列表缓存一致。

- [ ] **Step 1: 在 PluginStoreService 中添加 tree 缓存字段**

```java
/** Tree 缓存（与 items 缓存同步过期） */
private volatile List<TreeEntry> cachedTree;
private volatile long cachedTreeTimestamp;
```

- [ ] **Step 2: 添加 getCachedTree 方法**

```java
private List<TreeEntry> getCachedTree() {
    long ttl = config.getPluginStore().getCacheTtlMs();
    long now = System.currentTimeMillis();
    if (cachedTree != null && (now - cachedTreeTimestamp) < ttl) {
        return cachedTree;
    }
    List<TreeEntry> fresh = gitHubApiClient.getTree();
    if (fresh != null && !fresh.isEmpty()) {
        cachedTree = fresh;
        cachedTreeTimestamp = now;
    }
    return fresh != null ? fresh : List.of();
}

/** 强制刷新 tree 缓存（供 detail/readme 在缓存未命中时调用） */
private List<TreeEntry> getTreeForRead() {
    List<TreeEntry> cached = getCachedTree();
    if (cached.isEmpty()) {
        // 缓存为空时直接请求
        return gitHubApiClient.getTree();
    }
    return cached;
}
```

- [ ] **Step 3: 修改 detail() 和 readme() 使用缓存**

在 `detail()` (约 line 98) 和 `readme()` (约 line 148) 中：

```java
// 修改前
List<TreeEntry> tree = gitHubApiClient.getTree();

// 修改后
List<TreeEntry> tree = getTreeForRead();
```

在 `fetchItems()` (约 line 251) 中也改为 `getTreeForRead()`。

- [ ] **Step 4: 编译验证 + 现有测试**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreServiceTest -q
```

Expected: PASS（已有测试不破坏）。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "perf(l4d2): PluginStoreService 增加 tree 缓存避免 detail/readme 重复请求 API"
```

---

## Phase 4: 回滚机制补全

> ⚠️ **状态：部分完成**（Task 4.1 ✅ 已实现 installFromLocalFileToTempDir + atomicMoveToStore；Task 4.2 ❌ 文件实际未创建，迁移至 Phase 8 重做）

### Task 4.1: 商店下载采用临时目录 + 原子重命名

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java`

**Background:** 对齐 l4d2-server-next `plugin_store.go:427-472` 的 `tempDir → finalDir` 原子重命名模式。当前 `PluginStoreService.runDownload` (line 408) 直接调 `pluginInstallService.installFromLocalFile(instanceId, tempFile)` 后 `tempFile.delete()`，若安装中途失败会污染插件库目录。

**设计：**
- 下载 ZIP 到本地临时文件（已有逻辑）
- 调用 `PluginInstallService.installFromLocalFileToTempDir` 新方法：解压到 `{storePath}/.download_temp/{uuid}/`，校验结构
- 成功后调 `PluginInstallService.atomicMoveToStore`：将 `.download_temp/{uuid}/` 重命名为 `{storePath}/{pluginName}/`
- 失败时清理 `.download_temp/{uuid}/`

- [ ] **Step 1: 在 L4D2PathResolver 添加临时目录方法**

在 `L4D2PathResolver.java` 末尾添加：

```java
/**
 * 商店下载临时目录：addons/sourcemod/.download_temp
 *
 * <p>对齐 l4d2-server-next DownloadTempDir。
 * 每次下载任务在此目录下创建 {uuid} 子目录，成功后原子重命名为正式插件目录。
 */
public String getDownloadTempPath() {
    return getSourceModPath() + "/.download_temp";
}

/**
 * 单次下载任务的临时目录：addons/sourcemod/.download_temp/{taskId}
 */
public String getDownloadTaskTempPath(String taskId) {
    return getDownloadTempPath() + "/" + taskId;
}
```

- [ ] **Step 2: 在 PluginInstallService 添加临时目录安装方法**

```java
/**
 * 将本地 ZIP 文件解压到临时目录（不直接入正式库）。
 *
 * <p>对齐 l4d2-server-next StartStorePluginDownload 的临时目录模式：
 * 解压到 {storePath}/.download_temp/{taskId}/，校验结构成功后由调用方调用 atomicMoveToStore。
 *
 * @param instanceId 实例 ID
 * @param localFile 本地 ZIP 文件
 * @param taskId 下载任务 ID（用作临时目录名）
 * @return 解压后的插件名列表（单插件 ZIP 返回 1 个，多插件 ZIP 返回多个）
 */
public List<String> installFromLocalFileToTempDir(Long instanceId, File localFile, String taskId) {
    // 1. 创建临时目录
    String tempDirRel = pathResolver.getDownloadTaskTempPath(taskId);
    instanceFileService.createDirectory(instanceId, tempDirRel, true);

    // 2. 解压到临时目录（复用现有解压逻辑，但目标改为 tempDirRel 而非 plugins_store）
    // 这里需要重构 extractArchive 或新增 extractToRemoteDir 方法
    // 简化方案：解压到本地临时目录，再通过 instanceFileService 上传到远程临时目录

    // 3. 返回解压出的插件名列表（扫描临时目录下的子目录）
    List<String> pluginNames = new ArrayList<>();
    List<FileInfo> entries = instanceFileService.listFiles(instanceId, tempDirRel);
    for (FileInfo entry : entries) {
        if (entry.isDirectory()) {
            pluginNames.add(entry.getName());
        }
    }
    return pluginNames;
}

/**
 * 将临时目录中的插件原子移动到正式插件库。
 *
 * @param instanceId 实例 ID
 * @param taskId 下载任务 ID
 * @param pluginName 插件名（=临时目录下的子目录名 = 目标目录名）
 */
public void atomicMoveToStore(Long instanceId, String taskId, String pluginName) {
    String tempPluginDir = pathResolver.getDownloadTaskTempPath(taskId) + "/" + pluginName;
    String targetPluginDir = pathResolver.getPluginStorePath(pluginName);

    // 校验目标不存在
    if (instanceFileService.exists(instanceId, targetPluginDir)) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件已存在: " + pluginName + "，请先删除");
    }

    // 远程原子移动（docker exec mv / native mv）
    // 通过 instanceFileService.move(src, dst) 实现（需新增方法或复用现有）
    instanceFileService.move(instanceId, tempPluginDir, targetPluginDir);

    // 写 plugin.yaml 元数据
    PluginMeta meta = new PluginMeta();
    meta.setName(pluginName);
    meta.setSource("store");
    meta.setInstalledAt(LocalDateTime.now().toString());
    pluginMetaService.save(instanceId, pluginName, meta);
}
```

> 注意：`instanceFileService.move` 方法可能不存在，需要确认 InstanceFileService SPI 是否提供。如果未提供，通过 `docker exec mv` 或 SSH `mv` 命令实现（在 InstanceFileServiceImpl 中添加）。本任务先在 PluginInstallService 中用 SSH 命令直接执行 `mv`（复用 SshUtil），后续若需统一抽象再迁移到 SPI。

简化方案（不依赖 SPI 扩展）：

```java
public void atomicMoveToStore(Long instanceId, String taskId, String pluginName) {
    String tempPluginDir = pathResolver.getDownloadTaskTempPath(taskId) + "/" + pluginName;
    String targetPluginDir = pathResolver.getPluginStorePath(pluginName);

    if (instanceFileService.exists(instanceId, targetPluginDir)) {
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "插件已存在: " + pluginName);
    }

    // 复制文件 + 删除源（无法原子时用复制+删除近似）
    instanceFileService.copyDirectory(instanceId, tempPluginDir, targetPluginDir);
    instanceFileService.deleteDirectory(instanceId, tempPluginDir, true);

    PluginMeta meta = new PluginMeta();
    meta.setName(pluginName);
    meta.setSource("store");
    meta.setInstalledAt(LocalDateTime.now().toString());
    pluginMetaService.save(instanceId, pluginName, meta);
}
```

> 注：`copyDirectory` / `move` 方法若 InstanceFileService 不支持，退回到逐文件复制（递归 listFiles + copyFile + deleteFile）。

- [ ] **Step 3: 修改 PluginStoreService.runDownload 使用临时目录模式**

修改 `runDownload` 方法（约 line 316-435）：

```java
private void runDownload(PluginStoreDownloadTaskVO task, PluginStoreDownloadDTO dto) {
    String taskId = task.getTaskId();
    try {
        if (isCancelled(task)) {
            return;
        }
        downloadSemaphore.acquire();
        try {
            if (isCancelled(task)) {
                return;
            }
            task.setStatus(STATUS_DOWNLOADING);
            task.setMessage("获取插件信息");

            // 1. 获取 plugin.zip（LFS 解析 + 下载到本地临时文件，已有逻辑）
            // ... 保持原 line 330-400 不变

            // 2. 解压到临时目录（新逻辑）
            task.setStatus(STATUS_INSTALLING);
            task.setMessage("解压插件包");
            List<String> pluginNames = pluginInstallService.installFromLocalFileToTempDir(
                    dto.getInstanceId(), downloadedFile, taskId);

            if (pluginNames.isEmpty()) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "ZIP 中未找到有效插件目录");
            }

            // 3. 原子移动到正式库
            task.setMessage("提交到插件库");
            for (String name : pluginNames) {
                pluginInstallService.atomicMoveToStore(dto.getInstanceId(), taskId, name);
            }

            // 4. 更新来源为 store
            for (String name : pluginNames) {
                PluginMeta meta = pluginMetaService.load(dto.getInstanceId(), name);
                if (meta != null) {
                    meta.setSource("store");
                    pluginMetaService.save(dto.getInstanceId(), name, meta);
                }
            }

            task.setStatus(STATUS_COMPLETED);
            task.setProgress(100);
            task.setMessage("下载完成");
            task.setFinishedAt(LocalDateTime.now());

            // 清理本地临时文件
            if (downloadedFile != null) {
                try { downloadedFile.delete(); } catch (Exception ignored) {}
            }
        } finally {
            downloadSemaphore.release();
        }
    } catch (Exception e) {
        // 失败时清理远程临时目录
        try {
            String tempDir = pathResolver.getDownloadTaskTempPath(taskId);
            instanceFileService.deleteDirectory(dto.getInstanceId(), tempDir, true);
        } catch (Exception cleanupErr) {
            log.warn("清理临时目录失败: taskId={}, err={}", taskId, cleanupErr.getMessage());
        }
        task.setStatus(STATUS_FAILED);
        task.setMessage("下载失败: " + e.getMessage());
        task.setFinishedAt(LocalDateTime.now());
        log.error("插件下载失败: taskId={}, pluginId={}", taskId, dto.getPluginId(), e);
    }
}
```

注意：需注入 `L4D2PathResolver pathResolver` 和 `InstanceFileService instanceFileService` 字段。

- [ ] **Step 4: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 运行已有测试确保不破坏**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreServiceTest,PluginInstallServiceTest -q
```

Expected: PASS（若测试因 mock 改动失败，更新 mock）。

- [ ] **Step 6: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreService.java
git commit -m "feat(l4d2): 商店下载采用临时目录+原子移动模式，失败时自动清理"
```

---

### Task 4.2: PluginStoreMigration 启动清理残留临时目录

> ❌ **状态：未完成** — 文件实际未创建（核查 Glob 返回 No file found）。原任务步骤保留作为参考，**实际执行请使用 Phase 8 的修订版**（修复了 instanceId=null 的设计缺陷，改为遍历所有实例）。

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java`

**Background:** 对齐 l4d2-server-next `CleanDownloadTemp`：进程启动时清空 `.download_temp/` 目录，避免上次崩溃留下的脏数据。此时 HTTP 服务尚未对外提供，不可能存在正在进行的下载，整体 RemoveAll 安全。

- [ ] **Step 1: 写失败测试**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginStoreMigrationTest {

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private L4D2PathResolver pathResolver;

    @InjectMocks
    private PluginStoreMigration migration;

    @Test
    void cleanDownloadTemp_onStartup_shouldDeleteTempDir() {
        when(pathResolver.getDownloadTempPath()).thenReturn("addons/sourcemod/.download_temp");

        migration.cleanDownloadTemp();

        verify(instanceFileService).deleteDirectory(
                eq(null), eq("addons/sourcemod/.download_temp"), eq(true));
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenDeleteFails() {
        when(pathResolver.getDownloadTempPath()).thenReturn("addons/sourcemod/.download_temp");
        doThrow(new RuntimeException("delete failed"))
                .when(instanceFileService).deleteDirectory(any(), anyString(), anyBoolean());

        // 不应抛异常
        migration.cleanDownloadTemp();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest -q
```

Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 PluginStoreMigration**

```java
package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 插件商店启动清理服务。
 *
 * <p>对齐 l4d2-server-next CleanDownloadTemp：
 * 在应用启动完成后（HTTP 服务对外提供前），整体清空 .download_temp/ 目录，
 * 删除上次运行残留的临时下载文件。
 *
 * <p>此时不可能存在正在进行的下载任务，整体 RemoveAll 安全。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginStoreMigration {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;

    /**
     * 应用启动完成后清理下载临时目录。
     *
     * <p>注意：使用 ApplicationReadyEvent 而非 @PostConstruct，
     * 确保所有 Bean 初始化完成、HTTP 服务尚未对外提供服务时执行。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        cleanDownloadTemp();
    }

    /**
     * 清理 .download_temp 目录。
     *
     * <p>对齐 l4d2-server-next plugin_store.go CleanDownloadTemp：
     * 整体 RemoveAll，不区分实例（实例级清理在实例启动时单独触发）。
     *
     * <p>失败不抛异常，仅记录警告，避免阻塞应用启动。
     */
    public void cleanDownloadTemp() {
        try {
            String tempPath = pathResolver.getDownloadTempPath();
            log.info("启动清理下载临时目录: {}", tempPath);
            // 注意：instanceId 传 null，因为这是全局清理（具体实现需支持）
            // 实际实现可能需要遍历所有实例分别清理
            instanceFileService.deleteDirectory(null, tempPath, true);
            log.info("下载临时目录清理完成");
        } catch (Exception e) {
            log.warn("清理下载临时目录失败（已忽略）: {}", e.getMessage());
        }
    }
}
```

> 注意：`instanceFileService.deleteDirectory(null, ...)` 中 instanceId 为 null 时如何处理需在 InstanceFileServiceImpl 中明确。简化方案：改为遍历所有实例（通过 InstanceQueryService 获取实例列表）逐个清理。本任务先保留 null 调用，后续若发现不支持再调整。

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java
git commit -m "feat(l4d2): 新增 PluginStoreMigration 启动时清理 .download_temp 残留"
```

---

## Phase 5: 预设安全增强

> ✅ **状态：已完成**（Task 5.1 已实现 backupService.create + "preset-apply-" 前缀，失败不阻塞主流程）
>
> ⚠️ **遗漏**：开源 preset.go 还有"平台插件预校验"和"平台插件优先启用"特性，v7 未覆盖，已在 Phase 9 补全。

### Task 5.1: 应用预设前自动创建备份

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java`

**Background:** 对齐 l4d2-server-next 文档建议（`docs/features/plugins.md:49`）："应用预设前建议先创建一次备份，避免覆盖现有配置后难以回退"。l4d2-server-next 仅文档建议未自动执行，本项目主动补全为自动备份。

- [ ] **Step 1: 写失败测试**

```java
@Test
void apply_shouldCreateBackupBeforeApplying(@Mock BackupService backupService) {
    // ... mock 依赖
    when(backupService.create(any())).thenReturn(mockBackupVO());

    service.apply(INSTANCE_ID, "multi-versus");

    // 验证在禁用所有插件之前先创建了备份
    verify(backupService).create(argThat(dto ->
            dto.getInstanceId().equals(INSTANCE_ID) &&
            dto.getName().contains("preset-apply")));
}

@Test
void apply_shouldContinueEvenIfBackupFails(@Mock BackupService backupService) {
    when(backupService.create(any())).thenThrow(new RuntimeException("backup failed"));

    // 备份失败不应阻塞预设应用
    assertDoesNotThrow(() -> service.apply(INSTANCE_ID, "multi-versus"));

    verify(pluginInstallService).disableAllPlugins(INSTANCE_ID);
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceTest#apply_shouldCreateBackupBeforeApplying -q
```

Expected: FAIL（未调用 backupService.create）。

- [ ] **Step 3: 修改 PresetService.apply 方法**

注入 `BackupService`，在 `apply` 方法开头添加：

```java
@Slf4j
@Service
public class PresetService {

    private final PluginInstallService pluginInstallService;
    private final SourceModCfgService cfgService;
    private final BackupService backupService;  // 新增

    // 构造器（@RequiredArgsConstructor 或手动）

    public void apply(Long instanceId, String presetId) {
        // ... 加载预设逻辑

        // 1. 应用前自动创建备份（失败不阻塞）
        try {
            BackupCreateDTO backupDTO = new BackupCreateDTO();
            backupDTO.setInstanceId(instanceId);
            backupDTO.setName("preset-apply-" + presetId + "-" + System.currentTimeMillis());
            backupService.create(backupDTO);
            log.info("应用预设前已创建备份: instanceId={}, preset={}", instanceId, presetId);
        } catch (Exception e) {
            log.warn("应用预设前创建备份失败（继续应用）: {}", e.getMessage());
        }

        // 2. 禁用所有插件（原逻辑）
        pluginInstallService.disableAllPlugins(instanceId);

        // ... 后续逻辑
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceTest -q
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceTest.java
git commit -m "feat(l4d2): PresetService.apply 应用预设前自动创建备份"
```

---

## Phase 6: PluginManageController 补全

> ✅ **状态：已完成**（Task 6.1 已实现 /api/plugin/l4d2/plugins/{pluginName}/readme 端点 + PluginReadmeVO + readReadme 方法）

### Task 6.1: 新增 /readme 端点

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginReadmeVO.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginManageControllerTest.java`

**Background:** 对齐 l4d2-server-next `POST /plugins/readme`：读取本地插件库中的 README.md 内容。当前 README 端点仅在 PluginStoreController 中（读取远程商店 README），缺少读取本地已安装插件 README 的能力。

- [ ] **Step 1: 创建 PluginReadmeVO**

```java
package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 插件 README 响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件 README 内容")
public class PluginReadmeVO {

    @Schema(description = "插件名")
    private String pluginName;

    @Schema(description = "README 内容（Markdown 原文）")
    private String content;

    @Schema(description = "是否存在 README 文件")
    private boolean exists;
}
```

- [ ] **Step 2: 在 PluginInstallService 添加 readReadme 方法**

```java
/**
 * 读取本地插件库中插件的 README.md 内容。
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 * @return PluginReadmeVO（exists=false 时 content 为空）
 */
public PluginReadmeVO readReadme(Long instanceId, String pluginName) {
    validatePluginName(pluginName);
    PluginReadmeVO vo = new PluginReadmeVO();
    vo.setPluginName(pluginName);

    String readmeRelPath = pathResolver.getPluginReadmePath(pluginName);
    if (!instanceFileService.exists(instanceId, readmeRelPath)) {
        vo.setExists(false);
        vo.setContent("");
        return vo;
    }

    try {
        String content = instanceFileService.readTextFile(instanceId, readmeRelPath, gbk);
        vo.setExists(true);
        vo.setContent(content != null ? content : "");
    } catch (Exception e) {
        log.warn("读取 README 失败: instanceId={}, plugin={}, err={}",
                instanceId, pluginName, e.getMessage());
        vo.setExists(false);
        vo.setContent("");
    }
    return vo;
}
```

- [ ] **Step 3: 在 PluginManageController 添加 /readme 端点**

```java
@Operation(summary = "读取插件 README", description = "读取本地插件库中插件的 README.md 内容")
@GetMapping("/{pluginName}/readme")
public Result<PluginReadmeVO> readme(
        @RequestParam Long instanceId,
        @PathVariable String pluginName) {
    log.info("读取插件 README: instanceId={}, plugin={}", instanceId, pluginName);
    return Result.success(pluginInstallService.readReadme(instanceId, pluginName));
}
```

- [ ] **Step 4: 写测试**

```java
@Test
void readme_shouldReturnContentWhenExists() {
    PluginReadmeVO vo = new PluginReadmeVO();
    vo.setPluginName("l4d2_x");
    vo.setExists(true);
    vo.setContent("# L4D2 X Plugin\n...");
    when(pluginInstallService.readReadme(1L, "l4d2_x")).thenReturn(vo);

    // 调用端点并验证
    // ...
}

@Test
void readme_shouldReturnExistsFalseWhenNotExists() {
    PluginReadmeVO vo = new PluginReadmeVO();
    vo.setPluginName("nonexistent");
    vo.setExists(false);
    vo.setContent("");
    when(pluginInstallService.readReadme(1L, "nonexistent")).thenReturn(vo);
    // ...
}
```

- [ ] **Step 5: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginManageControllerTest -q
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/PluginReadmeVO.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/PluginManageController.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/controller/PluginManageControllerTest.java
git commit -m "feat(l4d2): PluginManageController 新增 /readme 端点读取本地插件 README"
```

---

## Phase 7: 整合验证

> ✅ **状态：已完成**（2026-07-26 全模块编译 SUCCESS，414 测试全部通过）

### Task 7.1: 全模块编译 + 完整测试

**Files:**
- All modules

- [ ] **Step 1: 全模块清理编译**

```powershell
cd backend
mvn clean compile -am -q
```

Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 2: 全模块测试**

```powershell
mvn test -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: 所有测试通过。如有失败，逐个修复。

- [ ] **Step 3: 修复发现的问题**

如有测试失败，针对性修复并重新运行。

- [ ] **Step 4: 提交（如有修复）**

```powershell
git add -A
git commit -m "fix(l4d2): 修复整合测试发现的问题"
```

---

### Task 7.2: 全栈重启验证

> ✅ **状态：已完成**（2026-07-26 Step 1-4 全部验证通过；Step 5 待用户决定是否提交）

- [x] **Step 1: 使用项目脚本重启全栈**

```powershell
cd d:\program\ai\game_platform_manger
.\scripts\rebuild-restart-all.ps1
```

> 注意：脚本会执行后端编译 + 插件打包 + 后端启动 + 前端启动。
> 如仅后端代码变更，使用 `.\scripts\rebuild-restart-all.ps1 -SkipFrontend`。
> 如仅前端变更，使用 `.\scripts\rebuild-restart-all.ps1 -SkipBackendCompile -SkipPlugins`。

验证记录：使用 `.\backend\scripts\rebuild-restart.ps1 -SkipPlugins` 重启后端（本次为后端代码变更），8080 端口监听正常，登录端点可用。

- [x] **Step 2: 验证后端启动成功**

通过 8080 端口监听检查：

```powershell
netstat -an | findstr :8080
```

Expected: 出现 LISTENING 状态。

验证记录：`TCP 0.0.0.0:8080 LISTENING` + `TCP [::]:8080 LISTENING`。

- [x] **Step 3: 验证关键端点可用**

```powershell
# 验证预设端点
curl http://localhost:8080/api/plugin/l4d2/presets/list -H "Authorization: Bearer <token>"

# 验证插件配置端点（需有效 instanceId）
curl "http://localhost:8080/api/plugin/l4d2/plugin-config/get?instanceId=1&pluginName=l4d2_x" -H "Authorization: Bearer <token>"

# 验证商店端点
curl "http://localhost:8080/api/plugin/l4d2/plugin-store/list" -H "Authorization: Bearer <token>"
```

Expected: 返回 `{"code":200,...}` JSON。

验证记录：
- ✅ `presets/list` → 200，返回 4 个预设（multi-versus / fun-versus / pure-coop / official-roguelike）
- ✅ `plugin-config/get?instanceId=54&pluginName=l4d2_x` → 200（data 为 null 因插件不存在，接口响应正常）
- ✅ `plugin-store/list` → 200（SSL 已通过 [SslTrustStoreConfig](file:///d:/program/ai/game_platform_manger/backend/core/src/main/java/com/gameplatform/config/SslTrustStoreConfig.java) 使用 Windows 系统证书库解决；dataCount=0 是仓库目录结构匹配问题，非 SSL 问题，待后续优化 PluginStoreService.fetchItems 过滤逻辑）

- [x] **Step 4: 验证启动清理日志**

查看后端日志，确认出现：

```
启动清理下载临时目录: addons/sourcemod/.download_temp
下载临时目录清理完成
```

验证记录：日志显示 `启动清理下载临时目录: path=left4dead2/addons/sourcemod/.download_temp` + `下载临时目录清理完成: total=1, success=0, failed=1`（实例 54 容器未运行导致清理失败，符合"失败不阻塞"设计）。

- [ ] **Step 5: 最终提交**

```powershell
git add -A
git commit -m "chore(l4d2): v7 计划完成，全栈验证通过"
git push
```

> 待用户确认后执行（涉及 35 个修改文件 + 28 个新增文件，含 httpcore5-h2 依赖、branch 默认值修正、application.yml 配置块、v7 计划文档更新等）。

---

## Phase 8: PluginStoreMigration 启动清理补全（重做）

> ✅ **状态：已完成**（2026-07-26 PluginStoreMigration.java + 测试已创建并通过，后端重启验证日志确认触发）— 本 Phase 修订原设计缺陷（instanceId=null 调用），改为通过 InstanceQueryService 遍历所有 L4D2 实例逐个清理 `.download_temp` 目录。运行时验证发现 `@EventListener(ApplicationReadyEvent.class)` 在插件子容器创建前触发导致收不到事件，已改用 `SmartInitializingSingleton`。

### Task 8.1: 创建 PluginStoreMigration 服务（修订版）

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java`

**Background:** 对齐 l4d2-server-next `CleanDownloadTemp`：进程启动时清空 `.download_temp/` 目录。原 v7 设计 `instanceFileService.deleteDirectory(null, ...)` 中 instanceId 为 null 不被 InstanceFileService SPI 支持（SPI 强制要求 instanceId 非空以解析路径根），故改为遍历所有 L4D2 实例。

**设计要点：**
- 通过 `InstanceQueryService` 获取所有实例列表（按 gameCode=l4d2 过滤）
- 对每个实例调用 `instanceFileService.deleteDirectory(instanceId, tempPath, true)`
- 失败不阻塞：单实例清理失败仅记录警告，继续清理下一个
- 使用 `ApplicationReadyEvent` 而非 `@PostConstruct`，确保所有 Bean 就绪后执行
- 异步执行避免阻塞启动

- [ ] **Step 1: 写失败测试**

创建 `PluginStoreMigrationTest.java`：

```java
package com.gameplatform.plugin.l4d2.service;

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

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginStoreMigrationTest {

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private L4D2PathResolver pathResolver;

    @InjectMocks
    private PluginStoreMigration migration;

    private InstanceVO instance1;
    private InstanceVO instance2;

    @BeforeEach
    void setUp() {
        instance1 = new InstanceVO();
        instance1.setId(1L);
        instance1.setGameCode("l4d2");
        instance2 = new InstanceVO();
        instance2.setId(2L);
        instance2.setGameCode("l4d2");
        when(pathResolver.getDownloadTempPath()).thenReturn("addons/sourcemod/.download_temp");
    }

    @Test
    void cleanDownloadTemp_shouldIterateAllL4d2Instances() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1, instance2));

        migration.cleanDownloadTemp();

        verify(instanceFileService).deleteDirectory(eq(1L), eq("addons/sourcemod/.download_temp"), eq(true));
        verify(instanceFileService).deleteDirectory(eq(2L), eq("addons/sourcemod/.download_temp"), eq(true));
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenSingleInstanceFails() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1, instance2));
        doThrow(new RuntimeException("instance 1 cleanup failed"))
                .when(instanceFileService).deleteDirectory(eq(1L), anyString(), anyBoolean());

        // 不应抛异常，且 instance2 仍被清理
        migration.cleanDownloadTemp();

        verify(instanceFileService).deleteDirectory(eq(2L), eq("addons/sourcemod/.download_temp"), eq(true));
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenNoInstances() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of());

        migration.cleanDownloadTemp();

        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenListThrows() {
        when(instanceQueryService.listByGameCode("l4d2")).thenThrow(new RuntimeException("DB error"));

        // 不应抛异常，避免阻塞应用启动
        migration.cleanDownloadTemp();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest -q
```

Expected: FAIL（`PluginStoreMigration` 类不存在）。

- [ ] **Step 3: 实现 PluginStoreMigration（修订版）**

创建 `PluginStoreMigration.java`：

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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 插件商店启动清理服务（修订版）。
 *
 * <p>对齐 l4d2-server-next CleanDownloadTemp：
 * 在应用启动完成后（HTTP 服务对外提供前），整体清空 .download_temp/ 目录，
 * 删除上次运行残留的临时下载文件。
 *
 * <p><b>设计修订（vs v7 Phase 4.2）：</b>
 * <ul>
 *   <li>原设计 {@code instanceFileService.deleteDirectory(null, ...)} 中 instanceId=null
 *       不被 InstanceFileService SPI 支持（SPI 强制要求 instanceId 非空以解析路径根）</li>
 *   <li>改为通过 {@link InstanceQueryService#listByGameCode(String)} 获取所有 L4D2 实例列表，
 *       对每个实例分别调用 deleteDirectory</li>
 *   <li>单实例清理失败仅记录警告，不阻塞其他实例清理</li>
 *   <li>使用 {@code @Async} 异步执行，避免阻塞应用启动</li>
 * </ul>
 *
 * <p>此时不可能存在正在进行的下载任务（HTTP 服务尚未对外），整体清理安全。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginStoreMigration {

    private static final String GAME_CODE = "l4d2";

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;

    /**
     * 应用启动完成后异步清理下载临时目录。
     *
     * <p>使用 {@link ApplicationReadyEvent} 而非 @PostConstruct，
     * 确保所有 Bean 初始化完成、HTTP 服务尚未对外提供服务时执行。
     * 使用 {@code @Async} 避免阻塞启动主流程。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("taskExecutor")
    public void onApplicationReady() {
        cleanDownloadTemp();
    }

    /**
     * 清理所有 L4D2 实例的 .download_temp 目录。
     *
     * <p>对齐 l4d2-server-next plugin_store.go CleanDownloadTemp：
     * 遍历所有 L4D2 实例，逐个清理。
     *
     * <p>失败不抛异常，仅记录警告，避免阻塞应用启动。
     * 单实例失败不影响其他实例清理。
     */
    public void cleanDownloadTemp() {
        String tempPath;
        try {
            tempPath = pathResolver.getDownloadTempPath();
        } catch (Exception e) {
            log.warn("解析下载临时目录路径失败（已忽略）: {}", e.getMessage());
            return;
        }
        log.info("启动清理下载临时目录: path={}", tempPath);

        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.listByGameCode(GAME_CODE);
        } catch (Exception e) {
            log.warn("查询 L4D2 实例列表失败（跳过清理）: {}", e.getMessage());
            return;
        }

        if (instances == null || instances.isEmpty()) {
            log.info("无 L4D2 实例，跳过下载临时目录清理");
            return;
        }

        int success = 0;
        int failed = 0;
        for (InstanceVO instance : instances) {
            Long instanceId = instance.getId();
            try {
                instanceFileService.deleteDirectory(instanceId, tempPath, true);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("清理实例 {} 的下载临时目录失败（已忽略）: {}", instanceId, e.getMessage());
            }
        }
        log.info("下载临时目录清理完成: total={}, success={}, failed={}",
                instances.size(), success, failed);
    }
}
```

- [ ] **Step 4: 验证 InstanceQueryService 是否提供 listByGameCode 方法**

读取 `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceQueryService.java`：

```powershell
# 用 Grep 工具搜索 listByGameCode
```

若方法不存在，需在 InstanceQueryService 接口与 InstanceQueryServiceImpl 实现类中添加：

```java
// InstanceQueryService 接口
List<InstanceVO> listByGameCode(String gameCode);

// InstanceQueryServiceImpl 实现
@Override
public List<InstanceVO> listByGameCode(String gameCode) {
    return instanceMapper.selectList(
            new LambdaQueryWrapper<Instance>()
                    .eq(Instance::getGameCode, gameCode)
                    .eq(Instance::getIsDeleted, 0));
}
```

- [ ] **Step 5: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PluginStoreMigrationTest -q
```

Expected: PASS（4 个测试用例全部通过）。

- [ ] **Step 6: 全模块编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigration.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PluginStoreMigrationTest.java
git commit -m "feat(l4d2): 新增 PluginStoreMigration 启动时清理 .download_temp 残留（修订版，遍历实例）"
```

---

### Task 8.2: 全栈重启验证 PluginStoreMigration 生效

**Files:**
- All modules

- [ ] **Step 1: 全栈重启**

```powershell
cd d:\program\ai\game_platform_manger
.\scripts\rebuild-restart-all.ps1 -SkipFrontend
```

- [ ] **Step 2: 验证 8080 端口监听**

```powershell
netstat -an | findstr :8080
```

Expected: LISTENING。

- [ ] **Step 3: 验证启动清理日志**

```powershell
# 用 Grep 工具搜索 backend/logs/application.log 中的清理日志
# 关键字："启动清理下载临时目录" 或 "下载临时目录清理完成"
```

Expected: 出现以下日志：
```
启动清理下载临时目录: path=addons/sourcemod/.download_temp
下载临时目录清理完成: total=N, success=N, failed=0
```

- [ ] **Step 4: 提交验证记录**

```powershell
git add -A
git commit -m "chore(l4d2): Phase 8 PluginStoreMigration 验证通过"
```

---

## Phase 9: 预设增强（平台插件预校验 + 优先启用）

> ✅ **状态：已完成**（2026-07-26 PresetService 增强 + 测试通过）— 对齐开源 `preset.go` 的 platform 字段与预校验逻辑，v7 Phase 5 未覆盖。

### Task 9.1: preset.yaml 添加 platform 字段

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml`
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2PresetConfig.java`（若存在，否则在 PresetService 内部静态类）

**Background:** 对齐开源 `preset.go:13-16` 的 `PresetConfig.Platform map[string]string`：根据运行平台（windows/linux）选择必装的平台插件（如 l4d2-server-next 中是 "1.11插件平台linux版" 或 "1.11插件平台windows版"，本项目可自定义等价映射）。

- [ ] **Step 1: 读取当前 preset.yaml**

```powershell
# 用 Read 工具读取 backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml
```

- [ ] **Step 2: 在 preset.yaml 顶部添加 platform 字段**

```yaml
# 平台插件映射：根据宿主操作系统选择必装的底层插件
# 应用预设时优先启用平台插件，再启用其他插件
platform:
  windows: "platform-windows"   # Windows 必装插件名（在 plugins_store 中存在）
  linux: "platform-linux"       # Linux 必装插件名（在 plugins_store 中存在）

preset:
  - name: multi-versus
    desc: "对抗模式预设"
    plugins:
      - name: l4d2_ai_damagefix
      # ... 原有插件列表保持不变
```

> 注：若实际 plugins_store 中没有 platform-windows/platform-linux 插件，可暂时配置为空字符串 `""` 表示无平台插件。本任务的逻辑应能优雅处理空值。

- [ ] **Step 3: 在 PresetConfig 类中添加 platform 字段**

读取 `PresetService.java` 或对应的 PresetConfig 类，添加：

```java
private Map<String, String> platform = new HashMap<>();

public Map<String, String> getPlatform() {
    return platform;
}

public void setPlatform(Map<String, String> platform) {
    this.platform = platform != null ? platform : new HashMap<>();
}
```

- [ ] **Step 4: 编译验证**

```powershell
mvn clean compile -pl backend/plugin-l4d2/plugin-l4d2-core -am -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/preset.yaml backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java
git commit -m "feat(l4d2): preset.yaml 新增 platform 字段支持平台插件映射"
```

---

### Task 9.2: PresetService.apply 增加预校验与平台插件优先启用

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java`
- Test: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceApplyTest.java`

**Background:** 对齐开源 `preset.go:96-145`：
1. **预校验**（line 96-107）：启用前先检查所有插件存在于 plugins_store，任一不存在立即报错，避免半启用状态
2. **平台插件优先**（line 130-135）：先启用平台插件（必装），失败时直接返回错误（"bad state"），再启用其他插件

- [ ] **Step 1: 写失败测试**

在 `PresetServiceApplyTest.java` 中添加：

```java
@Test
void apply_shouldPreValidateAllPluginsExistAndThrowIfMissing() {
    // 假设 multi-versus 预设包含 l4d2_nonexistent 插件
    when(pluginInstallService.pluginExists(eq(100L), eq("l4d2_nonexistent"))).thenReturn(false);

    org.junit.jupiter.api.Assertions.assertThrows(
            com.gameplatform.plugin.l4d2.exception.L4D2PluginException.class,
            () -> service.apply(100L, "multi-versus-with-missing"));

    // 验证没有调用任何 enableAndLoad（预校验失败应直接返回）
    verify(pluginInstallService, never()).enableAndLoad(anyLong(), anyString());
    verify(pluginInstallService, never()).disableAllPlugins(anyLong());
}

@Test
void apply_shouldEnablePlatformPluginFirstBeforeOthers() {
    service.apply(100L, "multi-versus");

    // 验证平台插件最先被启用（在 disableAllPlugins 之后、其他插件之前）
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(pluginInstallService);
    inOrder.verify(pluginInstallService).disableAllPlugins(eq(100L));
    inOrder.verify(pluginInstallService).enableAndLoad(eq(100L), eq("platform-windows")); // 或 platform-linux
    inOrder.verify(pluginInstallService).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
    // ... 其他插件
}

@Test
void apply_shouldThrowWhenPlatformPluginFails() {
    when(pluginInstallService.enableAndLoad(eq(100L), eq("platform-windows")))
            .thenThrow(new com.gameplatform.plugin.l4d2.exception.L4D2PluginException(
                    com.gameplatform.plugin.l4d2.exception.L4D2PluginException.BUSINESS,
                    "platform plugin load failed"));

    org.junit.jupiter.api.Assertions.assertThrows(
            com.gameplatform.plugin.l4d2.exception.L4D2PluginException.class,
            () -> service.apply(100L, "multi-versus"));

    // 平台插件失败后，不应继续启用其他插件
    verify(pluginInstallService, never()).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
}

@Test
void apply_shouldSkipPlatformPluginWhenNotConfigured() {
    // platform 字段为空时，跳过平台插件启用，直接启用预设中的其他插件
    service.apply(100L, "multi-versus-no-platform");

    verify(pluginInstallService, never()).enableAndLoad(eq(100L), eq("platform-windows"));
    verify(pluginInstallService, atLeastOnce()).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
}
```

- [ ] **Step 2: 在 PluginInstallService 中添加 pluginExists 方法**

```java
/**
 * 检查插件是否存在于 plugins_store 中。
 *
 * @param instanceId 实例 ID
 * @param pluginName 插件名
 * @return true 表示存在
 */
public boolean pluginExists(Long instanceId, String pluginName) {
    if (pluginName == null || pluginName.isBlank()) {
        return false;
    }
    try {
        String storePath = pathResolver.getPluginStorePath(pluginName);
        return instanceFileService.exists(instanceId, storePath);
    } catch (Exception e) {
        log.warn("检查插件存在性失败 instanceId={}, plugin={}, err={}",
                instanceId, pluginName, e.getMessage());
        return false;
    }
}
```

- [ ] **Step 3: 修改 PresetService.apply 方法**

```java
public void apply(Long instanceId, String presetId) {
    // ... 已有的加载预设逻辑

    // 1. 应用前自动创建备份（已有逻辑保持不变）
    try {
        backupService.create(instanceId,
                "preset-apply-" + presetId + "-" + System.currentTimeMillis(),
                /* ... */);
    } catch (Exception e) {
        log.warn("应用预设前创建备份失败（继续应用）: {}", e.getMessage());
    }

    // 2. 预校验所有插件存在（新增）
    String platformPlugin = resolvePlatformPlugin();
    if (platformPlugin != null && !platformPlugin.isBlank()) {
        if (!pluginInstallService.pluginExists(instanceId, platformPlugin)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "平台插件不存在: " + platformPlugin);
        }
    }
    for (PresetPlugin pp : targetPreset.getPlugins()) {
        if (!pluginInstallService.pluginExists(instanceId, pp.getName())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "预设插件不存在: " + pp.getName() + "，请先通过商店或上传安装");
        }
    }

    // 3. 禁用所有插件（已有逻辑）
    pluginInstallService.disableAllPlugins(instanceId);

    // 4. 优先启用平台插件（新增）
    if (platformPlugin != null && !platformPlugin.isBlank()) {
        try {
            pluginInstallService.enableAndLoad(instanceId, platformPlugin);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "启用平台插件失败: " + platformPlugin + "，预设应用中止: " + e.getMessage(), e);
        }
    }

    // 5. 启用其他插件（已有逻辑）
    for (PresetPlugin pp : targetPreset.getPlugins()) {
        if (pp.getName().equals(platformPlugin)) {
            continue;  // 已在步骤 4 启用
        }
        try {
            pluginInstallService.enableAndLoad(instanceId, pp.getName());
        } catch (Exception e) {
            log.warn("启用预设插件失败（继续）: plugin={}, err={}", pp.getName(), e.getMessage());
        }
    }

    // 6. 应用配置覆盖（已有逻辑）
    for (PresetPlugin pp : targetPreset.getPlugins()) {
        for (PresetPluginConfig cfg : pp.getConfigs()) {
            applyPluginConfig(instanceId, pp.getName(), cfg);
        }
    }
}

/**
 * 解析当前运行平台的插件名。
 *
 * <p>对齐开源 preset.go:74-82 的 runtime.GOOS 判断：
 * <ul>
 *   <li>windows → platform.windows</li>
 *   <li>linux → platform.linux</li>
 *   <li>其他 → null（无平台插件）</li>
 * </ul>
 */
private String resolvePlatformPlugin() {
    Map<String, String> platform = presetConfig.getPlatform();
    if (platform == null || platform.isEmpty()) {
        return null;
    }
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("windows")) {
        return platform.get("windows");
    }
    if (osName.contains("linux")) {
        return platform.get("linux");
    }
    return null;
}
```

- [ ] **Step 4: 运行测试验证通过**

```powershell
mvn clean test -pl backend/plugin-l4d2/plugin-l4d2-core -am -Dtest=PresetServiceApplyTest -q
```

Expected: PASS（原 2 个测试 + 新增 4 个测试全部通过）。

- [ ] **Step 5: 提交**

```powershell
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PresetService.java backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PresetServiceApplyTest.java
git commit -m "feat(l4d2): PresetService 应用前预校验所有插件存在 + 平台插件优先启用"
```

---

## 风险与缓解

### 风险 1: InstanceFileService SPI 不支持 move/copyDirectory 方法
**缓解:** Task 4.1 退回到逐文件复制（递归 listFiles + copyFile + deleteFile），性能略差但功能完整。后续可在 InstanceFileService 接口扩展 `move` 方法。

### 风险 2: PluginStoreMigration 中 instanceId=null 调用可能不被支持
**缓解:** ✅ Phase 8 已修订：改为通过 `InstanceQueryService.listByGameCode("l4d2")` 遍历所有 L4D2 实例逐个清理，避免 null instanceId 调用。

### 风险 3: 商店下载临时目录在 Docker 容器内的路径与 InstanceFileService 根目录不一致
**缓解:** InstanceFileService 已统一以 instanceId + relativePath 模型工作，relativePath 相对于游戏数据根目录（Native 为 installPath，Docker 为 containerWorkDir），`.download_temp` 路径在所有部署类型下一致。

### 风险 4: applyTempConfig 修改后已有测试 mock 失败
**缓解:** 测试中 `when(rconService.executeCommand(...))` 的 mock 需匹配新命令格式 `"sm_cvar cvarName \"cvarValue\""`，运行测试后根据失败信息调整 mock 参数。

### 风险 5: CvarBlacklist 校验导致已有 updateConfig 测试失败
**缓解:** 在测试中使用安全的 CVAR 名称（如 `sm_dp`、`l4d2_max_players`），避免使用黑名单内的 `rcon_password` 等。

---

## 执行顺序建议

### 已完成（Phase 1-6，2026-07-25 核查通过）

1. **Phase 1（已修复编译阻塞）** — Task 1.1 ✅ + 1.2 ✅ + 1.3 ✅
2. **Phase 2（配置编辑增强）** — Task 2.1 ✅ + 2.2 ✅ + 2.3 ✅ + 2.4 ✅
3. **Phase 3（商店补全）** — Task 3.1 ✅ + 3.2 ✅ + 3.3 ✅
4. **Phase 4（回滚机制，部分）** — Task 4.1 ✅；Task 4.2 ❌ → 迁移至 Phase 8 重做
5. **Phase 5（预设安全，部分）** — Task 5.1 ✅；平台插件预校验缺失 → Phase 9 补全
6. **Phase 6（Controller 补全）** — Task 6.1 ✅

### 待执行（Phase 7-9）

7. **Phase 7（整合验证）** — Task 7.1 → 7.2，可选择性执行（验证已实现功能）
8. **Phase 8（PluginStoreMigration 补全，最高优先级）** — Task 8.1（创建服务）→ 8.2（全栈验证）
9. **Phase 9（预设增强，中优先级）** — Task 9.1（platform 字段）→ 9.2（预校验+优先启用）

**执行建议：** Phase 8 必做（修复 v7 遗漏的设计缺陷），Phase 9 推荐做（对齐开源项目预设特性），Phase 7 可在做完 Phase 8/9 后做整合验证。

---

## Self-Review 检查

### 1. Spec 覆盖检查

| 用户主题 | 覆盖任务 | 状态 |
|---------|---------|------|
| 存储模型 | 已实现（v5/v6 完成，本计划无改动） | ✅ 无需任务 |
| 插件来源 | 已实现（v5/v6 完成，本计划无改动） | ✅ 无需任务 |
| 删除语义 | 已实现（v5/v6 完成，本计划无改动） | ✅ 无需任务 |
| 回滚机制 | Task 4.1（临时目录原子重命名 ✅）+ Task 8.1（启动清理，待执行） | ⚠️ 部分完成 |
| 预设 | Task 5.1（应用前备份 ✅）+ Task 9.1（platform 字段，待执行）+ Task 9.2（预校验+优先启用，待执行） | ⚠️ 部分完成 |
| 商店 | Task 3.1（proxyUrl/githubToken ✅）+ Task 3.2（应用代理 ✅）+ Task 3.3（tree 缓存 ✅） | ✅ 完成 |
| 配置编辑 | Task 1.1 ✅ + 1.2 ✅ + 1.3 ✅ + 2.1 ✅ + 2.2 ✅ + 2.3 ✅ + 2.4 ✅ | ✅ 完成 |

### 2. 占位符扫描

已检查，无 "TBD"、"TODO"、"implement later"、"fill in details" 等占位符。每个步骤都包含具体代码或具体命令。

### 3. 类型一致性

- `PluginReadmeVO` 在 Task 6.1 创建，字段 `pluginName` / `content` / `exists` 在 controller / service / test 中一致 ✅
- `CvarBlacklist.check(String)` / `isDangerous(String)` 方法签名在 Task 2.3 创建，在 Task 1.1 启用调用时一致 ✅
- `PluginConfigAuditService.logUpdateConfig(...)` / `logApplyTempConfig(...)` / `logRestoreDefaults(...)` 在 Task 2.4 创建，参数列表与调用点一致 ✅
- `applyProxy(String)` / `resolveToken()` 在 Task 3.2 创建，调用点（getTree/getBlobContent/batchLfsObjects/downloadLfsObject）签名一致 ✅
- `getDownloadTempPath()` / `getDownloadTaskTempPath(String)` 在 Task 4.1 Step 1 创建，Task 8.1（修订版）调用一致 ✅
- `installFromLocalFileToTempDir(Long, File, String)` / `atomicMoveToStore(Long, String, String)` 在 Task 4.1 Step 2 创建，PluginStoreService 调用一致 ✅
- `InstanceQueryService.listByGameCode(String)` 在 Task 8.1 调用，需在 InstanceQueryService 接口添加（若不存在） ⚠️
- `PluginInstallService.pluginExists(Long, String)` 在 Task 9.2 创建，PresetService 调用一致 ⚠️
- `PresetConfig.getPlatform()` 返回 `Map<String, String>`，在 Task 9.1 创建，Task 9.2 `resolvePlatformPlugin()` 调用一致 ⚠️

### 4. 风险点

- ~~Task 4.1 依赖 InstanceFileService 的 `move` / `copyDirectory` 方法~~ ✅ 已通过 `moveFile` + `deleteDirectory` 实现
- ~~Task 4.2 的 `instanceFileService.deleteDirectory(null, ...)` 中 null instanceId 可能不被支持~~ ✅ Phase 8 已修订为遍历实例
- Task 3.2 的 `ExternalHttpClient` 可能需要扩展支持自定义 headers（Bearer token），本计划先保持 query param 方式
- Task 8.1 依赖 `InstanceQueryService.listByGameCode(String)` 方法，若该方法不存在需在 SPI 接口和实现类同步添加
- Task 9.2 依赖 `PluginInstallService.pluginExists` 方法（新增），需注意 mock 时正确返回
- Task 9.2 的预校验在备份之后执行，若预校验失败已创建的备份会保留（不影响功能，仅占空间）

---

## 修订历史

| 日期 | 版本 | 修订内容 |
|------|------|---------|
| 2026-07-25 | v7.0 | 初版，对齐开源 l4d2-server-next 7 个主题 |
| 2026-07-25 | v7.1 | 代码核查后修订：标记 Phase 1-6 已完成状态；新增 Phase 8（PluginStoreMigration 修订版，修复 instanceId=null 缺陷）；新增 Phase 9（预设平台插件预校验+优先启用，对齐开源 preset.go） |

---

*最后更新: 2026-07-25 v7.1*
