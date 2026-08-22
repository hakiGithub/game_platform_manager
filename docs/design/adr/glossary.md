# Glossary（术语表）

> 本术语表收录 Game Platform Manager 项目中跨模块、跨文档使用的关键术语。
> 与 [ADR 索引](README.md) 配套维护。新增术语时按字母序插入，并标注首次引入的 ADR 编号。

## 术语

### C

- **capabilities**
  - **定义**：`PluginManifestVO.frontend.capabilities` 字段，向主应用声明插件提供的能力清单。
  - **演变**：ADR-0001 前，由 `manifest.features.keys()` 推导（如 `["rcon", "mapManagement", "playerManagement"]`）；ADR-0001 后，改为由 `getMenus()` 返回的菜单 path 集合推导（如 `["/dashboard", "/rcon", "/maps", ...]`）。
  - **用途**：前端 `pluginStore.capabilities` 计算属性；未来可用于插件市场过滤、实例列表能力图标。
  - **引入**：ADR-0001

- **连接生命周期（ConnectionLifecycle）**
  - **定义**：websocket 包内的深模块，统一承载 6 个 WebSocket handler 共享的有界线程池、连接注册表（register/unregister，自动清理 SSH 连接与通道）与心跳。连接建立复用 `DeploymentAccess.connect`。
  - **引入**：架构评审 2026-08-13（候选 4）

### D

- **DeploymentAccess（部署接入）**
  - **定义**：core 模块的部署接入深模块（`com.gameplatform.deploy`）。唯一权威负责 deployType 分类归一（null/空/"native" → LINUX_GSM，未知非空值抛 `BusinessException`）与 Host→SSH 凭据解析（解密私钥/密码、端口默认 22、建连认证、私钥优先密码回退）。
  - **方法**：`classify / isDockerDeploy / isNativeDeploy / credentials(Host|hostId) / connect(Host)`。
  - **内部接缝**：SshClient 工厂可注入，供测试使用假替身。
  - **引入**：架构评审 2026-08-13（候选 2）

- **database 声明段（database declaration）**
  - **定义**：游戏元数据 yml 部署节（如 `dockerCompose`）内的 `database` 子节，声明该部署产物自带的数据库连接档案：`type / host（字面量）/ portVar / user / passwordVar（部署变量名引用）/ databases[]（静态库名列表）`。部署完成时由 DockerComposeAdapter 按声明组装为 `configInfo.database = {type, host, port, user, password, databases[]}`（用户输入值 > 默认值，与 .env 生成同源同规则）；实例更新时走同一组装方法重算。
  - **合并**：随部署节纳入 ADR-0008 整节替换合并体系（yml 基线，插件 `getDeployConfigs()` 覆盖时 variables + database 自洽替换）。
  - **语义**：`host: 127.0.0.1` 指"实例所在主机的回环地址"，插件经 SshTunnelService 隧道访问。
  - **引入**：ADR-0009

- **定时计划（Schedule）**
  - **定义**：以 cron 表达式周期性触发指定 ScheduledTaskHandler 的可重复执行定义（cron + handler key + payload 模板 + enabled 开关）。独立于任务中心 task_record 模型——不向执行队列提交任务，到点直接调用 Handler。
  - **存储**：宿主 `scheduled_task` 表（镜像 task_record 的来源隔离模式，带 source / plugin_id 字段）；插件声明的默认计划按稳定键（pluginId:key）upsert，用户的修改（cron / enabled）不被插件重启覆盖，用户删除的计划不复活。
  - **重叠语义**：上一轮仍在执行时跳过本次触发，记一条 SKIPPED 触发记录；停机期间错过的触发不补跑。
  - **关系**：与 Task（一次性执行入队）相对——计划回答"什么时候做"，Task 记录"做了什么"。
  - **引入**：ADR-0011

- **定时触发记录（Schedule Run）**
  - **定义**：定时计划每次到点（或手动触发）产生的一次执行记录，存 `scheduled_task_run` 表。状态机：RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED（终态不可变）。
  - **配套**：`scheduled_task_run_log` 存 Handler 执行日志（每 run 上限 500 条，保留 30 天）；run 落 payload 快照；手动触发标记 MANUAL 来源。
  - **引入**：ADR-0011

### E

- **Extension Point（扩展点）**
  - **定义**：PF4J 框架中插件实现宿主能力的契约接口。本项目中特指 `GameEnhancementExtension`。
  - **相关**：`@Extension` 注解标记实现类；`PluginManager.getExtensions(Class)` 加载实例。
  - **引入**：项目初始

### F

- **features（已废弃）**
  - **定义**：`manifest.features` Map 字段，原用于声明插件能力旗标（`rcon / mapManagement / playerManagement` 等布尔值）。
  - **状态**：ADR-0001 废弃。`getMenus()` 取代其菜单 gate 职责；`capabilities` 字段改为从菜单 path 推导。
  - **引入**：项目初始；**废弃于**：ADR-0001

### G

- **`GameEnhancementExtension`**
  - **定义**：游戏增强扩展点接口，每个插件恰好提供一个 `@Extension` 实现类。
  - **方法**：`getGameCode() / getGameName() / getVersion() / getDescription() / getManifest() / getConfigFields() / onLoad() / onUnload() / onInstanceCreate() / onInstanceStart() / onInstanceStop() / onInstanceDelete() / onInstanceUpdate() / onLoadError() / getIcon() / getFrontendEntry() / getBasePackage() / getDependencies() / getDeployConfigs()`。
  - **演变**：ADR-0001 新增 `getMenus()`；ADR-0008 新增 `getDeployConfigs()`；ADR-0009 新增 `onInstanceUpdate()`（实参为 update 后完整 configInfo，DB 更新后触发、吞异常、不做平台侧 diff）。
  - **引入**：项目初始（v2.0.0 重构）

### L

- **LogTailer**
  - **定义**：websocket 包内的日志流深模块，把「轮询获取日志 + 增量 diff 推送」循环抽成可测模块；接受 `LogProvider` 适配器接口（现成实现包装 `DeployAdapter.getLogs`）。不改变获取语义，只收敛结构。
  - **引入**：架构评审 2026-08-13（候选 4）

### M

- **平台代劳（platform proxy）**
  - **定义**：平台代替目标主机执行下载/解压/推送补丁的操作。仅当 `isLanHost=true` 时允许（ADR-0004 硬开关）；公网主机（isLanHost=false）不能自治时直接报错，平台不跨公网代劳。
  - **引入**：ADR-0004；补丁安装决策树落地于 ADR-0006

- **manifest**
  - **定义**：插件清单，描述插件元数据、前端入口、菜单、API 等信息。序列化为 `PluginManifestVO` 返回给前端。
  - **构建路径**：ADR-0001 后仅从扩展点 `getManifest() + getMenus()` 动态构建；`loadManifestFromFile()` 静态文件路径已删除。
  - **缓存**：`PluginFrameworkServiceImpl.manifestCache` 按 pluginId 缓存拼装结果，插件 start/stop/reload/unload 时失效。
  - **引入**：项目初始

### P

- **PatchInstallService（补丁安装服务）**
  - **定义**：插件 SDK 接口（backend/plugin）+ core 实现，把**单一 URL 资源**推送到目标实例指定位置的通用资源/内容安装机制——覆盖补丁、游戏插件、地图、mod（L4D2 插件/地图、饥荒 mod、七日杀 mod 等）。压缩包解压后推送；**非压缩包不需要解压，直接安装到指定目录**（targetPath 目录不存在时自动创建，重复安装即覆盖更新，受备份/回滚保护）。异步执行——`install(PatchInstallRequest)` 提交任务中心任务（source=MAIN、taskType=PATCH_INSTALL）返回 taskId；`probeHost(hostId)` 暴露宿主机能力预检。多文件目录列表类下载（如 L4D2 商店逐文件下载、WORKSHOP 订阅）不属于本服务范围，保持插件自管。
  - **请求字段**：`instanceId / url / targetPath（safeRel 相对路径）/ format（可选，扩展名推断）/ sha256（可选）`。
  - **决策树**：宿主机按探测（curl/wget、tar/unzip/bsdtar）与 isLanHost 门控四分支执行（ADR-0006 决策 5）；容器目标统一由宿主机代劳（挂载目录经 docker inspect Mounts 判定后写宿主源目录，否则 docker cp）。
  - **引入**：ADR-0006

- **HostCapabilities（主机能力探测结果）**
  - **定义**：`probeHost` 在宿主机执行探测脚本（SFTP 推送执行，不区分局域网）返回的 JSON 契约：`osType/hostname/arch/currentUser/tools{curl,wget,tar,gzip,bzip2,xz,unzip,bsdtar,sha256sum,shasum,rsync}/tmpFreeKb`。探测只在宿主机执行，容器内不探测。
  - **引入**：ADR-0006

- **`PluginFrameworkServiceImpl`**
  - **定义**：主应用 core 模块中实现 `PluginFrameworkService` 接口的服务类，负责插件生命周期、manifest 拼装、资源读取。
  - **演变**：ADR-0001 删除 `buildDefaultMenus` 方法，改为调用 `extension.getMenus()` 拼装菜单。
  - **引入**：项目初始

- **`PluginMenuDeclaration`**
  - **定义**：插件菜单声明对象，由插件通过 `GameEnhancementExtension.getMenus()` 返回。
  - **字段**：`title / path / icon / order / parent / requireInstance`。
  - **包路径**：`com.gameplatform.plugin.extension.PluginMenuDeclaration`（plugin 模块）。
  - **约束**：同插件内 `path` 唯一；`requireInstance` 默认 `true`。
  - **引入**：ADR-0001

### H

- **HostCredentials**
  - **定义**：`DeploymentAccess.credentials` 的返回结构，承载已解密的主机 SSH 凭据（host / port / username / privateKey / password）。
  - **引入**：架构评审 2026-08-13（候选 2）

### I

- **InstanceStatus**
  - **定义**：`DeployAdapter.InstanceStatus` 枚举（0-7），`game_instance.run_status` 列的唯一权威词汇表：0=STOPPED、1=RUNNING、2=STARTING、3=STOPPING、4=ERROR、5=INSTALLING、6=UPDATING、7=NOT_INSTALLED。
  - **派生字段**：`wireKey`（英文键，前端过滤用）与 `description`（中文文本，唯一文本源）均由枚举派生；线上契约三字段 `runStatus`（数字）/`runStatusDesc`（description）/`status`（wireKey）在 `convertToVO` 单点填充。
  - **引入**：ADR-0005

- **`isLanHost`**
  - **定义**：`Host` 实体的布尔字段，标记主机是否处于局域网（相对平台而言）。
  - **语义**：作为"平台代劳下载/解压/推送补丁"的硬开关。`true` 允许平台跨网代劳（含容器场景）；`false` 时目标主机必须能自治（curl/wget + 解压工具齐全），不能自治则报错，平台不跨公网代劳。
  - **归属**：`Host` 实体属性（非任务参数），通过 `HostQueryService.getHostById()` 返回的 `HostVO` 透传给插件。
  - **默认值**：`false`（谨慎原则：新主机默认按公网处理）。
  - **引入**：ADR-0004

### N

- **Night Operations（夜间运维设计语言）**
  - **定义**：主应用前端的暗色设计语言：海军蓝 surface 0-3 表面色阶、青色 `#27b5f3` 主色、mono kicker 微标签、6px 圆角卡片、8px 间距体系。token 定义在 `--platform-*` 命名空间。
  - **引入**：commit ea9278f；插件前端对齐决策见 ADR-0007

### P

- **platform token（平台 token）**
  - **定义**：Night Operations 的 CSS 变量集合（`--platform-surface-*`、`--platform-status-*`、`--platform-cyan/-amber/-red/-green` 等）。插件因 Wujie shadow DOM 隔离不继承宿主变量，须自带同步副本。
  - **_Avoid_**: 主题变量、皮肤变量
  - **引入**：ADR-0007

- **插件前端（plugin frontend）**
  - **定义**：随插件 JAR 分发的 Vue 子应用（如 `plugin-l4d2/frontend`），经 Wujie 嵌入宿主 `/plugin/{gameCode}/ui/` 运行；本地开发用 Vite dev 模式。样式与 token 由插件自管（ADR-0002/0007）。
  - **_Avoid_**: standalone 前端（已废弃，ADR-0003）
  - **引入**：ADR-0007

### R

- **resources（部署资源限制）**
  - **定义**：部署向导收集的用户资源选择，`configInfo.resources = { cpuLimit: Number(核), memoryLimit: Number(GB), diskLimit: Number(GB) }`。
  - **生效链路（ADR-0010）**：compose 类部署（docker-compose / linuxgsm-docker）经 `docker-compose.override.yml` 自动合并烙入容器；docker 部署经 `--memory {n}g` / `--cpus {n}` 启动参数；`diskLimit` 仅作部署前磁盘水位校验（预估），非容器硬限制；native（linuxgsm）部署不生效。
  - **默认值来源**：游戏 yml `dependencies`（cpu/memory/disk），解析失败回退 2 核 / 4GB / 10GB。
  - **_Avoid_**: 顶层 `memoryLimit`/`cpuLimit` 字符串配置（历史死代码路径，ADR-0010 已移除）
  - **引入**：ADR-0010

- **资源覆盖文件（resource override file）**
  - **定义**：平台在实例 workDir 生成的 `docker-compose.override.yml`，仅含按服务应用的 `mem_limit`/`cpus`，文件头注释声明"平台生成，手动修改会被下次部署/更新覆盖"。
  - **合并语义**：Compose 未显式传 `-f` 时自动加载主文件 + override，标量以 override 为准（用户选择覆盖模板硬编码，如 dnf_tw 的 1g/1.0）。
  - **同步时机**：preDeploy 与 update 均重新同步；cpu/memory 均未设置时主动 `rm -f` 远端 override（支持"取消限制后更新"）；模板解析失败跳过并 warn（fail-open）。
  - **生效点**：容器创建（`up -d` / `up -d --force-recreate`）；start/stop/restart 不重建容器、无需重读 override。
  - **引入**：ADR-0010

- **`requireInstance`**
  - **定义**：菜单项是否要求选中实例后才渲染子应用。
  - **取值**：`true`（默认）—— 必须携带 instanceId 才能进入页面，如 RCON、地图管理；`false` —— 纯资源浏览页，无需实例即可访问，如地图中心。
  - **前端消费**：`PluginTab.vue` 的 `currentMenuRequireInstance` 计算属性依据此字段决定是否弹出实例选择对话框。
  - **演变**：ADR-0001 前由 `buildDefaultMenus` 在主应用侧设置；ADR-0001 后改由插件在 `PluginMenuDeclaration` 中显式声明。
  - **引入**：项目初始（字段已存在）；**职责迁移于**：ADR-0001

### S

- **ScheduledTaskHandler（定时任务处理器）**
  - **定义**：定时任务体系的独立执行契约（plugin 模块扩展点），按 (source, key) 注册。与任务中心 TaskHandler 完全分离——不复用其注册表、状态机与互斥键；无自动重试（下一轮 cron 即天然重试，失败后仅支持手动重跑）。
  - **引入**：ADR-0011

- **SshTunnelService（SSH 隧道服务）**
  - **定义**：plugin SDK 宿主能力服务接口（core 委托实现，经 PluginSpringContextFactory 注入插件子容器）：`openByHost(hostId, remoteHost, remotePort)` 用平台已登记主机凭据开隧道；`openWithCredentials(ssh, remoteHost, remotePort)` 用插件自带凭据开隧道（宿主不落库、不写日志）；`close(handle)` 幂等关闭。
  - **会话归属**：openByHost 复用 SshUtil 共享会话池并钉住会话；openWithCredentials 持有专用 ClientSession（不入池），随隧道关闭而关闭（共享池键不含凭据，混用会拿错会话）。
  - **信任边界**：可信插件模型——任何已安装插件可对任意已登记主机开隧道，与 ExtensionClient 等现有 SDK 服务同信任级别。
  - **引入**：ADR-0009

- **TunnelHandle（隧道句柄）**
  - **定义**：`SshTunnelService` 的嵌套 record：`id / localPort / remoteHost / remotePort / ownerPluginId`。本地转发端口仅绑 `127.0.0.1`（OS 随机分配）。
  - **去重与计数**：去重键 = `(ownerPluginId, hostId, remoteHost, remotePort)`（插件凭据为 `(ownerPluginId, 凭据指纹, remoteHost, remotePort)`）；同插件重复 open 同目标返回同一 handle 并 +1 引用，跨插件不共享。
  - **关闭兜底（三层）**：close() 引用计数归零；插件 stop/unload 时宿主按 ownerPluginId 强制关闭（插件 onUnload 主动 close 仅是加速路径）；宿主删除主机时关闭该 hostId 的全部平台凭据隧道。
  - **引入**：ADR-0009

- **会话钉住（session pinning）**
  - **定义**：TunnelHandle 引用 SshUtil 会话池中的 CachedSession 并计数，reaper 跳过被钉住的会话。取代早期"隧道句柄纳入 reaper 空闲回收周期"的表述——隧道转发流量不刷新 CachedSession.lastUsed，字面空闲回收会误杀活跃隧道。
  - **引入**：ADR-0009

### W

- **wireKey**
  - **定义**：`InstanceStatus` 枚举的英文键字段（`stopped / running / starting / stopping / error / installing / updating / not_installed`），序列化为 `InstanceVO.status`，供前端过滤与颜色映射使用。
  - **引入**：ADR-0005

## 缩写

| 缩写 | 全称 | 含义 |
|------|------|------|
| ADR | Architecture Decision Record | 架构决策记录，本目录下每条文档记录一项长期影响的技术决策 |
| PF4J | Plugin Framework for Java | Java 插件框架，本项目使用 3.10.0 版本 |
| VO | Value Object | 视图对象，用于接口序列化 |
| SDK | Software Development Kit | 软件开发包，本项目指 `backend/plugin` 模块提供的扩展点与服务接口集合 |

## 相关文档

- [ADR 索引](README.md)
- [ADR-0001: 插件菜单归属与 getMenus() 扩展点](0001-plugin-menu-ownership.md)
- [插件开发指南](../../.trae/skills/gameplatform-plugin-dev/SKILL.md)
- [架构文档](../../architecture/ARCHITECTURE.md)
