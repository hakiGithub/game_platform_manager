# 技术设计 — MERC-3 部署扩展步骤与 dnf-tw 指定版本部署

| 字段 | 值 |
| --- | --- |
| **创建者** | Architect-41fff2de |
| **创建时间** | 2026-09-20 （UTC+8，占位待更新） |
| **版本** | v0.1 |
| **状态** | 草稿 |
| **Issue** | MERC-3 |
| **上游 PRD** | docs/prd/MERC-3/prd.md @ 270f9d0 |
| **上游 ADR** | docs/design/adr/0029-deploy-extension-steps.md @ 590af8d |

> 本文件为**骨架先行**提交（抗中断要求：先落盘再细化）。以下为章节与 §14.2 八行待判定清单，逐节结论在后续提交中追加。

## 1. 理解

### 1.1 系统当前在做什么

**部署是一条固定阶段流水线，且「部署完成即终态」。** `DeployService` 以固定序列推进 `INIT → ENV_CHECK → PORT_CHECK → RESOURCE_CHECK → PRE_DEPLOY → DEPLOY → HEALTH_CHECK → UPDATE_STATUS → START → COMPLETE`（F-01），进度与日志只落在内存里的 `ConcurrentHashMap<Long, DeployTaskStatus> taskStatusMap`，由 `GET /api/instances/{id}/deploy-progress` 供 `DeployProgress.vue` 轮询（F-03、F-04）。产品部署**不经过任务中心**（`DeployTaskHandler` 注册了 `(MAIN, deploy)` 但无生产调用方）。

**"版本"今天是死的。** dnf-tw 的镜像 tag 是 `dnf_tw.yml` compose 模板里的字面量（F-09）；前端提交的 `configInfo.gameVersion` 取 `selectedGame.version`，而 `GameVO` 没有 `version` 字段 ⇒ 恒为 `undefined`、序列化即丢（F-11、RISK-10）；`game_instance` 表与 `InstanceVO` 都没有版本列（F-12）。因此要跑非默认版本只能"部署完成后人工改文件 + 重启"，且这条人工动作在部署日志里完全不可见。

**已有两块底座，但都接不上部署流程。**
- 补丁链路 `PatchInstallService`（ADR-0006 / F-05）已具备 URL → 实例目标路径的全过程（下载、解压、sha256 校验、备份与自动回滚、宿主机/容器路由、并发与重试约束）。但它的 `install()` 是**异步**语义：提交任务中心、返回 `taskId`、不回传过程日志（F-06），且 `headers`/`includePattern` 在 payload 序列化时丢失（F-07）。
- 插件声明机制 ADR-0008（F-08）：`GameEnhancementExtension.getDeployConfigs()` + core 读时合并**已实现但零使用者**，本期是首个使用方。插件侧的生命周期钩子只有 `onInstanceCreate/Update/Start/Stop/Delete`，fire-and-forget、异常被吞，且部署流程内的自动启动不触发 `onInstanceStart`。

**命令通道两类，容器那一类不满足要求。** 宿主机 SSH 通道 `FileAccessService.executeCommand(hostId, command, timeoutMs)`（默认 30s，F-13）可用；容器通道 `DockerComposeAdapter.executeCommand` 走 `compose exec`，**丢弃退出码、超时硬编码 60s、只返回输出文本**（F-16、§14.2 第三行），且 compose 类"停实例"= 容器停止，停后 `exec` 必然失败（`:395` vs `:728`）。

### 1.2 本期要变成什么

在 `DEPLOY` 与 `HEALTH_CHECK` 之间插入一个**由插件声明、由主应用阻塞执行**的扩展阶段：确保实例已停 → 按声明顺序执行 `PATCH` / `SCRIPT` 步骤 → 每步的开始/结果/耗时进现有部署日志流 → 致命失败即部署失败（实例 `ERROR`、不启动、不交付）。版本目标由部署向导选择，作为实例配置的一部分落库（独立键 `deployVersion`），每次部署按同一配方重放。dnf-tw 是首个用例，但**主应用 `core/` 内不出现任何 `dnf_tw` 条件分支**（BR-01 / G-01 / N-01）；承载方是本期新建的最小 `plugin-dnf-tw` 模块（OP-01 裁决）。

### 1.3 与 PRD 范围的对齐（缺口期定性）

G0 裁决把本期定为「交付**框架 + 版本目录占位模板**，显式标注不可上线」（OP-02）：`plugin-dnf-tw` 的目录未填充 ⇒ dnf-tw 恒处 S1（向导不出现版本项、行为与现状逐字一致，AC-24），真实版号 / URL / 目标路径由人类 Owner 后续提供、**填充即生效**（BR-15 ④）。因此 AC-05 / KPI-01 / KPI-03 本期记「不可测」，框架语义改由**最小桩插件 + 验收夹具**承载验收（§11.1 承载表第一行）。

**本设计遵守的边界**：不编造任何 dnf-tw 真实版号 / 补丁 URL / 目标路径（BR-15 ① / N-12，本文件 `http(s)://` 字面量命中数 = 0）；不改写已确认决策 1–9；不修改 `docs/prd/MERC-3/prd.md`（OP-04 等定稿结论交 Leader 统一下派 @ProductManager 回写）。

## 2. 非目标

| 编号 | 不做 | 依据 |
| --- | --- | --- |
| D-N01 | 不在主应用 `core/` 内写任何 dnf-tw 专用流程或 `dnf_tw` 条件分支 | 决策 1；BR-01；N-01；AC-23 ③ |
| D-N02 | 不新增 `onInstanceDeployed` 式自由执行钩子，不改现有生命周期钩子的异常语义 | 决策 2；N-02 |
| D-N03 | 不做部署日志 / 扩展结果落库（仍为 `taskStatusMap` 内存态） | 决策 8；N-03；L-01；不改 BR-13 |
| D-N04 | 不支持插件 JAR 内置资源作为补丁来源 | 决策 6；N-04 |
| D-N05 | 不把部署主流程改造成任务中心任务（扩展阶段在现有 `deployAsync` 线程内执行） | ADR-0029 备选；N-05；F-04 |
| D-N06 | 不改动 `HEALTH_CHECK` / `START` 判定标准与 `COMPLETE = 100` 语义 | BR-10；N-06 |
| D-N07 | 不做向导期 / 环境校验期的版本可用性前置探测（不探目录可达、不探补丁 URL） | N-09；运行期是唯一判定点 |
| D-N08 | 不做「已部署版本」在实例详情 / 列表的回显、版本列、版本徽标或任何读模型 | OP-03 裁决；N-10 |
| D-N09 | 不提供一键回退默认版本、一键清除 `deployVersion`、撤销已执行步骤、事务式全量回滚 | N-08；BR-14 ③⑤ |
| D-N10 | 不为 `headers` 类鉴权补丁来源设计任何字段或通道 | §8.2 本期硬约束（`headers` 不可声明） |
| D-N11 | 不支持完整第二游戏（新游戏元数据 / 游戏页 / 插件前端 / 新部署方式适配）；桩插件仅为验收资产 | N-11；FR-24 |
| D-N12 | 不给 dnf-tw 版本目录写入任何示例值；桩插件与夹具不进产品发布物 | BR-15 ①②；N-12；AC-26 |
| D-N13 | **不重做「实例详情 → 配置管理」表单**（该表单该展示什么、是否该写 `configInfo`）——本期只落 BR-16 的保键下限 | OP-07；Leader 裁定 2；本设计只改写入语义，不改表单 |
| D-N14 | 不新增权限位、审计页、扩展步骤的运行时可视化编辑界面 | §4.2；N-07 |
| D-N15 | 不改动 `games/dnf_tw.yml`（含其 compose 模板与镜像 tag 字面量、`variables` 四项） | §5.1 第 5 项；BR-02；AC-02 / AC-15 |
| D-N16 | 不改 `configInfo.gameVersion` 坏键（不读、不写、不清理） | F-11；RISK-10；§8.4.1 |

## 3. 建议改动

（待补）

## 4. 受影响组件

（待补）

## 5. 数据与状态

（待补）

## 6. 接口与契约边界

（待补）

## 7. 实现步骤

（待补）

## 8. 非功能需求

（待补）

## 9. 迁移与回滚

（待补）

## 10. 验证计划

（待补）

## 11. 需求追溯

（待补）

## 12. 风险与边界

（待补）

## 13. 待决事项

（待补）

## 14. §14.2 待改依赖逐行结论（八行，不得留空）

| # | 待改依赖 | 归属 | 结论 |
| --- | --- | --- | --- |
| 1 | 异步 → 阻塞桥接（`PatchInstallService.install()` 只返回 taskId，不阻塞、不回传过程日志） | @Architect（RISK-03） | 待判定 |
| 2 | `includePattern` 字段透传（任务 payload 丢 `headers` / `includePattern`） | @Architect / @BackendDev（RISK-02） | 待判定 |
| 3 | 容器内脚本执行通道（`DockerComposeAdapter.executeCommand` 丢退出码、超时硬编码 60s） | @Architect / @BackendDev（F-16） | 待判定 |
| 4 | **硬判定①** `imageTag` 落位机制 = AC-14 唯一生效前提（RISK-12） | @Architect | 待判定：给出机制 → AC-14 转可验收；给不出 → AC-14 记「本期不验收」 |
| 5 | **硬判定②** 停实例 + `position = container` 合法性 = AC-13 去留（RISK-05） | @Architect | 待判定：合法 → AC-13 留在 §11.1 第一行；不合法 → AC-13 移出本期 |
| 6 | **硬判定③** §8.5 日志呈现契约三项：步骤标识承载位 / 耗时承载位与单位 / 三行归组规则 | @Architect | 待判定（AC-03 / AC-16 / KPI-02 的核对前提） |
| 7 | 扩展阶段在进度百分比序列中的区间（不改 `COMPLETE=100` 语义） | @Architect | 待判定 |
| 8 | `configInfo` 覆盖式写入丢键 → BR-16 保键手段（含 AC-27 (c)） | @Architect 定手段 / @BackendDev 落地 | 待判定：手段限「合并式写入」或「覆盖前保留本次未显式改动的键」 |

## 15. OP-04 脚本 `timeoutMs` 缺省值与上限（由 Architect 拍板）

（待补）

## 16. ADR-0008 声明机制落地（`getDeployConfigs()` 首个使用方）与 `GET` 侧契约

（待补）

## 17. 评审回应

（复审时填）

## 18. 修订记录

| 版本 | 日期 | 作者 | 变更摘要 |
| --- | --- | --- | --- |
| v0.1 | 2026-09-20 | Architect | 骨架先行：章节基线 + §14.2 八行待判定清单 |
