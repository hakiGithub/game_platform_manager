# 平台游戏能力盘点报告（v2 修订版）

日期：2026-10-21 · 目标：搞清楚平台支持的 139 个游戏，上游世界已经做到了什么、我们还差什么、该先做哪个。

> **v2 修订说明**：初版只扫了 `game_platform_manger/backend/`，漏掉了主仓库之外的三个独立插件仓库
> （`D:\program\ai\plugin-7dtd`、`plugin-dnf-tw`、`plugin-dst`）。它们都已打包部署在
> `backend/plugins/` 下。初版"只有 1 个插件"的结论是错的，实际是 **4 个**。
> 更重要的是，这三个插件改变了对"插件是什么"的理解 —— 详见第一节。

---

## 一句话结论

平台有 139 个游戏、4 个插件。**插件不是"附加功能"，而是"这个游戏的管理台本身"** ——
游戏元数据（`games/*.yml`）只负责把服务器装起来，真正的 Web 管理操作全在插件里。
而且这四个插件里，有三个走的是**完全不同**的游戏通信路线，没有一条是能直接套用的。

---

## 一、先纠正：插件是游戏的"管理台本体"，不是附加功能

我初版把"游戏元数据"当成了"游戏能力"，这是个根本性的误判。实际分层是：

```
games/{游戏}.yml      → 只负责"把服务器装起来"（部署模板、端口、镜像）
plugin-{gameCode}     → 才是"这个游戏的 Web 管理台"（全部操作界面）
```

**证据**：`sdtd.yml`、`dst.yml`、`dnf_tw.yml` 三个文件里 `configSchema` 和 `customOperations`
**一个都没有**（都是 0），但它们的插件分别有 6 / 7 / 10 个管理菜单。
换句话说，元数据干净得看不出这个游戏有没有插件 —— 我初版就是被这个骗了。

### 四个插件的实际规模

| 插件 | 游戏 | Java 文件 | 后端行数 | 前端页面 | 菜单数 |
|------|------|----------|---------|---------|--------|
| `plugin-l4d2` | 求生之路2 | 223 | 25681 | 29 | 18 |
| `plugin-dst` | 饥荒联机版 | 56 | 6437 | 11 | 7 |
| `plugin-dnf-tw` | DNF 台服 | 47 | 4939 | 12 | 10 |
| `plugin-7dtd` | 7 Days to Die | 25 | 1861 | 8 | 6 |
| **合计** | | **351** | **38918** | **60** | |

四个插件都已经打包进 `backend/plugins/`（jar 日期：l4d2 是 10-21，其他是 8-9 月）。

### 最重要的一件事：四条完全不同的通信路线

这是本次调研最有价值的发现。四个插件**没有两个走同一条路**：

| 插件 | 通信路线 | 具体做法 |
|------|---------|---------|
| `l4d2` | **RCON 直连** | 平台宿主 `RconService`（ADR-0016），标准 TCP RCON |
| `7dtd` | **游戏自带 HTTP Web API** | 调用 Alloc's Server Fixes 的 `/api/executeconsolecommand`、`/api/getplayerslocation` 等，HTTP Basic 鉴权 |
| `dst` | **tmux send-keys + 日志解析** | DST **没有 RCON**，只能 `docker exec -u linuxgsm <容器> tmux -L <socket> send-keys -t <会话> "<lua>" Enter`，再解析控制台日志取结果 |
| `dnf-tw` | **直连游戏数据库** | MySQL JDBC 连 `d_taiwan` / `taiwan_cain` / `taiwan_cain_2nd` / `taiwan_billing` / `taiwan_login` 五个库，直接改数据 |

`dst` 的 ADR-0002 里写得很清楚："**DST 没有 RCON**……l4d2 插件自建 RCON 直连的先例对 DST 不可行。"
而且它是真容器实测踩过坑的 —— tmux 跑在容器内 `linuxgsm` 用户下、socket 带动态后缀，
`docker exec` 不加 `-u linuxgsm` 连不上。

`dnf-tw` 更极端，它根本不是游戏服务器管理，是**私服 GM 工具**：
角色充值、属性修改、转职觉醒、全图地狱、清理背包、删除恢复角色、邮件发放、
拍卖行运营、PVF 字典（游戏资源包解析，还处理了 GBK 编码）。

**结论**：给新游戏做插件时，"照着 l4d2 抄 RCON"这个思路只对 Source 系成立。
遇到 DST 这种没有 RCON 的、或 DNF 这种要直连数据库的，得重新找通道。

---

## 二、现状：139 个游戏，4 个有管理台

平台对每个游戏都定义了一份 `games/{游戏}.yml`，里面最关键的几样是：
`configSchema`（能在界面上改哪些配置）、`customOperations`（能执行哪些语义化操作）、部署模板。

扫完全部 139 份：

| 能力 | 游戏数 | 占比 |
|------|--------|------|
| 有插件（真正的管理台） | **4** | 2.9% |
| 有配置表单 `configSchema` | 5 | 3.6% |
| 有自定义操作 `customOperations` | 5 | 3.6% |
| 有 RCON 密码标准键 | 2 | 1.4% |

注意这两组数字**不是同一批游戏**。有 `configSchema` 的 5 个是
`l4d2` / `rust` / `palworld` / `valheim` / `minecraft`；
有插件的 4 个是 `l4d2` / `dst` / `dnf_tw` / `sdtd`。
**只有 `l4d2` 两者都有。**

也就是说：

- `rust` / `palworld` / `valheim` / `minecraft`：有配置表单，但**没有插件管理台**
- `dst` / `dnf_tw` / `sdtd`：**有插件管理台**，但元数据里看不出来
- 另外 **132 个游戏**：只能装、启、停、删、看文件、看容器日志

还有个信号：133 个游戏**只支持 `linuxgsm-docker` 一种部署方式**，
说明它们是脚本批量生成的 —— 这解释了为什么元数据层这么薄。

---

## 三、上游世界做到哪了

核验了 22 个真实存在的上游项目（star 数、地址都验证过，不存在的一律归档不用）。
分四层看：

### 通用面板层 —— 我们要抄的不是 UI，是"能力清单"

`pterodactyl/panel`（9358 stars）是这个领域的基准。它最有价值的不是界面，而是
**Egg 的四段式契约**：启动参数 + 环境变量 + 安装脚本 + **配置解析**。

对比下来，平台的 `games/*.yml` 已经有前两段，**缺的是第四段"配置解析"** ——
也就是"把游戏配置文件里的键，映射回界面上的表单"。Egg 生态（`pelican-eggs/eggs`，3091 stars）
有数百个游戏现成的这套模板，是可以借鉴的现成答案。

### 运维底座层 —— LinuxGSM 已经把"该有什么操作"定义好了

平台本来就跑在 LinuxGSM 上（4912 stars），而它的 `lgsm/modules/` 目录就是一份
**运维操作的标准答案**。我把它整个拉下来了：

- **生命周期**：启动 / 停止 / 重启 / 监控 / 查看详情 / 控制台 / 发指令
- **数据**：备份 / 校验 / 清档 / 更新 / 检查更新
- **模组**：安装 / 卸载 / 更新 / 列举 / 创意工坊同步
- **诊断**：调试 / 检查 IP / 检查状态 / 检查配置 / 检查依赖 / 检查日志 / 检查版本
- **告警**：**11 种通知通道** —— Discord、Telegram、ntfy、Slack、Matrix、Gotify、
  Pushover、Pushbullet、Rocket.Chat、IFTTT、Email
- **专属修复**：47 个游戏有 `fix_*` 模块，其中 **46 个平台也支持**

最后一条很重要：`fix_*` 存在意味着"这个游戏启动有已知的坑"。46 个游戏有专属修复脚本，
而平台完全没有对应能力 —— 用户遇到坑只能自己 SSH 进去查。

**告警那一栏平台是零。** 而它跟游戏无关，属于通用宿主能力，做一次 139 个游戏全受益。

### 游戏生态层 —— 谁有现成的轮子

| 生态 | 上游 | stars | 能拿来干什么 |
|------|------|-------|------------|
| Source 引擎 | `alliedmodders/sourcemod` | 1150 | 插件框架 + 管理员体系 |
| Source 封禁 | `sbpp/sourcebans-pp` | 375 | 封禁 / 禁言 / 管理员的 Web 管理 |
| Minecraft | `itzg/docker-minecraft-server` | **14458** | 版本切换、模组加载器、modpack 自动装 |
| Minecraft 地图 | `overviewer/Minecraft-Overviewer` | 3505 | 世界地图渲染 |
| 通用查询 | `gamedig/node-gamedig` | 737 | 跨引擎查玩家数 / 地图 / 延迟 |
| 帕鲁存档 | `cheahjs/palworld-save-tools` | 866 | 存档解析成 JSON |
| Rust 模组 | `OxideMod/Oxide.Rust` | 387 | 服务端 mod 框架 |

Minecraft 生态是最成熟的（itzg 镜像 1.4 万 stars），Source 生态是最成体系的
（SourceMod + SourceBans 是十几年的老基础设施）。

### HTTP/REST 型：平台其实已经趟通了（初版漏了）

初版我说"REST 型游戏是全新形态，值得先趟通一条" —— **错了，`plugin-7dtd` 已经趟通了**。
它调的是 7DTD 社区模组 Alloc's Server Fixes 暴露的 HTTP Web API：

```
/api/executeconsolecommand   执行控制台命令
/api/getserversinfo          服务器信息
/api/getplayerslocation      玩家位置
/api/getstats                统计数据
/api/getlog                  日志（带过滤与分页）
```

HTTP Basic 鉴权，10 秒超时，走 JDK 原生 `HttpClient`。7dtd 的 ADR-0002 管这叫
"混合通信基线"：结构化数据走 Web API，其他走 RCON/文件。

所以帕鲁那条 REST 路线**不是从零开始**，有现成的 7dtd 实现可以照着改。

### 帕鲁：一个正在发生的转向

平台用的是 `thijsvanloef` 镜像（3219 stars），我从它的 README 里实测到：

- RCON 默认**关闭**（`RCON_ENABLED=false`，端口 25575）
- REST API 默认**开启**，端口 **8212**

而另一个主流镜像 `jammsen/docker-palworld-dedicated-server`（1009 stars）的 README 第一句就是：
**"RCON 已被移除"**，所有管理能力（玩家检测、备份、重启、CLI）全部改走官方 REST API，
子命令有 `players` / `info` / `metrics` / `settings` / `gamedata` / `save`。

平台现在的 `palworld.yml` 只接了 RCON 那套思路，**REST 完全没碰**。
考虑到 7dtd 已有 HTTP 客户端实现，这条路的成本比初版估计的低不少。

---

## 四、游戏 × 功能：按引擎族看，杠杆才看得清

一个一个游戏做插件不现实。但按引擎分组后，画面完全不同：

| 引擎族 | 游戏数 | 这些游戏天生支持什么 | 平台现状 |
|--------|--------|-------------------|----------|
| **Source** | **27** | RCON、Source 查询、SourceMod/MetaMod、创意工坊、地图管理、cfg 配置 | 只有 l4d2 有插件 |
| GoldSrc | 12 | RCON（部分）、Source 查询、地图管理 | 无 |
| Unity3D | 10 | 查询、配置文件驱动、存档/世界管理 | valheim、rust 有 |
| iW（CoD 系） | 10 | 查询、配置文件、地图管理 | 无 |
| Unreal 2/3/4/5 | 8 | WebAdmin（UE3 内置）、RCON、INI 层级配置、地图管理 | palworld 有 |
| id Tech 1-4 | 5 | 查询、配置文件、地图管理 | 无 |

**Source 系 27 个游戏**是最大的一块（占 19%，加上 GoldSrc 共 39 个 / 28%）：

`l4d2` `l4d` `css` `csgo` `tf2` `tf2c` `dods` `hl2dm` `hldms` `gmod` `ins` `doi`
`nmrih` `zps` `zmr` `bmdm` `ahl2` `bb` `bb2` `cc` `dab` `dys` `em` `fof` `pvkii` `sfc`
`bs`（Blade Symphony）

（`cs2` 是 Source 2 引擎，单独一族；`sven` Sven Co-op 与 `tfc` 是 GoldSrc，
归在下面那 12 个里。）

我从 LinuxGSM 抓到的 10 个权威配置（`bs` `csgo` `css` `doi` `gmod` `ins` `l4d2` `nmrih`
`tf2` `zps`）**全部是 `engine=source`、查询协议 `protocol-valve`、端口 27015** —— 契约完全一致。

### 这里是关键

L4D2 插件里这几个组件，我逐个看过它们的代码依赖：

- `StatusParser` — 解析 RCON `status` 输出拿玩家列表
- `SourceModCfgParser` — 解析 SourceMod 配置（`parse` / `serialize` 双向）
- `AdminsIniParser` — 解析 `admins_simple.ini` 管理员文件
- `VpkParser` — 解析 VPK 打包格式
- `WorkshopDownloadService` — 创意工坊下载
- `MapService` — 地图管理

**它们全都只依赖 Source 引擎的契约，不含任何 L4D2 特有逻辑。**

`plugin-l4d2` 名义上是"求生之路 2 的插件"，实际上已经是**一个写好了的 Source 引擎插件**，
只是绑死在 l4d2 这个 gameCode 上。

---

## 五、顺带发现的重复造轮子（v2 新增）

把四个插件的同类实现摆在一起看，重复很明显：

| 能力 | l4d2 | dst | dnf-tw | 7dtd |
|------|------|-----|--------|------|
| 配置解析与回写 | `SourceModCfgParser`（cfg 双向解析） | `IniFile`（ini 解析 + `mergeAndRender`） | — | — |
| 备份 | `BackupService` + 3 个 DTO + 2 个 Resource | `DstBackupService` + `BackupTaskHandler` + `DstBackupScheduleHandler` | — | — |
| 定时任务 | `L4D2ScheduleExtension` + `MapCrawlScheduleHandler` | **8 个** ScheduleHandler + `AbstractDstScheduleHandler` | 2 个 Handler | — |
| 日志解析 | `SourceModLogService` | `OnlinePlayerLogParser` | `PluginLogService` | `SdtdServerDataParser` + `SdtdEventRepository` |
| 玩家统计 | `PlayerStatsService` + `PlaytimeService` | `DstPlayerSamplingEngine` + `PlayerSampleResource` | — | — |

两套独立的**配置文件 → 表单 → 回写**实现（`SourceModCfgParser` 对 `IniFile`），
两套独立的**备份**实现，两套独立的**玩家统计**实现。

这些重复的每一份都是能用的（都上线了），但它们意味着：
**下一个插件又要把这些写第五遍。** 这才是真正的成本所在 ——
不是"132 个游戏没插件"，而是"每加一个插件都要重造一遍轮子"。

有意思的是 `dst` 那份 `IniFile` 的质量看起来比 l4d2 的还细致：
它有 `mergeAndRender`（合并时保留未知节键）和 `defaultClusterIni` / `defaultServerIni`
（内置默认模板）。这是配置编辑该有的样子。

---

## 六、几个具体的、能立刻动手的发现

1. **Factorio 的 RCON 是现成的，没接。** LinuxGSM 配置里白纸黑字写着
   `rconport="34198"` 和 `rconpassword`，而平台 `fctr.yml` 没声明
   `rconPort` / `rconPassword` 标准键。改几行 YAML 就能有 RCON 控制台。

2. **创意工坊只有 4 个游戏开了，实际能开 27 个。** LinuxGSM 里 `workshopmods`
   只有 dayz / l4d2 / squad / st 声明了，但整个 Source 系都支持。

3. **帕鲁的 REST API（端口 8212）完全没接**，而它默认就开着，且 7dtd 已有 HTTP 通道可复用。

4. **告警能力为零**，而 LinuxGSM 有 11 种通道的实现可参照，且这是与游戏无关的宿主能力。

5. **46 个游戏有已知启动坑**（LinuxGSM 专属 `fix_*`），平台没有对应兜底。

6. **4 个插件里有 3 个的元数据是"隐形的"**（v2）：`dst` / `dnf_tw` / `sdtd` 的 yml 里
   `configSchema` 和 `customOperations` 都是 0，从元数据完全看不出它们有插件。
   这让我初版直接漏掉了它们 —— 建议给 yml 加一个 `pluginId` 之类的显式关联字段，
   或者在文档里维护一份"游戏 ↔ 插件"对照表，避免下次再靠猜。

---

## 七、建议：先抽公共层，再谈新插件

知道有 4 个插件之后，我的建议和初版**不一样了**。初版说"先横向做 `plugin-source`"，
现在我认为排序应该倒过来：**先抽公共层（不碰游戏逻辑），再做单游戏插件。**

理由：四个插件已经证明了"每做一个插件就要重造一遍轮子"（第五节）。
现在有 4 份重复实现，再拖下去会变成 5 份、6 份。而公共层的抽取
**不涉及任何游戏语义**，风险低、可增量做。

### P0 — 抽出插件公共层（最高优先级，v2 新增）

从现有 4 个插件里抽这几样，放进 `backend/plugin/` SDK 供所有插件共用：

| 抽取项 | 现有实现 | 建议做法 |
|--------|---------|---------|
| **配置编辑框架** | l4d2 `SourceModCfgParser`（cfg）、dst `IniFile`（ini，含 `mergeAndRender`） | 抽成"格式适配器 + 表单渲染"两层；`IniFile` 的保留未知键、内置默认模板是好设计，作为基准 |
| **备份能力** | l4d2 `BackupService`、dst `DstBackupService` | 下沉为宿主能力（备份本来就与游戏无关） |
| **日志解析与事件入库** | l4d2 `SourceModLogService`、dst `OnlinePlayerLogParser`、7dtd `SdtdEventRepository` | 抽"日志 tail + 模式匹配 + 事件落库"框架 |
| **玩家统计/采样** | l4d2 `PlayerStatsService`、dst `DstPlayerSamplingEngine` | 抽采样 + 时间序列存储 |
| **HTTP 客户端** | 7dtd `SdtdWebApiService`（JDK HttpClient + Basic 鉴权） | 抽成通用 HTTP 通道，帕鲁 REST 直接复用 |

**注意**：备份、告警这类能力按 ADR-0002 范围隔离，属"通用宿主能力"，下沉进主应用是合规的。
而配置编辑、日志解析这类是插件公共库，放 SDK 层。

### P1 — 告警通知（进主应用）

- 覆盖 **全部 139 个游戏**，与游戏无关
- 先做 Discord / Telegram / ntfy 三种通道，事件源：实例启停、崩溃、更新失败、备份完成
- LinuxGSM 有 11 种通道的实现可参照，不是从零设计
- 成本小、受益面最大、无依赖

### P2 — `plugin-source`：Source 引擎族横向插件

- 覆盖 **39 个游戏**（27 Source + 12 GoldSrc，占 28%）
- RCON 语义层（换图 / 踢人 / 封禁 / 查玩家 / cvar 读写）、SourceMod 插件管理、
  管理员管理、地图管理、创意工坊、玩家统计
- 主要工作是**抽取**而非新写，`StatusParser` / `SourceModCfgParser` /
  `AdminsIniParser` / `VpkParser` / `WorkshopDownloadService` / `MapService` 都能直接用
- **建议排在 P0 之后**：P0 做完，这个插件能少写一半代码

### P3 — `plugin-palworld`

- 有配置表单（15 键）但没有插件，是"半完成"状态里最值得补的
- REST API 接入（端口 8212）：`players` / `info` / `metrics` / `settings` / `save`
- **7dtd 已有 HTTP 客户端实现可照着改**，不是从零开始

### 之后

`plugin-minecraft`（5 个游戏，生态最成熟）、`plugin-unreal`（8 个）、`plugin-iw`（10 个）。

### 明确不做

- 多租户、计费、子用户 API 密钥 —— Pterodactyl 的核心，但你是个人自用单租户
- Bedrock 的 RCON —— Bedrock 本来就没有 RCON，不要自研协议
- Egg 四段式的全量移植 —— 平台已有前两段，只缺"配置解析"，P0 会顺带解决

### 执行顺序

P0 → P1 → P2 → P3。

P0 和 P1 都不碰游戏逻辑、可并行；P2 是真正的大头但依赖 P0 的成果；
P3 独立可做，适合当"验证 P0 抽取效果"的第一个用例。

---

## 八、这份报告的边界（说清楚哪些没查）

- **49 个游戏的引擎族没识别出来。** 它们既不在 LinuxGSM 抓取的 34 个里，
  平台 yml 也没写引擎。包括 `dnf_tw`、`satisfactory`、`barotrauma`、`MORDHAU`、
  `Insurgency: Sandstorm`、`SCP: Secret Laboratory ServerMod`、`pmc` / `wmc` / `vpmc` 等。
  这些要做插件需要单独确认协议面 —— 但考虑到它们大多是小众游戏，优先级本来也低。

   **注意 `dnf_tw` 就在这 49 个里，而它有插件**（DNF 台服 GM 工具，47 个 Java 文件）。
   这再次说明"元数据看不出有没有插件"—— 引擎族未识别 ≠ 没插件。

- **三份细粒度调研还没做**：SourceMod 的权限模型怎么映射到平台审计、
  Minecraft modpack 的安装契约、各生存游戏存档工具的运行时依赖。
  这些是**动手写代码前**才需要精确回答的，用来定优先级已经够了。

- **GitHub API 配额用尽了**（匿名限额 core 60/小时、search 10/小时）。
  所以 Crafty Controller 的官方仓库位置、Ark 的管理工具没能确认，已归档待办。
  后续补查建议配置 `GITHUB_TOKEN`。

- 补充说明取证方式：因为 API 配额耗尽，我改用直接抓源文件的方式
  （`raw.githubusercontent.com` 不消耗配额）。这反而拿到了更硬的东西 ——
  LinuxGSM 的**全量模块目录**和 **34 个游戏的原始配置文件**，
  而不是二手文章的转述。报告里所有"契约一致"的判断，都是从这些原始配置里读出来的。

### 关于初版的搜索失误（必须记下）

初版我把搜索范围限定在 `game_platform_manger/backend/` 下，没有看同级目录
`D:\program\ai\`。而这三个插件是**独立 git 仓库**，恰好放在那里，
打包后的 jar 才进 `backend/plugins/`。

这个失误的教训不只是"下次多搜一层"，而是暴露了一个结构性问题：
**没有一处地方写着"哪些游戏有插件、插件仓库在哪"。**
`backend/plugins/` 里的 jar 是构建产物，`games/*.yml` 里也没有关联字段。
所以只能靠猜。建议补一份 `docs/plugins.md` 登记表，或者在 yml 里加显式关联 ——
否则下次换个人来调研，会犯一模一样的错。

---

## 附：数据产物在哪

| 文件 | 内容 |
|------|------|
| `research/00-repo-inventory.md` | 22 个已核验仓库 + 8 个核验失败的归档 |
| `capability-catalog.md` | 详细能力目录（实现时的参考） |
| `roadmap.md` | 路线图（实现时的参考） |
| `research/full-matrix.csv` | 139 个游戏全量矩阵数据 |
| `research/_raw/` | 抓回来的原始配置和 README，可自己核对 |
| `research/*.ps1` | 5 个解析脚本（含外部插件扫描），数据可重新生成 |
| `research/scan-external-plugins.ps1` | 扫 `D:\program\ai\` 下的独立插件仓库，列出菜单/Controller/规模 |
