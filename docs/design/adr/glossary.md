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
  - **方法**：`getGameCode() / getGameName() / getVersion() / getDescription() / getManifest() / getConfigFields() / onLoad() / onUnload() / onInstanceCreate() / onInstanceStart() / onInstanceStop() / onInstanceDelete() / onLoadError() / getIcon() / getFrontendEntry() / getBasePackage() / getDependencies()`。
  - **演变**：ADR-0001 新增 `getMenus()` default 方法。
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

### R

- **`requireInstance`**
  - **定义**：菜单项是否要求选中实例后才渲染子应用。
  - **取值**：`true`（默认）—— 必须携带 instanceId 才能进入页面，如 RCON、地图管理；`false` —— 纯资源浏览页，无需实例即可访问，如地图中心。
  - **前端消费**：`PluginTab.vue` 的 `currentMenuRequireInstance` 计算属性依据此字段决定是否弹出实例选择对话框。
  - **演变**：ADR-0001 前由 `buildDefaultMenus` 在主应用侧设置；ADR-0001 后改由插件在 `PluginMenuDeclaration` 中显式声明。
  - **引入**：项目初始（字段已存在）；**职责迁移于**：ADR-0001

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
