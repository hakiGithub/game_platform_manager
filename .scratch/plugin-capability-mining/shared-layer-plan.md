# 插件公共层抽取方案（问题 2 与问题 3 的答复）

日期：2026-10-21 · 依据：4 个插件的源码清点 + PF4J 3.10.0 源码核验 + 平台 `PluginSpringContextFactory` 源码

---

# 问题 2：公共层该抽哪些功能

## 判定标准

我用的标尺是：**"换个游戏，这份代码要不要改？"**

- 不用改 → 与游戏无关 → 该进公共层
- 要改 → 含游戏语义 → 留在插件里

当前 4 个插件里，有 5 个能力被重复实现了 2 次以上，全部符合"不用改"标准。

## 五类待抽取能力

### 1. 配置文件编辑框架（重复 2 次）

| 插件 | 现有实现 | 格式 | 特点 |
|------|---------|------|------|
| l4d2 | `SourceModCfgParser` | Source cfg | `parse` / `serialize` 双向 |
| dst | `IniFile` + `DstConfigFileService` | INI | `parse` / `render` / `asMap` / **`mergeAndRender`** / `defaultClusterIni` / `defaultServerIni` |

**以 dst 的为基准**，因为它有两个 l4d2 没有的关键设计：

- `mergeAndRender(currentContent, update)` —— 合并时**保留未知节键**，不会因插件不认识某个键就把它删掉
- `defaultClusterIni()` / `defaultServerIni(master)` —— 内置默认模板，文件不存在时也能渲染出表单

抽取形态：

```java
// 格式适配器（按扩展名/声明选择）
public interface ConfigFormatAdapter {
    Map<String, Map<String, String>> parse(String content);
    String mergeAndRender(String current, Map<String, Map<String, String>> update);
    String defaultTemplate();          // 默认模板
}
// 内置实现：IniAdapter / SourceCfgAdapter / JsonAdapter / XmlAdapter
```

上层再挂一层"节-键 → 表单字段"的渲染（插件通过注解或声明式 schema 描述字段类型、
枚举、范围、分组），这部分游戏相关，留在插件侧。

**收益**：`sdtd`（`serverconfig.xml`）、`valheim`、`st`（`setting.xml`）、`terraria`、
`unt`（`Config.json`）、`ck`（`ServerConfig.json`）、`vints`（`serverconfig.json`）都能直接复用。
按 LinuxGSM 的 `servercfg` 字段统计，这批至少有 7 个游戏用的是 ini/xml/json 类配置。

### 2. 备份（重复 2 次）

| 插件 | 现有实现 |
|------|---------|
| l4d2 | `BackupService` + `BackupContent` + 3 个 DTO + 2 个 Resource |
| dst | `DstBackupService` + `BackupTaskHandler` + `DstBackupScheduleHandler` |

**备份完全与游戏无关**（就是打包目录 + 保留策略 + 恢复），按 ADR-0002 属通用宿主能力，
建议**下沉进主应用**，直接进 `backend/plugin/` 服务面。

LinuxGSM 已经定义好了策略参数：`maxbackups=4`、`maxbackupdays=30`、`stoponbackup=on`，
可直接照搬。

### 3. 日志解析与事件入库（重复 4 次）

| 插件 | 现有实现 |
|------|---------|
| l4d2 | `SourceModLogService` |
| dst | `OnlinePlayerLogParser` |
| 7dtd | `SdtdServerDataParser` + `SdtdEventRepository`（聊天 / 进出 / 击杀死亡三类事件） |
| dnf-tw | `PluginLogService` |

四个插件各写一遍"tail 日志 → 模式匹配 → 结构化落库"。
公共部分（远端 tail、编码处理、增量游标、分页查询）可抽，
游戏相关的部分（正则模式、事件类型枚举）留在插件侧。

**注意编码坑**：l4d2 有 `GbkCodecUtil`，dnf-tw 的 `PvfParser` 也处理了 GBK。
中文私服日志编码问题是共通的，编码探测应进公共层。

### 4. 玩家统计与采样（重复 2 次）

| 插件 | 现有实现 |
|------|---------|
| l4d2 | `PlayerStatsService` + `PlaytimeService` + 多个 Resource |
| dst | `DstPlayerSamplingEngine` + `PlayerSampleResource` + `SamplingConfigSpec` |

两者本质都是"周期性采样在线玩家 → 存时间序列 → 出趋势图"。
可抽：采样调度、快照存储、按天聚合、趋势计算。
留插件：玩家标识解析（SteamID / EOS ID / KU ID 各不相同）。

### 5. HTTP 客户端通道（已有 1 次，但可复用面广）

| 插件 | 现有实现 |
|------|---------|
| 7dtd | `SdtdWebApiService`（JDK `HttpClient` + Basic 鉴权 + 10 秒超时 + `DisposableBean` 清理） |

7dtd 已经把这条路趟通了。抽成通用 `HttpChannelService`（超时、鉴权、重试、连接器生命周期）
后，`palworld`（REST 端口 8212）可以直接用。

## 不建议抽的（看着像公共，其实是游戏语义）

| 能力 | 为什么不抽 |
|------|-----------|
| RCON 命令语义 | 已经在宿主 `RconService`（ADR-0016），语义层各游戏命令不同，抽不了 |
| Mod / 创意工坊管理 | 目录结构与协议各引擎不同（Source 用 Workshop collection，DST 用 ACF + SteamCMD，7dtd 用 Modlet 目录） |
| 玩家标识解析 | SteamID / EOS ID / KU ID / 数据库 characNo 各不相同 |
| 定时任务**的具体内容** | 调度框架已在宿主 `ScheduleService`，各插件的 handler 语义不同 |

## 落地位置

按 ADR-0002 范围隔离：

| 能力 | 落地位置 | 理由 |
|------|---------|------|
| 备份 | 主应用 `backend/plugin/` 服务面 | 通用宿主能力 |
| 编码探测 | 主应用 util | 通用 |
| 配置编辑框架 | 插件 SDK（`backend/plugin/`）+ 可选通用库 | 库性质，不含游戏语义 |
| 日志解析框架 | 插件 SDK | 同上 |
| 玩家统计采样 | 插件 SDK | 同上 |
| HTTP 通道 | 插件 SDK | 同上 |

---

# 问题 3：`plugin-source` 如何被其他插件（如 l4d2）引用

## 先说结论

**技术上可行，但当前的平台代码没接通。** 有两条路，我建议走第一条。

## 机制核验（已查 PF4J 3.10.0 源码与平台代码）

### 平台当前状态：类加载层面可用，但依赖声明没被读取

1. **类加载器支持**：`GamePlatformPluginManager.createPluginLoader()` 用
   `new PluginClassLoader(pluginManager, descriptor, parentClassLoader, true)`，
   `parentFirst=true` 对应 PF4J 的 **`ClassLoadingStrategy.APD`**
   = Application → Plugin → **Dependencies**。
   即 PF4J 的类加载器**已经会在第三个来源查找依赖插件的类**。
   出处：`pf4j/PluginClassLoader.java` 的 `loadClassFromDependencies()`，
   它遍历 `pluginDescriptor.getDependencies()` 并委托给
   `pluginManager.getPluginClassLoader(dependency.getPluginId())`。

2. **依赖声明入口存在**：PF4J 通过 `plugin.properties` 的
   **`plugin.dependencies`** 键读取（`PropertiesPluginDescriptorFinder.PLUGIN_DEPENDENCIES`），
   格式为逗号分隔的 `pluginId`，可选带版本约束（如 `plugin-source@1.0.0`）。

3. **启动顺序有保障**：`AbstractPluginManager.resolvePlugins()` 用
   `DependencyResolver` 做拓扑排序（`result.getSortedPlugins()`），
   `startPlugin()` 也会**递归先启动依赖**。环依赖会抛 `CyclicDependencyException`，
   缺失依赖抛 `DependenciesNotFoundException`。

4. **⚠️ 但平台没真正用它**：全仓库 `rg "getDependencies"` 只命中四处，逐一来看：

   | 命中 | 性质 | 是否接通依赖机制 |
   |------|------|-----------------|
   | `GameEnhancementExtension.java:235` | SDK 里的 `default List<String> getDependencies()`，返回空列表 | **否** —— 只是声明，**没有任何插件覆写它，也没有任何代码读它** |
   | `PluginFrameworkServiceImpl.java:360` | `plugin.getDescriptor().getDependencies().toString()` | **否** —— 读的是 PF4J descriptor，只是塞进 `PluginStatusVO.dependencies` 做**前端展示**，不影响加载 |
   | `PluginFrameworkServiceImplTest` / `PluginLifecycleHookTest` | 测试 mock `when(descriptor.getDependencies()).thenReturn(emptyList())` | 否 |

   另外 `rg "plugin\.dependencies|PluginDependency"` 在 `backend/` 下**零命中** ——
   即没有任何 `plugin.properties` 写过 `plugin.dependencies`，主应用也没有解析它的代码。

   结论：SDK 声明了 `getDependencies()`，但**它和 PF4J 的 `plugin.dependencies` 之间没有胶水代码**。
   直接覆写 `getDependencies()` 返回 `List.of("plugin-source")` **目前不会产生任何效果**。

### ⚠️ 顺带发现：开发指南教了一个不生效的用法

`docs/plugin-development/reference/extension-and-menus.md` 第 94 行：

```java
@Override public List<String> getDependencies() { return List.of("common-lib"); } // 依赖的 gameCode
```

`docs/architecture/ARCHITECTURE.md` 第 219 行也把它列为
"`getDependencies()` — 依赖的 gameCode 列表 [v2.0 新增]"。

**两处都不准确，按它写会踩坑**：

1. 这个方法**没有任何读取方**，覆写了也不生效（见上表）。
2. 注释说"依赖的 gameCode"，但 PF4J 原生机制 `plugin.dependencies`
   认的是 **pluginId 而非 gameCode** —— 两者不同（如 `plugin-sdtd` 的 gameCode 是 `sdtd`）。
   即使将来接通，按 gameCode 传也会匹配不上。

建议：要么在 SDK 里真正实现它（加主应用胶水代码），
要么在文档里标注"当前未生效"。别让下一个开发插件的人照着写一遍再debug。

### Spring 子容器层面：Bean 不共享

`PluginSpringContextFactory` 对每个插件建独立子容器：

```java
AnnotationConfigApplicationContext childContext = new AnnotationConfigApplicationContext();
childContext.setParent(mainContext);               // 父 = 主应用容器
childContext.setClassLoader(wrapper.getPluginClassLoader());
childContext.getBeanFactory().registerSingleton("rconService", rconService);  // 宿主服务逐个注入
childContext.scan(basePackage);                    // 只扫自己 plugin.properties 的 basePackage
```

关键：`childContext.scan(basePackage)` **只扫本插件的包**。
即使类加载器看得见 `plugin-source` 的类，**也不会自动变成 Bean**，
`plugin-l4d2` 的 `@Autowired` 拿不到 `plugin-source` 里的 `@Component`。

另外 `PluginContext` 只暴露 `getPluginId()` / `getGameCode()` / `getVersion()` /
`getGameName()` / `getCustomProperties()`，**不含 PluginManager**，
插件没有官方途径去拿别的插件的容器。

## 方案 A：公共代码进 SDK（推荐）

**做法**：把公共层的类放进 `backend/plugin/`（SDK 模块），插件以 `provided` scope 依赖它
（和现在依赖 `game-platform-plugin` 一样）。

```
plugin-l4d2 / plugin-css / plugin-tf2  ──provided──>  game-platform-plugin (SDK)
                                                        ├── ConfigFormatAdapter + Ini/Xml/Json 实现
                                                        ├── LogTailFramework
                                                        ├── PlayerSamplingFramework
                                                        └── HttpChannelService
```

**优点**

- 零新增机制：插件本来就 `provided` 依赖 SDK（l4d2 的 pom 已这么干）
- 类加载无歧义：`parentFirst=true` 保证 SDK 类从应用加载器加载，**不会出现 ClassCastException**
- Bean 也简单：公共类若是无状态工具类，各插件 `@Bean` 自己注册即可；
  若需共享，由主应用注册到 `mainContext`，子容器通过 parent 自动可见
- 卸载安全：无插件间卸载顺序约束

**适用**：配置编辑框架、日志解析框架、采样框架、HTTP 通道 —— 也就是上面五类里的四类。

**代价**：SDK 变胖。需要守住边界 —— SDK 只放"换个游戏不用改"的代码
（ADR-0002 已规定主应用不写插件业务，这条要跟着守）。

## 方案 B：PF4J 插件依赖（可行但有代价）

**做法**：`plugin-l4d2` 的 `plugin.properties` 加

```properties
plugin.dependencies=plugin-source
```

然后 `plugin-l4d2` **自己** `@ComponentScan("com.gameplatform.plugin.source")`
或 `@Import` 把 source 的类注册进自己的子容器。

**优点**：不需要改主应用；PF4J 原生机制，加载/启动/卸载顺序自动处理。

**代价（需要在动手前想清楚）**

1. **Bean 仍需手动接**：类加载器可见 ≠ Bean 存在。必须显式扫描或导入，
   否则 `@Autowired` 失败。
2. **加载顺序硬约束**：`plugin-source` 必须先启动；卸载 `plugin-source`
   会连带卸载所有依赖方（`AbstractPluginManager.unloadPlugin` 的
   `unloadDependents` 逻辑）。热部署 `plugin-source` 会打断全部 Source 系插件。
3. **版本耦合**：`plugin.dependencies=plugin-source@1.0.0` 一旦写死版本，
   source 升级要同步改所有下游。
4. **SDK 里的 `getDependencies()` 是个陷阱**：它现在没人读，
   如果直接实现它**不会有任何效果**（不会被转成 PF4J 的 `plugin.dependencies`）。
   要用方案 B，得先在主应用补一段"读 `getDependencies()` → 写入 descriptor"的胶水代码，
   或者干脆绕开它、直接在 `plugin.properties` 里写。

## 我的建议

**方案 A 为主，方案 B 只在必要时用。**

理由是这五类公共能力本质上都是"库"，不是"服务" —— 它们没有自己的状态、
不需要独立生命周期、不需要被单独热部署。这种情况塞进 PF4J 插件依赖
是**用重机制解决轻问题**，反而引入加载顺序和版本耦合。

具体到 `plugin-source` 这个案例，我建议拆成两半：

| 部分 | 归属 | 机制 |
|------|------|------|
| VPK 解析、Workshop 下载、`status` 解析、SourceMod cfg 解析、管理员 ini 解析 | **SDK**（`backend/plugin/`） | 方案 A，所有插件 `provided` 依赖 |
| Source 引擎的菜单、Controller、任务 handler（需要独立生命周期与热部署） | `plugin-source` 插件 | 可选方案 B |

这样 `plugin-l4d2` 引用公共能力**不需要** `plugin.dependencies`，
而 `plugin-css` / `plugin-tf2` 想复用整套 Source 管理台时才走方案 B。

## 如果确实要走方案 B，需要补的代码

1. 主应用：把 `GameEnhancementExtension.getDependencies()` 的结果
   写进 PF4J descriptor（或在加载时读 `plugin.properties` 的 `plugin.dependencies`）。
   目前这块是空的，需要新增。
2. 下游插件：在自己的 Spring 配置里显式 `@ComponentScan` / `@Import` 上游包，
   否则 Bean 拿不到。
3. 热部署脚本：`scripts/deploy-plugin.sh` 需要处理"卸载 source 会连带卸载下游"的情况。

---

## 附：本次核验的源码出处

| 结论 | 出处 |
|------|------|
| 类加载策略为 APD，含 DEPENDENCIES 阶段 | `pf4j/ClassLoadingStrategy.java`：`APD = [APPLICATION, PLUGIN, DEPENDENCIES]` |
| 依赖类查找实现 | `pf4j/PluginClassLoader.java`：`loadClassFromDependencies()` |
| 依赖声明键 | `pf4j/PropertiesPluginDescriptorFinder.java`：`PLUGIN_DEPENDENCIES = "plugin.dependencies"` |
| 拓扑排序与顺序启动 | `pf4j/AbstractPluginManager.java`：`resolvePlugins()` / `startPlugin()` |
| 平台确实用 APD | `backend/core/.../GamePlatformPluginManager.java`：`new PluginClassLoader(..., true)` |
| 子容器只扫自己包 | `backend/core/.../PluginSpringContextFactory.java`：`childContext.scan(basePackage)` |
| `getDependencies()` 未被读取 | 全仓库 `rg "getDependencies"` 仅命中 SDK 定义、文档与测试 mock |
