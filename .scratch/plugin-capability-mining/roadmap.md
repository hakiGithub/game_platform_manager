# 插件化路线图（v2 修订稿）

> 依据 [capability-catalog.md](capability-catalog.md)。给人读的版本见 [REPORT.md](REPORT.md)。
>
> **v2 修订**：初版说"只有 1 个插件"，实际是 **4 个**（l4d2 / dst / dnf_tw / sdtd，
> 后三个是 `D:\program\ai\` 下的独立仓库）。这改变了排序判断 —— 详见 P0。

## 核心判断

**先抽公共层，再谈新插件。**

初版的判断是"先做引擎族横向插件"，基于"只有 1 个插件、没有重复"。
知道有 4 个插件之后，这个判断要修正：四个插件里已经存在**明显的重复实现**
（两套配置解析、两套备份、两套玩家统计、四套日志解析）。
现在有 4 份重复，拖下去会变成 5 份、6 份。

而且公共层的抽取**不涉及任何游戏语义**（配置编辑、备份、日志解析都是通用能力），
风险比"抽 Source 引擎插件"低得多，还能立刻让下一个插件少写一半代码。

## P0. 插件公共层抽取（最高优先级，v2 新增）

从现有 4 个插件里抽，放进 `backend/plugin/` SDK 供所有插件共用：

| 抽取项 | 现有重复实现 | 建议 |
|--------|------------|------|
| 配置编辑框架 | l4d2 `SourceModCfgParser`(cfg) / dst `IniFile`(ini) | 格式适配器 + 表单渲染两层；以 `IniFile` 为基准（有 `mergeAndRender` 保留未知键、内置默认模板） |
| 备份 | l4d2 `BackupService`(6 类) / dst `DstBackupService`+`BackupTaskHandler`+`DstBackupScheduleHandler` | 下沉为宿主能力（备份与游戏无关） |
| 日志解析与事件入库 | l4d2 `SourceModLogService` / dst `OnlinePlayerLogParser` / 7dtd `SdtdEventRepository` / dnf-tw `PluginLogService` | 抽"tail + 模式匹配 + 事件落库"框架 |
| 玩家统计/采样 | l4d2 `PlayerStatsService`+`PlaytimeService` / dst `DstPlayerSamplingEngine`+`PlayerSampleResource` | 抽采样 + 时间序列存储 |
| HTTP 客户端 | 7dtd `SdtdWebApiService`（JDK HttpClient + Basic 鉴权） | 抽成通用 HTTP 通道，供帕鲁等 REST 型游戏复用 |

**合规性**：备份/告警按 ADR-0002 属"通用宿主能力"，下沉进主应用合规；
配置编辑/日志解析是插件公共库，放 SDK 层。

## P1. 告警通知宿主能力（S 级，进主应用）

- **覆盖**：全部 139 个游戏
- **MVP**：复用 LinuxGSM 已验证的告警面，先做 Discord / Telegram / ntfy 三种通道，
  事件源为实例启停/崩溃/更新失败/备份完成
- **归属主应用理由**：与游戏无关，属通用宿主能力，符合 ADR-0002
- 成本小、受益面最大、与 P0 无依赖可并行

## P2. `plugin-source` — Source/GoldSrc 引擎族通用插件（M 级，杠杆最大）

- **覆盖**：27 个 Source + 12 个 GoldSrc = **39 个游戏**，占平台 28%
- **MVP**：RCON 语义层（换图/踢人/封禁/status/cvar 读写）、SourceMod 插件管理（启停/配置）、
  管理员管理（`admins_simple.ini` 解析）、地图管理、创意工坊、玩家统计
- **可直接复用 L4D2 已有资产**：`StatusParser`、`SourceModCfgParser`、`AdminsIniParser`、
  `VpkParser`、`WorkshopDownloadService`、`MapService`、`ChunkUploadService`
- **宿主依赖**：`RconService` / `InstanceFileService` / `TaskService`（平台全都有）
- **排序理由（v2 变更）**：代码基本现成，但**建议排在 P0 之后** ——
  P0 抽完公共层，这个插件能少写一半代码

## P3. `plugin-palworld` — 帕鲁专用（S 级）

- **覆盖**：`palworld`（1 个游戏）
- **MVP**：REST API 接入（`players`/`info`/`metrics`/`settings`/`save`，端口 8212）、
  无人自动暂停、存档解析只读展示
- **v2 补充**：初版说这是"REST 型游戏的第一块试验田、要从零趟路"—— **错了**。
  `plugin-7dtd` 已经用 JDK `HttpClient` + Basic 鉴权接了 Alloc's Server Fixes 的 HTTP API，
  有现成实现可照着改，成本比初版估计的低
- 独立可做，适合当"验证 P0 抽取效果"的第一个用例

## 第二梯队（P0/P2 成型后的增量）

| 插件 | 覆盖 | 依据 |
|------|------|------|
| `plugin-minecraft` | minecraft/mcb/pmc/wmc/vpmc（5 个） | itzg 镜像 14k stars + MCSManager 5k stars，生态最成熟 |
| `plugin-unreal` | ark/sm/squad/st/kf/kf2/ut 系列（8 个） | UE3 内置 WebAdmin、UE4/5 RCON 契约一致 |
| `plugin-iw` | cod 系列/mohaa/rtcw/wet/etl（10 个） | 配置文件与地图管理契约一致 |
| `plugin-valheim` | valheim（1 个） | 上游容器活跃（2026-10-20）、LinuxGSM 有 `fix_vh` |

## 明确不做

- 多租户、计费、子用户 API 密钥（Pterodactyl 的核心，但本平台是个人自用单租户）
- Egg 的四段式全量移植（平台 `games/*.yml` 已有启动参数+变量+安装，**只缺配置解析段**，P0 会顺带解决）
- 无上游依据的自研协议（如自研 Bedrock RCON —— Bedrock 本来就没有 RCON）

## 建议的执行顺序

1. **P0 插件公共层抽取**（不碰游戏逻辑，风险最低，为后续所有插件减负）
2. **P1 告警通知**（进主应用，139 个游戏全量受益，与 P0 可并行）
3. **P2 `plugin-source`**（最大杠杆 39 个游戏，但依赖 P0 的成果）
4. **P3 `plugin-palworld`**（独立，验证 P0 效果）
5. 之后按第二梯队展开

P0 与 P1 都不涉及游戏语义、可并行；P2 是真正的大头但应排在 P0 之后；
P3 独立可做。

## 顺带要做的一件小事

`dst.yml` / `dnf_tw.yml` / `sdtd.yml` 的元数据里看不出它们有插件
（`configSchema` 与 `customOperations` 都是 0）。这直接导致初版调研漏掉了三个插件。
建议补一份插件登记表（或在 yml 里加显式关联字段），避免下次再靠猜。
