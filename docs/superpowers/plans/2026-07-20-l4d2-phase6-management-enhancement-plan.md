# L4D2 Phase 6: 管理增强模块实施计划

> **创建日期**: 2026-07-20
> **范围**: spec §4 模块 14（服务器配置多 tick 同步）+ 模块 15（重启）+ 模块 16（版本信息）
> **目标**: 补齐 plugin-l4d2 的管理增强功能，对齐源项目
> **依赖**: Phase 1-5 已完成（RconService、FileAccessService、L4D2Config.Restart 配置块就绪）

---

## 0. 调研结论

### 0.1 关键差异对照

| 模块 | 源项目实现 | plugin-l4d2 现状 | Phase 6 动作 |
|------|----------|-----------------|-------------|
| 管理员管理 | admin_manager.go：SourceMod admins_simple.ini 管理 | AdminController 9 端点 + AdminResource + AdminsIniParser | **已完成**，无需重复 |
| 系统信息 | server_info.go：hostname/motd/host 文件管理 | ServerInfoController 2 端点（GBK 编码支持） | **已完成**，无需重复 |
| 重启 | restart.go：RCON `_restart` 或 `docker restart` 两种方式 | L4D2Config.Restart 配置块就绪（byRcon/containerName/customCmd），但**无 RestartController** | **新增** |
| 服务器配置 | server_config.go：写主 server.cfg + 同步 4 个 tick 文件 | ServerConfigController 5 端点，但 `updateConfig` 仅 log.info 未实际写入；**无多 tick 同步** | **补齐** |
| 版本信息 | version.go：单字段 version（CI ldflags 注入） | 无任何版本端点 | **新增**（扩展字段） |

### 0.2 源项目关键参考

| 源文件 | 关键内容 |
|--------|---------|
| [restart.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/restart.go) | 优先级：RCON 方式（env L4D2_RESTART_BY_RCON=true）→ 命令方式（env L4D2_RESTART_CMD 或 `docker restart l4d2`） |
| [server_config.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/server_config.go) | 同步文件 `server.cfg.128tick/100tick/60tick/30tick`，仅当目标文件已存在时才写入，失败仅忽略；marker `// [L4D2-MANAGER-CUSTOM]` 保留策略 |
| [version.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/version.go) | 极简：`{version: "v1.2.3"}`，CI 注入，本地默认 "Dev" |

### 0.3 plugin-l4d2 现有资产

| 资产 | 路径 | 现状 |
|------|------|------|
| ServerConfigController | [controller/ServerConfigController.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/ServerConfigController.java) | 5 端点，`updateConfig` 第 116-117 行仅 `log.info` 未写入 |
| FileAccessService | plugin 模块提供 | `writeTextFile(hostId, path, content)` / `readTextFile(hostId, path, gbk)` 已就绪 |
| RconService | [service/RconService.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RconService.java) | `executeCommand(host, port, password, command)` 已就绪，超时 5s |
| L4D2Config.Restart | [config/L4D2Config.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java#L172-L179) | `byRcon=false` / `containerName="l4d2"` / `customCmd=""` |
| L4D2PathResolver | resolver/L4D2PathResolver.java | `getCfgPath(InstanceVO)` = `installPath + "/left4dead2/cfg"` |
| L4D2Plugin | L4D2Plugin.java | `getVersion()` 返回硬编码 "1.0.0" |

### 0.4 关键设计决策

1. **不引入 bcrypt / 平台账号体系**：plugin-l4d2 依赖宿主应用 Spring Security 鉴权，不重复实现。源项目"admin"也是 SourceMod 玩家管理员（SteamID），不是平台账号。
2. **不引入 actuator**：版本端点独立实现，避免引入重型依赖。
3. **多 tick 同步策略**：对齐源项目——仅当目标文件（`server.cfg.{tick}tick`）已存在时才同步覆盖，不存在则跳过；写入失败仅 log.warn 不抛异常。
4. **重启实现**：Standalone 模式支持 `docker restart` 命令（通过 `Runtime.exec`）；PF4J 模式仅支持 RCON `_restart`（无 shell 权限）。
5. **版本信息扩展**：除 version 外，增加 commit/buildTime/jdkVersion/pf4jVersion/pluginId 字段，通过 Maven 资源过滤注入 `git.properties` / `build.properties`。
6. **去掉审计**：所有操作不记录审计日志（用户明确要求）。

---

## Task 6.1: ServerConfigController 完善 + 多 tick 同步

### 目标
补齐 `updateConfig` 实际写入逻辑，并实现多 tick 配置文件同步。

### 实施步骤

#### 6.1.1 新建 ServerConfigService

**文件**: `service/ServerConfigService.java`

`@Service`，注入：`InstanceQueryService`、`FileAccessService`、`L4D2PathResolver`、`L4D2Config`

关键方法：

- `getServerConfig(Long instanceId)` → `ServerConfigVO`
  - 调用 `fileAccessService.readTextFile(hostId, cfgPath + "/server.cfg", false)` 读取原始内容
  - 解析字段：hostname/rcon_password/sv_password/sv_maxplayers/sv_visiblemaxplayers/map/mp_gamemode/z_difficulty + 自定义配置
  - 返回 VO（包含 customConfig 字段，对应源项目的 `// [L4D2-MANAGER-CUSTOM]` marker 之后的内容）

- `updateServerConfig(Long instanceId, ServerConfigUpdateDTO dto)` → `void`
  - 调用 `buildConfigContent(dto)` 拼装 server.cfg 文本（按字段顺序：hostname → rcon_password → sv_password → sv_maxplayers → sv_visiblemaxplayers → map → mp_gamemode → z_difficulty → extraConfig）
  - 在自定义配置前插入 marker `// [L4D2-MANAGER-CUSTOM]`
  - 写入主文件 `{cfgPath}/server.cfg`（通过 `fileAccessService.writeTextFile`）
  - **多 tick 同步**：调用 `syncMultiTickConfigs(instanceId, cfgPath, content)` 同步到 `server.cfg.128tick/100tick/60tick/30tick`
    - 仅当目标文件已存在时才覆盖（通过 `fileAccessService.fileExists(hostId, path)` 检查，或 try-catch NotFoundException）
    - 失败仅 `log.warn` 不抛异常

- `reloadConfig(Long instanceId)` → `void`
  - 调用 `rconService.executeCommand(host, rconPort, rconPassword, "exec server.cfg")`

- `getFileContent(Long instanceId, String fileName)` → `String`
  - 安全检查：禁止 `..` / `/` / `\`
  - 调用 `fileAccessService.readTextFile(hostId, cfgPath + "/" + fileName, false)`

- `updateFileContent(Long instanceId, String fileName, String content)` → `void`
  - 同样的安全检查
  - 调用 `fileAccessService.writeTextFile`

- `private String buildConfigContent(ServerConfigUpdateDTO dto)`：拼装配置文本，按字段顺序输出，自定义配置前加 marker
- `private void syncMultiTickConfigs(Long instanceId, String cfgPath, String content)`：遍历 4 个 tick 文件，存在则覆盖
- `private String getRconConnection(InstanceVO instance)`：从 instance.getConfigInfo() 读 rconPort/rconPassword，host 硬编码 "127.0.0.1"（带 TODO）

#### 6.1.2 重构 ServerConfigController

**修改**: `controller/ServerConfigController.java`

- 注入 `ServerConfigService`
- `GET /get`：调用 `serverConfigService.getServerConfig(instanceId)`
- `POST /update`：调用 `serverConfigService.updateServerConfig(instanceId, dto)`
- `POST /reload`：调用 `serverConfigService.reloadConfig(instanceId)`
- `GET /file-content`：调用 `serverConfigService.getFileContent(instanceId, fileName)`
- `POST /file-content`：调用 `serverConfigService.updateFileContent(instanceId, fileName, content)`
- **移除** Controller 中的业务逻辑（buildServerConfigVO、getConfigPath、getRconConnection 等私有方法迁移到 Service）

#### 6.1.3 VO/DTO 扩展

**修改**: `vo/ServerConfigVO.java`
- 字段对齐源项目：hostname/rconPassword/svPassword/maxPlayers/visibleMaxPlayers/mapName/gameMode/difficulty/extraConfig(Map<String,String>)/customConfig(String)

**修改**: `dto/ServerConfigUpdateDTO.java`
- 字段对齐 ServerConfigVO（除 extraConfig/customConfig 外都可为 null，null 跳过）

#### 6.1.4 测试

**文件**: `test/.../service/ServerConfigServiceTest.java`
- 测试用例（≥8）：
  - `get_server_config_parses_fields_correctly`
  - `get_server_config_returns_empty_when_file_missing`
  - `update_server_config_writes_main_file`
  - `update_server_config_syncs_multi_tick_when_target_exists`
  - `update_server_config_skips_multi_tick_when_target_missing`
  - `update_server_config_preserves_custom_config_after_marker`
  - `reload_config_calls_rcon_exec`
  - `get_file_content_rejects_path_traversal`
  - `update_file_content_rejects_path_traversal`

### 验收标准
- 9+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- updateConfig 实际写入文件（不再仅 log.info）

---

## Task 6.2: RestartController + RestartService

### 目标
实现 L4D2 服务器重启功能，支持 RCON 方式和命令方式。

### 实施步骤

#### 6.2.1 新建 RestartService

**文件**: `service/RestartService.java`

`@Service`，注入：`InstanceQueryService`、`RconService`、`L4D2Config`

关键方法：

- `restart(Long instanceId, RestartMode mode)` → `void`
  - `mode` 为枚举：`AUTO`（按配置决定）/ `RCON` / `COMMAND`
  - AUTO 模式：读取 `config.getRestart().isByRcon()`，true 走 RCON，false 走 COMMAND
  - RCON 模式：调用 `rconService.executeCommand(host, rconPort, rconPassword, "_restart")`
    - host 从 instance 配置读取
    - 抛异常时包装为业务异常并附带明确提示
  - COMMAND 模式：调用 `executeRestartCommand()`
    - 命令优先级：`config.getRestart().getCustomCmd()` 非空 → 使用自定义命令
    - 否则使用 `docker restart {containerName}`（containerName 默认 "l4d2"）
    - 通过 `Runtime.getRuntime().exec(...)` 执行
    - Windows: `cmd.exe /c {cmd}`
    - Linux: `sh -c {cmd}`
    - 等待 exit code，非 0 抛异常
    - 命令注入防护：使用 `ProcessBuilder` 而非 `Runtime.exec(String)`，命令拆分为参数数组

- `restartByRcon(Long instanceId)` → `void`：便捷方法，强制 RCON 模式
- `restartByCommand(Long instanceId)` → `void`：便捷方法，强制 COMMAND 模式

- `private String[] buildCommand(String cmd)`：根据操作系统构建命令数组
  - Windows: `["cmd.exe", "/c", cmd]`
  - Linux: `["sh", "-c", cmd]`
- `private String resolveContainerName(Long instanceId)`：从 instance 配置读取容器名（如 Docker 部署的 containerName），未配置则用 `config.getRestart().getContainerName()`

#### 6.2.2 新建 Controller

**文件**: `controller/RestartController.java`

`@RestController` + `@RequestMapping("/api/plugin/l4d2/restart")`

端点：
- `POST /` `{instanceId, mode?}` → `Result<Void>`（mode 可选，默认 AUTO）
- `POST /rcon` `{instanceId}` → `Result<Void>`（强制 RCON 模式）
- `POST /command` `{instanceId}` → `Result<Void>`（强制 COMMAND 模式）
- `GET /config` → `Result<RestartConfigVO>`（返回当前配置：byRcon/containerName/customCmd/availableModes）
- `POST /config` `{byRcon?, containerName?, customCmd?}` → `Result<Void>`（更新配置，admin 鉴权）

#### 6.2.3 新建 VO/DTO

- `vo/RestartConfigVO.java`：byRcon/containerName/customCmd/availableModes(List<String>)
- `dto/RestartDTO.java`：instanceId(Long, @NotNull)/mode(String, 可选 AUTO/RCON/COMMAND)
- `dto/RestartConfigUpdateDTO.java`：byRcon(Boolean)/containerName(String)/customCmd(String)
- `enums/RestartMode.java`：AUTO/RCON/COMMAND

#### 6.2.4 配置扩展

**修改**: `config/L4D2Config.java`

`Restart` 内部类新增字段：
- `long commandTimeoutMs = 30_000L`（命令执行超时）
- `boolean enabled = true`（运行时开关）

#### 6.2.5 测试

**文件**: `test/.../service/RestartServiceTest.java`
- 测试用例（≥7）：
  - `restart_auto_uses_rcon_when_byRcon_true`
  - `restart_auto_uses_command_when_byRcon_false`
  - `restart_rcon_mode_calls_execute_command_with_restart`
  - `restart_command_mode_uses_custom_cmd_when_set`
  - `restart_command_mode_uses_docker_restart_when_no_custom_cmd`
  - `restart_command_mode_handles_nonzero_exit_code`
  - `restart_command_mode_handles_timeout`
  - `restart_disabled_throws_exception`

**文件**: `test/.../controller/RestartControllerTest.java`
- 测试用例（≥4）：
  - `restart_default_uses_auto_mode`
  - `restart_rcon_endpoint_calls_rcon_mode`
  - `get_config_returns_current_settings`
  - `set_config_updates_byRcon`

### 验收标准
- 12+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- 命令注入防护到位（ProcessBuilder + 参数数组）

---

## Task 6.3: VersionController

### 目标
实现版本信息查询端点，返回插件版本、构建时间、JDK 版本等信息。

### 实施步骤

#### 6.3.1 新建 BuildProperties 读取器

**文件**: `util/BuildInfoReader.java`

`@Component`，启动时通过 `ClassPathResource` 读取 `META-INF/build.properties`（Maven 资源过滤生成）。

字段：
- `version`：版本号（从 `plugin.properties` 或 build.properties 读取）
- `commit`：Git commit hash（短）
- `buildTime`：构建时间（ISO 格式）
- `jdkVersion`：`System.getProperty("java.version")`
- `pf4jVersion`：从 `PluginWrapper` 获取或硬编码

关键方法：
- `@PostConstruct init()`：读取 `META-INF/build.properties`，解析为 `Properties`，赋值字段
  - 文件不存在时所有字段为 "unknown"
- `BuildInfoVO toVO()`：转换为 VO

#### 6.3.2 Maven 资源过滤配置

**修改**: `plugin-l4d2-core/pom.xml`

`<build><resources>` 段添加：
```xml
<resource>
  <directory>src/main/resources</directory>
  <filtering>true</filtering>
  <includes>
    <include>META-INF/build.properties</include>
  </includes>
</resource>
```

**新建**: `src/main/resources/META-INF/build.properties`（模板）：
```
version=${project.version}
commit=${git.commit.id.abbrev}
buildTime=${maven.build.timestamp}
```

考虑添加 `git-commit-id-maven-plugin`（如不想引入新插件，commit 字段可暂时为 "unknown"）。

#### 6.3.3 新建 Controller

**文件**: `controller/VersionController.java`

`@RestController` + `@RequestMapping("/api/plugin/l4d2/version")`

端点：
- `GET /` → `Result<BuildInfoVO>`（完整版本信息）
- `GET /short` → `Result<String>`（仅 version 字符串，对齐源项目极简接口）

#### 6.3.4 新建 VO

**文件**: `vo/BuildInfoVO.java`
- `version`：插件版本
- `commit`：Git commit hash
- `buildTime`：构建时间
- `jdkVersion`：JDK 版本
- `pf4jVersion`：PF4J 框架版本
- `pluginId`：插件 ID（固定 "plugin-l4d2"）
- `pluginDescription`：插件描述
- `springBootVersion`：Spring Boot 版本（从 `SpringBootVersion.getVersion()` 获取）

#### 6.3.5 测试

**文件**: `test/.../util/BuildInfoReaderTest.java`
- 测试用例（≥4）：
  - `init_reads_build_properties_when_present`
  - `init_uses_unknown_when_properties_missing`
  - `to_vo_maps_all_fields`
  - `jdk_version_returns_system_property`

**文件**: `test/.../controller/VersionControllerTest.java`
- 测试用例（≥3）：
  - `get_version_returns_full_info`
  - `get_short_returns_version_string`
  - `get_version_includes_plugin_id`

### 验收标准
- 7+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- `mvn clean package` 后 `META-INF/build.properties` 文件被正确替换

---

## Task 6.4: 前端 ServerConfig + Restart + Version 页面

### 目标
完善前端三个管理增强模块的页面，对齐源项目 UI。

### 实施步骤

#### 6.4.1 API 扩展

**修改**: `frontend/src/api/index.ts`

新增类型定义：
```typescript
export interface ServerConfigVO {
  hostname: string
  rconPassword: string
  svPassword: string
  maxPlayers: number
  visibleMaxPlayers: number
  mapName: string
  gameMode: string
  difficulty: string
  extraConfig: Record<string, string>
  customConfig: string
}
export interface ServerConfigUpdateDTO {
  instanceId: number
  hostname?: string
  rconPassword?: string
  svPassword?: string
  maxPlayers?: number
  visibleMaxPlayers?: number
  mapName?: string
  gameMode?: string
  difficulty?: string
  extraConfig?: Record<string, string>
  customConfig?: string
}
export interface RestartConfigVO {
  byRcon: boolean
  containerName: string
  customCmd: string
  availableModes: string[]
}
export interface RestartDTO {
  instanceId: number
  mode?: 'AUTO' | 'RCON' | 'COMMAND'
}
export interface BuildInfoVO {
  version: string
  commit: string
  buildTime: string
  jdkVersion: string
  pf4jVersion: string
  pluginId: string
  pluginDescription: string
  springBootVersion: string
}
```

新增 API 模块：
```typescript
export const restartApi = {
  restart: (data: RestartDTO) => post('/restart', data),
  restartByRcon: (instanceId: number) => post('/restart/rcon', { instanceId }),
  restartByCommand: (instanceId: number) => post('/restart/command', { instanceId }),
  getConfig: () => get<RestartConfigVO>('/restart/config'),
  setConfig: (data: Partial<RestartConfigVO>) => post('/restart/config', data),
}

export const versionApi = {
  get: () => get<BuildInfoVO>('/version'),
  getShort: () => get<string>('/version/short'),
}
```

扩展 `serverConfigApi`（如已存在则补充）：
```typescript
export const serverConfigApi = {
  get: (instanceId: number) => get<ServerConfigVO>('/server-config/get', { instanceId }),
  update: (data: ServerConfigUpdateDTO) => post('/server-config/update', data),
  reload: (instanceId: number) => post('/server-config/reload', { instanceId }),
  getFileContent: (instanceId: number, fileName: string) =>
    get<string>('/server-config/file-content', { instanceId, fileName }),
  updateFileContent: (instanceId: number, fileName: string, content: string) =>
    post('/server-config/file-content', { instanceId, fileName, content }),
}
```

#### 6.4.2 ServerConfig.vue 重构

**修改**: `frontend/src/pages/ServerConfig.vue`（如存在则重构，否则新建）

- el-form 表单：
  - hostname、rconPassword、svPassword、maxPlayers、visibleMaxPlayers、mapName、gameMode、difficulty
  - 自定义配置：el-input textarea（对应 customConfig）
  - 额外配置：动态 KV 列表（el-table 内嵌 el-input）
- 操作按钮：
  - 保存：调用 `serverConfigApi.update`
  - 重载配置：调用 `serverConfigApi.reload`（带确认弹窗）
  - 查看原始文件：el-select 选择文件名 + el-dialog 展示内容（可编辑保存）
- 多 tick 同步提示：保存成功后 el-message 提示"已同步到所有已存在的 tick 配置文件"

#### 6.4.3 Restart.vue

**文件**: `frontend/src/pages/Restart.vue`

- 顶部：重启配置卡片
  - el-switch：byRcon（RCON 模式 / 命令模式）
  - el-input：containerName（命令模式下使用）
  - el-input：customCmd（可选，覆盖默认 docker restart 命令）
  - 保存按钮：调用 `restartApi.setConfig`
- 中部：重启操作区
  - 大按钮"重启服务器"（el-button type="danger" size="large"）
  - 点击后弹出确认对话框（el-message-box）
  - 确认后调用 `restartApi.restart`，loading 状态
  - 成功/失败后 el-message 提示
- 高级选项（el-collapse）：
  - 强制 RCON 模式重启按钮
  - 强制命令模式重启按钮
- 提示信息：
  - RCON 模式：通过 RCON 协议发送 `_restart` 命令，需服务器在线
  - 命令模式：通过 shell 执行 `docker restart`，需管理端有 shell 权限

#### 6.4.4 VersionInfo.vue

**文件**: `frontend/src/pages/VersionInfo.vue`

- el-descriptions 展示版本信息：
  - 插件版本（version）
  - 插件 ID（pluginId）
  - 插件描述（pluginDescription）
  - Git Commit（commit）
  - 构建时间（buildTime）
  - JDK 版本（jdkVersion）
  - PF4J 版本（pf4jVersion）
  - Spring Boot 版本（springBootVersion）
- 复制按钮：el-button 复制完整版本信息到剪贴板

#### 6.4.5 路由配置

**修改**: `frontend/src/router/index.ts`

新增/更新路由：
```typescript
{ path: '/restart', name: 'Restart', meta: { title: '重启管理', icon: 'RefreshRight' }, component: () => import('../pages/Restart.vue') },
{ path: '/version', name: 'Version', meta: { title: '版本信息', icon: 'InfoFilled' }, component: () => import('../pages/VersionInfo.vue') },
```

保留现有 `/server-config` 路由。

### 验收标准
- 前端 `npm run build` 通过
- 三个页面可正常访问
- ServerConfig 保存后实际写入文件
- Restart 按钮有二次确认
- VersionInfo 展示完整信息

---

## Task 6.5: Phase 6 集成验证

### 目标
全模块集成测试 + 端到端验证 + 提交。

### 实施步骤

#### 6.5.1 全量测试

```bash
cd backend
mvn test -pl plugin-l4d2/plugin-l4d2-core
# 期望：Phase 5 (281) + Phase 6 新增 (~30) = 311+ tests, 0 failures

cd frontend
npm run build
# 期望：构建成功
```

#### 6.5.2 集成验证

- 启动应用，调用 `/api/plugin/l4d2/version` 验证版本信息
- 调用 `/api/plugin/l4d2/server-config/get` 验证配置读取
- 调用 `/api/plugin/l4d2/restart/config` 验证重启配置

#### 6.5.3 提交

- plugin-l4d2 仓库提交（按 Task 分组）：
  - `feat(l4d2): server config sync with multi-tick files (phase 6.1)`
  - `feat(l4d2): restart service with rcon & command modes (phase 6.2)`
  - `feat(l4d2): version endpoint with build info (phase 6.3)`
  - `feat(l4d2-fe): server config & restart & version pages (phase 6.4)`
- 主仓库提交：
  - `feat(l4d2): phase 6 management enhancement (server config + restart + version)`

### 验收标准
- 全部测试通过
- 前端构建成功
- 主仓库和 plugin-l4d2 子模块均已提交

---

## 执行顺序与依赖

```
Task 6.1 (ServerConfig) ─┐
                         ├─→ Task 6.4 (前端) ─→ Task 6.5 (集成验证)
Task 6.2 (Restart) ──────┤
                         │
Task 6.3 (Version) ──────┘
```

- Task 6.1 / 6.2 / 6.3 可**并行**派发（互不依赖）
- Task 6.4 依赖 6.1+6.2+6.3 的 API 定义
- Task 6.5 依赖全部完成

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| `docker restart` 命令在 PF4J 模式下无权限 | 默认 byRcon=false 仅 Standalone 模式可用；PF4J 模式强制走 RCON（检测运行模式） |
| 命令注入攻击 | 使用 `ProcessBuilder` + 参数数组，不使用 `Runtime.exec(String)` |
| server.cfg 文件不存在 | 读取时返回空 VO，写入时自动创建 |
| 多 tick 文件同步失败 | 仅 log.warn 不抛异常，主文件写入成功即返回 200 |
| build.properties 文件缺失 | BuildInfoReader 降级返回 "unknown"，不影响启动 |
| Git commit 信息不可用 | 暂时不引入 git-commit-id-plugin，commit 字段为 "unknown" 或后续优化 |

---

*最后更新: 2026-07-20*
