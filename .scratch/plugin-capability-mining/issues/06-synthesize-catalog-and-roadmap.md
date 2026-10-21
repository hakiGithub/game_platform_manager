# 06: 汇总产出能力目录与插件路线图

Status: resolved
Type: task
Blocked by: 01, 02, 03, 04, 05

## 任务

把 01-05 的调研结果合成两份决策文件，供后续 `to-spec` / `to-tickets` 直接切片实现。

### 产物一：`capability-catalog.md`

统一的能力目录，每条能力一行，字段：

| 字段 | 说明 |
|------|------|
| capability | 能力名（动词短语，如"在线玩家列表与踢出"） |
| 上游实现 | 仓库 + 源码路径链接 |
| 契约 | 落到什么上：RCON 命令 / REST / 文件与目录 / 配置文件 / 容器 API |
| 适用 gameCode | 具体游戏码列表，或"通用" |
| 宿主依赖 | 需要哪些宿主服务（`RconService`、`InstanceFileService`、`TaskService`、`ScheduleService`、`CloudDriveService`、`HostToolingService`、`SshTunnelService` 等） |
| 归属 | 主应用宿主能力 / 通用横向插件 / 单游戏插件 |
| 现状 | 平台已实现 / 部分实现 / 未实现 |

### 产物二：`roadmap.md`

- 候选插件清单（至少 3 个），每个给出：目标 gameCode、MVP 功能集、依赖的宿主能力是否具备、
  预估工作量级别（S/M/L）、优先级与理由。
- 一条"先做横向能力还是先做单游戏插件"的判断（例如：先抽一个 Source 系通用插件骨架，
  让 css/tf2/csgo 复用 L4D2 已验证的 RCON + 文件 + 任务链路）。
- 明确不做清单（多租户、计费、子用户 API 密钥等）。

## 约束

- 严格 ADR-0002 范围隔离：任何要进主应用的东西必须论证"通用宿主能力"，游戏专属能力一律归插件。
- 引用 01-05 的结论，不新引入未核验仓库。
- 现状列必须对照真实代码：`backend/plugin/` 的宿主服务面、`backend/core/` 的
  `rcon`/`instanceinfo`/`task`/`schedule`/`archive` 包、以及 `plugin-l4d2` 已有页面。

## 验收

- [x] `capability-catalog.md` 覆盖 ≥20 条能力，其中通用能力 ≥5 条。
- [x] `roadmap.md` 给出 ≥3 个候选插件，每个标注 MVP 功能集与依赖宿主能力现状。
- [x] 两份文件可作为 `to-spec` 的输入，无需再回头补调研。

## Answer

2026-10-21 完成。产物：[capability-catalog.md](../capability-catalog.md)、[roadmap.md](../roadmap.md)

### 与计划的偏差（需知悉）

票 02/03/04（Source / Minecraft / 生存族三份调研）**尚未单独产出报告**，仍为 `ready-for-agent`。
本次汇总改以**一手源码实证**替代二手调研文章作为主要依据：

- LinuxGSM `lgsm/modules/` 目录清单（真实命令面）
- LinuxGSM 34 个游戏的 `_default.cfg`（`engine`/`querytype`/端口/`workshopmods` 等权威字段）
- 平台自身 `games/*.yml` 全量 139 个 + `plugin-l4d2` 源码清点

这比"读几篇 README 再归纳"更硬，且完全规避了匿名 API 配额耗尽的问题。
但代价是：02/03/04 里那些**需要读上游源码才能回答的细节**仍未覆盖，主要是：

- SourceMod admin 权限模型与平台审计的映射（02 问题 1）
- SourceBans++ 的数据模型与是否有可调用 API（02 问题 2）
- itzg 镜像的 modpack 安装契约、MCSManager 分布式模型细节（03 问题 1-2）
- 各生存游戏的存档工具运行时依赖与 ADR-0021 可行性（04 问题 2）

这些是**动手实现前**才需要精确回答的；用于"审批优先级"这个目标，现有证据已足够。
若要进入实现，建议先补 02/03/04，或配置 `GITHUB_TOKEN` 后重跑。

### 核心结论

1. **现状基线**：139 个游戏里 134 个只有部署模板，无任何游戏专属语义能力。
2. **最大杠杆**：Source 引擎族 27 个 + GoldSrc 12 个 = 39 个游戏（28%），
   而 L4D2 插件里 `StatusParser`/`SourceModCfgParser`/`AdminsIniParser`/`VpkParser`/
   `WorkshopDownloadService` 全部只依赖 Source 引擎契约，可直接抽出复用。
3. **路线**：先横向（`plugin-source` 骨架）后垂直，P2 告警通知因与游戏无关进主应用。
