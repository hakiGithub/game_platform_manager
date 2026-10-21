# 游戏服管理能力目录

> 调研时间 2026-10-21。**v2 修订：初版漏了主仓库外的三个独立插件仓库，已补。**
> 上游出处见 [research/00-repo-inventory.md](research/00-repo-inventory.md)。
> 平台现状对照 `backend/plugin/` 宿主服务面、`backend/core/` 各包，以及**全部 4 个插件**的实现。

## 零、v2 更正：实际有 4 个插件，不是 1 个

初版只在 `game_platform_manger/backend/` 下搜索，漏掉了同级的三个独立 git 仓库。
它们都已打包进 `backend/plugins/`：

| 插件 | 仓库位置 | gameCode | Java | 行数 | 页面 | 通信路线 |
|------|---------|----------|------|------|------|---------|
| plugin-l4d2 | `backend/plugin-l4d2`（主仓库内） | l4d2 | 223 | 25681 | 29 | RCON 直连 |
| plugin-dst | `D:\program\ai\plugin-dst` | dst | 56 | 6437 | 11 | tmux send-keys + 日志解析 |
| plugin-dnf-tw | `D:\program\ai\plugin-dnf-tw` | dnf_tw | 47 | 4939 | 12 | 直连 MySQL（5 个库） |
| plugin-7dtd | `D:\program\ai\plugin-7dtd` | sdtd | 25 | 1861 | 8 | 游戏自带 HTTP Web API |

**四条通信路线无一相同。** "给新游戏做插件就照 l4d2 抄 RCON"这个思路只对 Source 系成立：
DST 明确没有 RCON（见 `plugin-dst/docs/adr/0002-console-channel.md`），
DNF 台服本质是数据库 GM 工具，7DTD 走的是 Alloc's Server Fixes 的 HTTP API。

**元数据看不出插件**：`dst.yml` / `dnf_tw.yml` / `sdtd.yml` 的 `configSchema`
与 `customOperations` 均为 0，但各有 7 / 10 / 6 个管理菜单。

## 一、现状基线（先看清自己有什么）

| 指标 | 数值 | 出处 |
|------|------|------|
| 平台内置游戏 | 139 | `backend/core/src/main/resources/games/*.yml` |
| 部署方式分布 | 133 个仅 `linuxgsm-docker`，5 个多种，1 个 `docker-compose` | 脚本扫描 |
| 有 `configSchema` | **5 个**（l4d2/minecraft/palworld/rust/valheim） | 脚本扫描 |
| 有 `customOperations` | **5 个**（同上） | 脚本扫描 |
| 有 `rconPassword` 标准键 | 2 个（l4d2/rust） | 脚本扫描 |
| **已有插件** | **4 个**（l4d2 / dst / dnf_tw / sdtd，合计 351 文件 38918 行） | 源码清点 |

**结论**：插件 ∪ configSchema 共覆盖 8 个游戏，剩下 **131 个**只有部署模板，
没有任何游戏专属语义能力。这是插件化的主要空间。
但注意两组数字**不是同一批游戏**：有 `configSchema` 的 5 个是 l4d2/rust/palworld/valheim/minecraft，
有插件的 4 个是 l4d2/dst/dnf_tw/sdtd，**只有 l4d2 两者都有**。

### 平台已具备的宿主能力（`backend/plugin/`，插件可直接注入）

`RconService`（ADR-0016 传输层）、`InstanceFileService` / `AbstractInstanceFileService` / `FileAccessService`、
`TaskService` + `TaskHandler`（异步任务）、`ScheduleService` + `ScheduledTaskHandler`（定时任务）、
`CloudDriveService`、`HostToolingService`（ADR-0021）、`HostQueryService` / `InstanceQueryService`、
`SshTunnelService`、`PatchInstallService`、`InstanceArchiveImportService`（ADR-0031）。

### L4D2 插件已实现的能力面（可直接作为复用样本）

后端 20 个 Controller：`Rcon` `Map` `MapCenter` `Monitor` `Logs` `Backup` `Preset` `ServerConfig`
`ServerInfo` `PlayerStats` `Playtime` `Admins` `PluginManage` `PluginStore` `PluginConfig` `Restart`
`Download` `ChunkUpload` `Instance` `Version`。

前端 18 个页面，菜单分六组：仪表盘 / 配置管理（服务器配置·服务器信息·预设场景）/
运维控制（控制台·系统监控·日志·重启管理）/ 地图管理（地图管理·地图中心）/
玩家管理（玩家统计·管理员）/ 扩展与数据（插件管理·备份还原·下载管理）。

关键实现资产：`VpkParser`（VPK 解析）、`WorkshopDownloadService`（创意工坊）、
`MapCrawler` + `OrangetageCrawler`（地图爬取）、`AdminsIniParser` + `SourceModCfgParser`（SourceMod 配置）、
`PlayerStatsService` + `PlayerStatsService`（玩家统计与时长）、`MonitorMetricFileStore`（监控历史）、
`PluginStoreService` + `BuiltinPluginInstaller`（插件商店）、`ChunkUploadService`（分片上传）、
`L4D2InstanceInfoProvider`（ADR-0017 动态信息）、`GbkCodecUtil`（编码处理）。

---

## 二、通用宿主能力（与游戏无关，七域对照）

| 能力域 | 上游实现 | 平台现状 | 归属判断 |
|--------|----------|----------|----------|
| 部署模板（启动参数+变量+安装脚本+配置解析） | Pterodactyl Egg 四段式；`pelican-eggs/eggs` 3091 stars | 平台 `games/*.yml` 已有前两段，**缺"配置解析"段**（把配置文件键映射回 UI 表单） | 主应用（元数据是例外，ADR-0002） |
| 生命周期 | LinuxGSM `command_start/stop/restart/monitor/details/console/send` | 已有（适配器 + 控制台 WS） | 主应用 |
| 文件管理 | Wings 文件 API：列表/读写/重命名/删/压缩解压/分片上传 | 已有 `InstanceFileService`；L4D2 有 `ChunkUploadService` | 主应用 |
| 备份 | Wings：压缩+限额+下载+恢复+轮换；LinuxGSM `command_backup` + `maxbackups`/`maxbackupdays` | 已有实例备份 | 主应用 |
| 计划任务 | Wings schedule；LinuxGSM 内置 `updateonstart`/备份轮换 | 已有 `ScheduleService` + `ScheduledTaskHandler` | 主应用 |
| 告警通知 | LinuxGSM **11 种** alert 模块（discord/email/gotify/ifttt/matrix/ntfy/pushbullet/pushover/rocketchat/slack/telegram） | **缺失** | 主应用（通用宿主能力） |
| 服务器查询（玩家数/地图/延迟） | `gamedig/node-gamedig` 737 stars，跨引擎 | 已有 `InstanceQueryService` + ADR-0017 Provider | 主应用 |
| 模组/创意工坊 | LinuxGSM `mods_install/remove/update/list` + `workshop_update` | 仅 L4D2 实现 | **通用横向插件** |
| 多租户/计费/子用户 API 密钥 | Pterodactyl 完整实现 | 无 | **明确不做**（个人自用单租户） |

---

## 三、按引擎族的能力面（这是插件复用的杠杆）

| 引擎族 | 游戏数 | 天然能力面 | 平台现状 |
|--------|--------|-----------|----------|
| **Source** | **27** | RCON、Source 查询、SourceMod/MetaMod、创意工坊、地图管理、cfg 配置 | 仅 l4d2 有插件 → **26 个游戏可复用同一套骨架** |
| GoldSrc | 12 | RCON（部分）、Source 查询、地图管理 | 无 |
| Unity3D | 10 | 查询、配置文件驱动、存档/世界管理 | valheim/rust 有配置与操作 |
| iW 引擎（CoD 系） | 10 | 查询、配置文件、地图管理 | 无 |
| Unreal 2/3/4 | 8 | WebAdmin（UE3 内置）、RCON、INI 层级配置、地图管理 | palworld(U5) 有配置 |
| id Tech 1-4 | 5 | 查询、配置文件、地图管理 | 无 |
| 其他单游戏族 | 各自 1 | 见下表 | 极少数有 |

### Source 系 27 个游戏清单（最大杠杆）

`l4d2` `l4d` `css` `csgo` `cs2` `tf2` `tf2c` `tfc` `dods` `hl2dm` `hldms` `gmod` `ins` `doi` `nmrih`
`zps` `zmr` `bmdm` `ahl2` `bb` `bb2` `cc` `dab` `dys` `em` `fof` `pvkii` `sfc` `sven`

其中 LinuxGSM 抓取到权威配置的有：`bs` `csgo` `css` `doi` `gmod` `ins` `l4d2` `nmrih` `tf2` `zps`
（全部 `querytype=protocol-valve`，端口 27015，`engine=source`）。

**关键事实**：L4D2 插件里 `AdminsIniParser`、`SourceModCfgParser`、`StatusParser`、`VpkParser`、
`WorkshopDownloadService` 都只依赖 Source 引擎契约，不依赖 L4D2 特有逻辑。
把它们抽成 `plugin-source` 横向插件，`css`/`tf2`/`gmod`/`csgo` 可以直接长出来。

---

## 四、单游戏重点能力（有上游出处的）

### Palworld（`palworld`，unreal5）

| 能力 | 上游出处 | 契约 | 现状 |
|------|----------|------|------|
| RCON | `thijsvanloef` README：`RCON_ENABLED`（默认 false）、`RCON_PORT` 25575 | RCON | 平台已有 4 个操作，但 yml 未声明 `rconPort` 标准键 |
| **REST API** | 同上：`REST_API_ENABLED`（默认 true）、`REST_API_PORT` **8212**；`jammsen` 已**移除 RCON 全转 REST** | HTTP REST | **平台完全没接** |
| REST 子命令 | `restapicli`：`players` `info` `metrics` `settings` `gamedata` `save` | REST | 未实现 |
| 无人自动暂停 | `AUTO_PAUSE_ENABLED` + `ENABLE_PLAYER_LOGGING` | 环境变量 | 未实现 |
| 存档解析 | `cheahjs/palworld-save-tools` 866 stars（Python，转 JSON） | 文件 | 未实现 |

### Rust（`rust`，unity3d）

LinuxGSM 权威字段：`port` 28015、`queryport` 28017、**`rconport` 28016**、`rconpassword`（标准键，平台已有）、
`rconweb=1`、`serverlevel`（Procedural Map/Barren/HapisIsland/SavasIsland）、`seed`、`salt`、`worldsize` 1000-6000、
`saveinterval` 300、`tickrate` 15-100、`gamemode`（vanilla/softcore）、`tags`。
上游 mod 框架：`OxideMod/Oxide.Rust` 387 stars。平台已有 7 个操作（含"更新Oxide""执行RCON命令"）。

### Minecraft 家族（`minecraft`/`mcb`/`pmc`/`wmc`/`vpmc`）

上游：`itzg/docker-minecraft-server` 14458 stars、`itzg/docker-minecraft-bedrock-server` 1874、
`overviewer/Minecraft-Overviewer` 3505、`MCSManager/MCSManager` 4995。
能力面：版本切换、模组加载器（Forge/Fabric/Quilt/Paper）、modpack 自动安装（CurseForge/Modrinth）、
世界管理、RCON（Java 25575）、Bedrock 无 RCON（仅 `server.properties`）。
平台：仅 `minecraft` 有 9 键配置 + 4 个操作。

### 其他有明确上游的

| 游戏 | 上游 | 能力 |
|------|------|------|
| `valheim` | `community-valheim-tools/valheim-server-docker` 2393 stars（活跃 2026-10-20）；LinuxGSM `fix_vh` | 世界管理、配置 |
| `pz` | `fpsacha/zomboid-control-panel` 128 stars；LinuxGSM `fix_sdtd` 同级 | 存档、Mod |
| `dayz` | LinuxGSM `engine=enfusion`，`workshopmods` **已支持** | 创意工坊、经济配置 |
| `ark`/`sm`/`squad`/`st` | LinuxGSM `fix_ark`/`fix_sm`/`fix_squad`/`fix_st` 专属修复模块；`squad`/`st` 有 `workshopmods` | RCON、INI 配置、地图 |
| `fctr` | LinuxGSM **`rconport` 34198 + `rconpassword` 标准键** | RCON、JSON 配置 |
| `ts3` | LinuxGSM `querytype=teamspeak3` + `command_ts3_server_pass` | ServerQuery、权限/频道 |

### LinuxGSM 有专属修复模块的游戏（= 有已知坑，值得优先做插件兜底）

`fix_*` 覆盖 40 个：`ark` `arma3` `armar` `av` `bf1942` `bfv` `bo` `bt` `cmw` `cod` `cod2` `coduo` `csgo`
`dst` `hw` `ins` `ios` `kf` `kf2` `mcb` `mohaa` `mta` `nd` `nmrih` `onset` `pvr` `ro` `rust` `rw` `samp`
`sdtd` `sfc` `sm` `sof2` `squad` `st` `steamcmd` `terraria` `tf2` `ts3` `unt` `ut` `ut2k4` `ut3` `vh` `wurm` `xnt` `zmr`
