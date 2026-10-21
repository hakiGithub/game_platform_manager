# 上游开源能力挖掘与插件化规划

## 背景

平台已在 `backend/core/src/main/resources/games/` 内置 145 个游戏元数据（LinuxGSM 全集 + 少量自定义，如
`dnf_tw`），但只有 L4D2 拥有插件（`backend/plugin-l4d2`，提供 RCON 控制台、地图中心、SourceMod 插件、
配置预设、日志、监控、备份、玩家统计等 18 个页面）。其余游戏仅能用到主应用的通用能力（主机纳管、
部署、文件、Docker、生命周期）。目标是从 GitHub 上游生态里挖出这些游戏已有的 Web 管理 / GM 工具，
提炼出可复用的能力面，输出成"下一个插件做什么、按什么顺序做"的规划。

## 目标

1. 建立**已核验**的上游项目清单（仓库存在、star 数、语言、与哪些 gameCode 相关），不写想象中的仓库名。
2. 按能力域归并，产出一份统一的能力目录（capability catalog）：能力名、典型上游实现、依赖的宿主能力、
   适用游戏集合、复用层级（主应用宿主能力 / 通用插件 / 单游戏插件）。
3. 给出插件化路线图：候选插件、优先级、可复用的横向组件（RCON/WebSocket/Rest 面板骨架）。
4. 明确**不做什么**：避免把上游的授权/计费/多租户面板能力搬进来（本项目是个人自用单租户）。

## 游戏分组（调研维度）

| 组 | 代表 gameCode | 调研重点 |
|----|---------------|----------|
| Source 引擎 | `l4d2` `css` `tf2` `csgo` `gmod` `ins` `doi` `nmrih` `zps` `sven` `dods` `cscz` `hl2dm` | SourceMod/MetaMod 生态、SourceBans++、`sm` 命令体系 |
| 沙盒/建造 | `minecraft` `mcb` `pmc` `wmc` `vpmc` `terraria` `st` `fctr` `ck` `vints` | Forge/Fabric/Paper 生态、modpack 管理、存档/世界管理 |
| 生存 | `palworld` `valheim` `rust` `ark` `pz` `sdtd` `dst` `unt` `dayz` `soulmask(sm)` | 存档解析/回滚、RCON/WebSocket 命令、Oxide/UMod 插件 |
| 其他 | `dnf_tw` `squad` `kf2` `bs` `ts3` | 各自生态的现状，判断是否值得插件化 |

## 关键约定

- 所有仓库引用必须**核验过**（GitHub API 或仓库页面可访问），记录 star 数与最后活跃证据；无法核验的一律标注"未核验"而不是写进结论。
- 功能描述追溯到上游源码或官方文档（README / 源码路径 / docs 链接），不引用二手评测文章。
- 结论统一落到 `.scratch/plugin-capability-mining/` 下：调研票各写一份 `research/` 报告，汇总票产出 `capability-catalog.md` + `roadmap.md`。
- 与现有平台能力的差异必须显式标注（例如：主应用已有 RCON 传输层 `com.gameplatform.rcon` + `RconService`，插件只需做语义层）。

## 验收

- [ ] 5 份调研报告齐备，每份至少 3 个已核验上游项目 + 有源码/文档出处的功能清单。
- [ ] `capability-catalog.md` 覆盖通用于全部游戏的能力面 + 各游戏族专属能力面。
- [ ] `roadmap.md` 给出至少 3 个候选插件及其优先级理由，标注每个插件需要的宿主能力是否已具备。
- [ ] 每张票在票内记录 `## Answer` 并置 `Status: resolved`。
