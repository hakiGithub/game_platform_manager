# 技术设计 — MERC-3 部署扩展步骤与 dnf-tw 指定版本部署

| 字段 | 值 |
| --- | --- |
| **创建者** | Architect-41fff2de |
| **创建时间** | 2026-09-20 |
| **版本** | v0.3 |
| **状态** | 待 ArchReviewer 第 2 轮复审（§17 已逐条填写 REV-1…REV-7 + SUG-1…SUG-9；§14 待改依赖扩至十四条，逐行带状态与证据指向） |
| **Issue** | MERC-3 |
| **上游 PRD** | docs/prd/MERC-3/prd.md @ 270f9d0（v0.5，双层门禁 PASS） |
| **上游 ADR** | docs/design/adr/0029-deploy-extension-steps.md @ 590af8d（远端 `agent/leader/chat-1cfa252af963`，实现分支须 merge 带入） |
| **平行输入** | docs/ui/MERC-3/ui-spec.md @ f6312ef（S1b；其 §10-B 三项界面侧前提由本文 §14.4 / §14.9 / §14.11 收口） |
| **交付分支** | `release/MERC-3-deploy-extension-steps` |

> 本文按「先落盘再细化」分次提交：v0.1 骨架（§1/§2 + 待判定清单）→ v0.2 补齐 §3–§16 → **v0.3 并入架构评审第 1 轮（REV-1…REV-7 全采纳 + SUG-1…SUG-9）** 与 Leader 的两项范围裁定（每主机互斥取「补一层等价互斥」、EXTENSION 支持集合按 FR-11 字面收口为 compose 两类）。
>
> **v0.3 的三处实质变化**（其余为同步）：① §14.14 —— BR-09 的每主机互斥**过去被写错**：真实承担者是任务中心的内存互斥键，绕开任务中心即绕开它，本期由同步入口自行承键补上（不改 `PatchInstallExecutor`、不引入任务中心）；② §14.13 —— 扩展阶段收尾「把容器起回」原先**没有可调用的面**，且该结论对三类容器适配器同时成立，现定死为 `DeployAdapter` 一个 default 方法 + **逐类**收口形状，支持集合钉为 {`docker-compose`, `linuxgsm-docker`}，集合外声明不合法；③ §14.16 —— PRD 点名交本设计的 RISK-09（retry-deploy 先 `uninstall`）收口，`BR-14` 的「保留」自此显式限定为**当次部署内**、不跨 attempt。逐条处置见 §17。

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
| D-N05 | 不把部署主流程改造成任务中心任务（扩展阶段在现有 `deployAsync` 线程内执行）。**边界（v0.3 补）**：同主机互斥借用的是任务中心的**内存键管理器** `TaskMutexManager`（`putIfAbsent`/`remove`），不 submit、不建 `TaskRecord`、不注册 Handler、不占其线程池 ⇒ 仍是「不改造成任务中心任务」。**不得**把这条借用读成「本期接入了任务中心」 | ADR-0029 备选；N-05；F-04；§14.14 |
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

### 3.1 一句话方案

在 `DeployService` 的 `DEPLOY` 完成行与 `HEALTH_CHECK` 之间插入一个**「有声明才存在」的 `EXTENSION` 阶段**：读版本目录（单一读者，**只支持 `docker-compose` / `linuxgsm-docker` 两类 deployType**，集合外声明即不合法）→ 确保实例停止 → 按声明序**阻塞**执行 `PATCH`（同步补丁入口，**同主机互斥由任务中心同一个内存键管理器承键**）与 `SCRIPT`（宿主机 SSH）→ **收尾以各类 `DEPLOY` 已在用的 `up -d` 形状经 `DeployAdapter` 新增的 default 方法把容器起回** → 每步的结构化日志行进既有部署日志流 → 致命失败即部署失败。版本选择以 `configInfo.deployVersion` 为唯一载体，通用配置写路径改为**合并式**以保住该键。主应用 `core/` 内不出现任何游戏码分支。

### 3.2 执行时序（本期新增部分加粗）

```
INIT → ENV_CHECK → PORT_CHECK → RESOURCE_CHECK → PRE_DEPLOY → DEPLOY
   ↓
【EXTENSION 阶段】（仅当解析出步骤集 ≥1 才存在；仅 deployType ∈ {docker-compose, linuxgsm-docker} 允许声明，§14.13.1；否则本段整体不存在，序列与今天逐字相同）
   进入行 → 确保停止（3×2s 判定）→ E-1 步骤行 → E-2 步骤行 → … → 收尾起回容器（adapter.ensureRunningForExtension，§14.13.2/3）→ 阶段完成行 → 交棒行
   ↓  （致命失败 / 停失败 / 收尾失败 → ERROR，不进入后续任何阶段）
HEALTH_CHECK（容器已在扩展阶段收尾按依赖顺序起回，判据与时点一字不改，见 §14.12 / §14.13.3）→ UPDATE_STATUS(STOPPED) → START → COMPLETE
```

### 3.3 改动分组

| 组 | 改动 | 模块 | 新增/改动 |
| --- | --- | --- | --- |
| **A 声明层** | `GameEnhancementExtension` 新增两个 default 方法 `getDeployVersions(deployType)` / `getDeployExtensionSteps(ctx)`；**新增 7 个类型**：4 record（`DeployVersionDeclaration` / `PatchStepDeclaration` / `ScriptStepDeclaration` / `DeployExtensionContext`）+ 1 **sealed interface `DeployExtensionStepDeclaration`**（步骤集的公共上界，两个步骤 record 是其 permitted 实现，带 `kind()`）+ 2 enum（`StepKind` / `ScriptPosition`，后者 `CONTAINER` 校验期拒绝）。定义体见 §16.2（**v0.3 补 REV-4**） | `backend/plugin` | 新增（纯加法，既有实现者零改动） |
| B 补丁同步入口 | `PatchInstallService.installSync(request, listener)` + `PatchInstallProgressListener`；core 实现为直调 `PatchInstallExecutor.execute()`，**并在其外再包一层同主机互斥：复用 `TaskMutexManager` 承任务中心那个键 `PATCH_INSTALL:<hostId>`（2s 轮询 / 600s 预算 / `finally` 释放）**（**v0.3 补 REV-1**，§14.14） | `backend/plugin` + `backend/core` | 新增 default 方法 + 一个实现（含承键逻辑）；`TaskMutexManager` 与 `PatchInstallExecutor` 零改动 |
| **C 目录与校验** | 新 `DeployVersionCatalogService`（§16.3）：`read(gameCode, deployType) → CatalogView{ABSENT/EMPTY/INVALID/AVAILABLE}`，含 §8.1 全量校验 + **本设计新增五条规则 N1…N5**（判定通道绑死 `game_metadata` 表快照；模板占位符；tag 格式；`timeoutMs`/`position`；**deployType 支持集合**） | `backend/core` | 新增 |
| D 版本选择 | 纯函数 `applyVersionSelection(configInfo, catalog, selection)`（写/删/不写三态，§14.10）+ 提交期 BR-07 撞键校验（清单 = PRD 原三项，**`PLATFORM_IMAGE_TAG` 不进该清单**，其约束改由声明期保留键承担，§14.4.2）（**v0.3 订正**） | `backend/core` + `frontend` | 新增 |
| E 镜像 tag 注入 | `buildDeployConfig` 第 5.5 步，**两级门控**：该 deployType 未声明保留变量 ⇒ 完全不写；声明了 ⇒ **值一律由平台写**（命中条目带 `imageTag` 用条目值，否则用该变量的 `defaultValue`），且**仅当 `configInfo` 含 `deployVersion` 才读目录**（§14.4 / §14.4.3）（**v0.3 补 SUG-4/5**） | `backend/core` | 新增一处，条件门控 |
| **F 执行管线** | `DeployService`：`EXTENSION` 阶段插入（`:214` 之后）+ `DeployExtensionExecutor`（新类：解析步骤集 → 停实例 → 顺序执行 → 每步日志行 → **收尾调 `adapter.ensureRunningForExtension(...)`**）+ `ensureStoppedForExtension()`。（**v0.3 按 SUG-1 删除**原末项「扩展分支内跳过 `HEALTH_CHECK` 容器态探测」——该方案已被 §14.12 明确否决，留在改动分组里会把实现者引向被判否的形状） | `backend/core` | 新增类 + 改动 4 处 |
| G 日志与呈现 | `LogEntry` / `LogEntryVO` 新增 **6 个**可选字段（`stepId` / `stepIndex`+`stepTotal` / `stepLabel`+`stepType` / `stepEvent` / `elapsedMs` / **`exitCode`**，v0.3 补 REV-6）；`DeployProgressVO` 顶层新增 `stage`；`mapStageToStatus` 加 `EXTENSION → installing`；`DeployService` 进度字面量在扩展分支内条件分配 `[80,84]` + `HEALTH_CHECK` 顶层 `progress` 起点 85（§14.6 / §14.7） | `backend/core` + `backend/api` | 新增可选字段（默认 `null`） |
| **H 前端** | `DeployProgress.vue`：`level` 归一化（含 `warn → warning` 别名）、阶段带与「扩展」步骤点由 `logs[].stage === 'EXTENSION'` latch 驱动、步骤行按 ui-spec §6.2 词面渲染、**耗时先把 computed `formattedElapsedTime` 参数化为 `formatElapsed(seconds)` 再按 `max(1, round(ms/1000))` 秒渲染**（§14.6 单位口径，v0.3 补 SUG-6）；`deploy.vue`：步骤 2 版本选择控件 + 步骤 5 摘要行（P1/P2 谓词） | `frontend` | 改动 |
| I 保键 | `InstanceServiceImpl.updateInstance` 的 `copyProperties` 整表替换改合并式写入（§14.8） | `backend/core` | 改动一处（语义变化面窄） |
| **J 交付载体** | 新建 `backend/plugin-dnf-tw`（聚合 pom + `-core` JAR 子模块，**无前端**）：`DnfTwPlugin` + `DnfTwExtension`（`getGameCode() = "dnf_tw"`）+ `getDeployVersions()` 返回**未填充占位模板**（读取结果 = 空目录）+ 模板内显式标注「占位模板，不可上线」；`backend/pom.xml` `<modules>` 加一项 | `backend/plugin-dnf-tw` | **新增模块** |
| K 验收资产 | `plugin-stub`（仅声明版本目录 + 混排步骤集，无游戏语义）+ 受控补丁包/脚本夹具 + 桩游戏元数据经外置 `./games` 目录投放（不进 core resources、不进发布物） | 测试资产 | 新增（非产品模块） |
| **L 起回调用面（v0.3 新增，回应 REV-2/REV-3）** | `DeployAdapter` **新增一个 default 方法** `ensureRunningForExtension(instanceId, config)`（默认抛 `UnsupportedOperationException`，与既有 `stopServer` 同形）；`DockerComposeAdapter` 与 `LinuxGsmDockerAdapter` **各 +1 覆写**（命令与就绪判定逐类照抄各自 `DEPLOY` 已在用的形状，§14.13.3）；`DockerAdapter` / `LinuxGsmAdapter` / `AbstractDeployAdapter` **零改动**（不给空实现——返回 `true` 等于静默假装已起回） | `backend/core` | 接口 +1 方法（实现者二进制兼容）；两个适配器各 +1 方法，**既有方法一字不改** |

### 3.4 明确不改

见 §2 非目标（16 条）。其中三条最容易在实现中被顺手改掉，单独点出：**不动 `games/dnf_tw.yml`**（含其 compose 模板与镜像 tag 字面量）；**不读不写不清理坏键 `gameVersion`**；**不给 `configInfo` 加任何数据库列或表**。

## 4. 受影响组件

| 文件 / 位置 | 改动性质 | 风险 | 归属 |
| --- | --- | --- | --- |
| `backend/plugin/.../extension/GameEnhancementExtension.java`（L255 附近） | 加两个 default 方法 | 低（加法） | @BackendDev |
| `backend/plugin/.../extension/deploy/*.java`（**新增 7 类型**：4 record + 1 sealed interface + 2 enum） | 新增 | 低 | @BackendDev |
| `backend/plugin/.../service/FileAccessService.java`（不改，仅使用其 `CommandResult.exitCode`，`:235-244`） | **零改动** | — | — |
| `backend/plugin/.../patch/PatchInstallService.java` + 新 `PatchInstallProgressListener.java` | 加 default 方法 | 低；**不得**改 `install()` 签名或行为（plugin-l4d2 在用） | @BackendDev |
| `backend/core/.../patch/PatchInstallServiceImpl.java` | 实现 `installSync`（直调执行器 + **承 `PATCH_INSTALL:<hostId>` 互斥键**） | 中：必须直调执行器，不得另起链路（FR-14 红线）；**不得改 `install()` 的提交路径**（plugin-l4d2 在用） | @BackendDev |
| `backend/core/.../task/TaskMutexManager.java` | **零改动**（只注入使用其 `putIfAbsent` / `remove` / `isHeld`） | 低：本期是它的**第二个非任务中心使用方**，holder 用 `"EXT:…"` 前缀避开 `removeByTaskId` 的 DB taskId 路径（§14.14） | @BackendDev |
| `backend/core/.../adapter/DeployAdapter.java` | **+1 default 方法** `ensureRunningForExtension(instanceId, config)`（默认抛异常） | **高（接口面扩大）**：4 个实现者不必改即可编译，但新增一个「只有两类适配器能用」的能力位 ⇒ 靠声明期支持集合校验封住（§14.13.1/2、RISK-D13）。**红线**：默认实现不得改成 `return true` | @BackendDev |
| `backend/core/.../adapter/DockerComposeAdapter.java` | **+1 覆写**（`up -d` + `sleep 5s` + `ps` 判 `running`/`Up`，镜像 `:259-287` 既有形状）；既有 14 个方法一字不改 | 中：起停语义若写错会直接破坏 `HEALTH_CHECK`（V-27 逐类核对） | @BackendDev |
| `backend/core/.../adapter/LinuxGsmDockerAdapter.java` | **+1 覆写**（`up -d` + `sleep 8s` + `ps -q` 后**逐个**容器探 `.State.Running`）；**禁止复用 private `ensureContainerRunning`（`:955-993`，其停止分支走 `compose start` 且第一个容器即 `return`）**；既有方法一字不改 | 中：同上，且该类容器数不定（V-27 单独承载本类） | @BackendDev |
| `backend/core/.../adapter/DockerAdapter.java` / `LinuxGsmAdapter.java` / `AbstractDeployAdapter.java` | **零改动**（本期 EXTENSION 不支持这两类 deployType，§14.13.1） | 低 | — |
| `backend/core/.../service/DeployService.java` `:214`、`:216-221`、`:609-620`、`:860` | 插入阶段 + 条件进度 + 状态映射 | **高**：改动落在产品部署主干，回归面最大 → AC-15 必测 | @BackendDev |
| `backend/core/.../service/deploy/DeployExtensionExecutor.java`（新增） | 新增 | 中 | @BackendDev |
| `backend/core/.../service/impl/InstanceServiceImpl.java` `:153-195`（`:177`）、`:687-759`（新 5.5 步） | 合并式写入 + tag 注入 | **高**：`updateInstance` 是所有实例更新的公共路径，改语义影响面见 §12 RISK-D02 | @BackendDev |
| `backend/api/.../vo/LogEntryVO.java`（映射在 `InstanceController.java:876-897`）、`DeployProgressVO`、`DeployConfigVO` | 加可选字段 | 低（前端不读即无变化） | @BackendDev |
| `backend/core/.../service/impl/GameServiceImpl.java` `:195-231` | 目录读取与 `CatalogView` 汇入 VO | 中：不得改 `getDeployConfigs()` 的整节替换语义（16.5） | @BackendDev |
| `frontend/src/components/DeployProgress.vue` `:72-109`、日志渲染区 | 归一化 + 阶段带 + 步骤行 | 中：该组件被备份/还原等复用，见 RISK-D03 | @FrontendDev |
| `frontend/src/views/instance/deploy.vue` 步骤 2 / 步骤 5 | 版本控件 + 摘要行 | 低（新块，谓词门控） | @FrontendDev |
| `backend/pom.xml` `<modules>` | 加 `plugin-dnf-tw` | 低 | @BackendDev |
| `backend/plugin-dnf-tw/**`（新模块） | 新增 | 低；产物不得混入桩插件/夹具（AC-26 ②） | @BackendDev |
| `scripts/deploy-plugin.sh`、`start-all.sh` | 视模块数可能需登记新插件 jar | 低（核对后决定） | @BackendDev |
| `docs/design/adr/0029-*.md` / `CONTEXT.md` / `glossary.md` | 已由 `590af8d` 进远端分支 `agent/leader/chat-1cfa252af963`，实现分支**必须 merge 带入** | 低 | @BackendDev |

**不受影响（负向清单，防被读成隐含需求）**：`games/dnf_tw.yml`、`GameInstance` 实体与 `game_instance` 表、`db/schema-*.sql` 与 `db/migration/`、`InstanceQueryService.executeCommand`、`DockerAdapter` 与 `LinuxGsmAdapter`（本期 EXTENSION 不支持这两类，§14.13.1）、`AbstractDeployAdapter`（**不给 `ensureRunningForExtension` 空实现**，理由见 §14.13.2）、`DeployTaskHandler` 与任务中心（本期只借 `TaskMutexManager` 的内存键，不 submit、不建任务记录 ⇒ D-N05 仍成立）、`SecurityConfig`（无新 URI 面）、`TaskMutexManager` 自身代码、`LinuxGsmDockerAdapter.ensureContainerRunning`（private，不碰不复用）。

> **v0.3 对本清单的三处订正**（原清单被评审逐条判为不成立）：① 原「**三个适配器不改动**」**作废**——`DeployAdapter` +1 default 方法、compose 与 lgsm-docker 各 +1 覆写（§14.12 的收尾结论必须有调用面才落得了地，REV-2）；② 原「`PatchInstallExecutor` 内部逻辑」不改**保留成立**——本期只**调用**它，锁加在它的调用方，回滚结果的可见性走日志文本而非改它的回调接口（§14.6 规则 5）；③ 原「`InstanceQueryService.executeCommand` 与三个适配器」中把两者并列的写法拆开，避免被读成「因为不改 executeCommand 所以不改适配器」。

## 5. 数据与状态

### 5.1 无数据库变更（判定）

本期**不加表、不加列、不加迁移脚本**。理由：版本只落 `game_instance.config_info`（既有 JSON 列，ADR-0015 三方言均无需改）；扩展步骤与部署日志本期不落库（N-03 / BR-13 / D-N03）；插件目录不落库（16.1 的读取时合并）。⇒ `db/schema-{sqlite,mysql,postgresql}.sql` 与 `db/migration/` 零改动，也顺带避开了「MySQL/PG 无增量迁移机制」的既有缺口。

### 5.2 `configInfo` 键口径

| 键 | 本期动作 | 说明 |
| --- | --- | --- |
| `deployVersion` | 由 `applyVersionSelection` 写/删；retry-deploy 与重部署只读（**retry 会先 `uninstall` 清空宿主机 workDir ⇒ 补丁必然全量重放，见 §14.15**） | 唯一版本载体（§8.4 固定名） |
| `PLATFORM_IMAGE_TAG` | **v0.3 订正（SUG-4）：它确实会成为真实的 `configInfo` 键**，不是「只在临时 map 里」。依据：§14.4 要求声明 `imageTag` 就必须把它声明成该 deployType 的 `variables[]` 一项，而 `deploy.vue` 对**全部**变量（含 `hidden`，`:246-250` 回填 `defaultValue`）做 `...deployVariablesValues` 展开进 `configInfo`（`:713`）⇒ 向导提交即带上它。**后果可控的理由**：第 5.5 步在「该游戏声明了这个键」时**一律由平台写值**（条目 `imageTag` 或该变量的 `defaultValue`），用户 / 通用写接口提交的值**不参与任何判定、不进 `.env`**（§14.4 表「框架侧唯一新增」+ §14.4.2） | 因此 §8.4「声明只出自插件代码、无用户输入面」这句话**在本键上不再无条件成立**——它成立是因为平台不采信，而不是因为面上没有键。取值格式校验见 §14.4.1 R4 |
| `gameVersion` | 不读、不写、不清理 | D-N16 / F-11 |
| `database` / `containerWorkDir` / `serviceName` | 不动写方（适配器 `DEPLOY` 末回写） | F-15；`updateInstance` 的 `database` 回注保留 |
| `variables[].name` 各键 | 不动 | 与 `deployVersion` 共存于同一扁平 map（BR-07 撞键校验对象） |

### 5.3 三态与不变式（与 PRD §8.4.2 同口径，逐态实现落点）

| 态 | 判定 | 键 | 扩展阶段 | 实现落点 |
| --- | --- | --- | --- | --- |
| S1 | `CatalogView.availableForWizard() == false` **且** 无键 | 不写 | 不进入 | 前端不渲染（`deploy.vue`）；`applyVersionSelection` 第三支 |
| S2 | 目录可用，选默认或未选 | 不写；**若既存 → 删** | 不进入 | `applyVersionSelection` 第二支（界面端到端入口缺失，见 §14.10） |
| S3 | 目录可用且选非默认，或既存键被重放 | 写 `versionId` | 进入 | `applyVersionSelection` 第一支 → `buildDeployConfig` → `DeployExtensionExecutor` |

不变式 **`configInfo.deployVersion` 存在 ⇔ 部署目标是非默认版本** 的两个支撑点：写入方唯一（`applyVersionSelection`）+ 其它写路径不丢键（§14.8 合并式）。

### 5.4 运行态对象（内存，进程重启即丢 = L-01）

`DeployTaskStatus.logs` 的元素 `LogEntry` 增加 §14.6 的五个可选字段；`DeployProgressVO` 顶层增加 `stage`。`taskStatusMap` 的生命周期、淘汰策略、轮询频率**全不变**；新增的输出体量约束见 §8.3。

### 5.5 实例运行状态

与 PRD §9 一致，两处必须写明：① 扩展阶段期间 `run_status` 恒为 `INSTALLING(5)`（`ensureStoppedForExtension` 只调适配器、不回写 STOPPED，§14.5）；② 扩展阶段**内部**容器是停止的（FR-11 的判定点），收尾会按依赖顺序把它起回来，因此 `HEALTH_CHECK` 的判据与时点、`UPDATE_STATUS` 只写库、`START` 面对已运行容器这三条既有表现全部不变（§14.12）。**③（v0.3 补）本段全部结论只对 `docker-compose` / `linuxgsm-docker` 两类成立**——「停 → 起回」的调用面是 `DeployAdapter.ensureRunningForExtension`，两类各自的命令形状与就绪判定不同（§14.13.3）；`docker` / `linuxgsm` 两类本期**不允许声明扩展步骤**，故其运行状态语义与今天无任何差异（§14.13.1）。

## 6. 接口与契约边界

### 6.1 对外 HTTP 契约（三条，全部向后兼容）

| 接口 | 变化 | 兼容性 |
| --- | --- | --- |
| `GET /api/games/{gameId}/deploy-config?deployType=` | 响应增加 `deployVersions[]`、`versionCatalogState`、`versionCatalogReason`（§16.4） | 新增字段；老前端不读即无变化；空目录返回 `[]` 而非 `null` |
| `POST /api/instances`（向导提交） | **无新增字段**：版本以 `configInfo.deployVersion` 承载 | 载荷形状不变；提交期只新增 BR-07 撞键拒绝（400 + 可辨识原因） |
| `GET /api/instances/{id}/deploy-progress` | `DeployProgressVO.stage` 新增；`logs[]` 每行新增 **6 个**可选字段（v0.3 含 `exitCode`，§14.6） | 非扩展部署这些字段恒为 `null`/既有集合不变 |

不新增接口、不新增 WebSocket、不新增轮询通道（FR-20）。

### 6.2 SDK 契约（`backend/plugin`）

见 §16.2 的代码块。**扩展方与执行方的责任边界**：

| 责任 | 归插件（声明方） | 归主应用（执行方） |
| --- | --- | --- |
| 步骤内容与顺序 | ✅ 声明有序清单，序号自 1 | 按声明顺序执行，不重排（BR-08） |
| 幂等 | ✅（BR-06） | 不判断「是否已做过」 |
| 致命性 | ✅ 默认 `true` | 只读取，不推断不覆盖（BR-04） |
| 路径安全 | 给出相对路径 | ✅ 校验绝对路径 / `..` 越界 → 声明不合法（BR-05） |
| 超时 | 可声明 `timeoutMs` | ✅ 缺省与上下限（§15.2）、执行与判定 |
| 过程可见 | 无责任 | ✅ 日志呈现契约（§14.6） |
| 回滚 | 无责任 | ✅ 补丁走 `PatchInstallExecutor` 既有备份回滚；脚本无回滚并如实记日志（BR-14） |
| 鉴权头补丁源 | 本期不可声明 | 结构性不提供（§14.2） |
| 同主机互斥（BR-09） | 无责任（不感知） | ✅ `installSync` 承任务中心同一个内存键 `PATCH_INSTALL:<hostId>`，等待预算 600 s、`finally` 释放（§14.14）。**不得**在声明侧或调用外层再造重试 |
| deployType 是否支持扩展步骤 | 无责任（插件不必知道自己被配在哪类部署方式上） | ✅ 声明读取期按 §14.13.1 支持集合判不合法 ⇒ 整目录 `INVALID` → BR-12 处置；`ensureRunningForExtension` 的默认抛异常只是**实现缺陷的兜底**，不是给插件的契约 |
| 「起回后容器真的在跑」 | 无责任 | ✅ 收尾失败按致命处置（§14.13.3），不让 `HEALTH_CHECK` 去替它失败 |

### 6.3 日志呈现契约（对外登记物）

§14.6 即 KPI-02 / AC-03 / AC-12 / AC-16 的核对契约，**以本节为登记处**：**六个**字段集合、`stepEvent` 取值、**五条**归组与判定规则、耗时单位（`elapsedMs` 毫秒）、退出码承载位（`exitCode`）、`stdout`/`stderr` 的**非判据**地位、`stage = "EXTENSION"` 常量。UI 词面归 ui-spec §6.2（`docs/ui/MERC-3/ui-spec.md` @ `f6312ef`，S1b v0.4 在途），两者关系：契约是判据，词面是渲染。

## 7. 实现步骤

> 里程碑：**M1 SDK 与目录 → M2 执行管线 → M3 前端与契约 → M4 交付载体与验收资产**。同一里程碑内可并行，跨里程碑按依赖串行。G1 之后才开 S2a（@BackendDev 的 API 契约细化）与 S2b（@Tester 功能用例）。

### 7.1 @BackendDev — `backend/plugin`（M1）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| B-01 | 新增 **7 个类型**：`DeployVersionDeclaration` / `PatchStepDeclaration` / `ScriptStepDeclaration` / `DeployExtensionContext` / `ScriptPosition` / **`DeployExtensionStepDeclaration`（sealed interface）** / **`StepKind`**（定义体与 `kind()` 见 §16.2，v0.3 补 REV-4） | 编译通过；无 `dnf_tw` 字面量（AC-23 ③ 前提）；**两个步骤 record 是 sealed 的 permitted 实现且 `Stream.concat(patches, scripts)` 可赋给 `List<DeployExtensionStepDeclaration>`**（类型闭合即本条判据） |
| B-02 | `GameEnhancementExtension` 加两个 default 方法（返回空集合），**不改任何既有方法签名**；`getDeployExtensionSteps` 的返回类型即 sealed 上界 | plugin-l4d2 无需改动即可编译；`install()` 行为未变 |
| B-03 | `PatchInstallService.installSync` default 方法 + `PatchInstallProgressListener` | 既有 `install()` 调用点零改动 |

### 7.2 @BackendDev — `backend/core`（M1→M3）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| B-04 | `PatchInstallServiceImpl` 实现 `installSync` = **承 `PATCH_INSTALL:<hostId>` 互斥键（`TaskMutexManager.putIfAbsent`，2 s 轮询 / 600 s 预算 / `finally` 释放）→ 直调 `PatchInstallExecutor.execute()`**（请求对象引用透传） | `includePattern` 生效（V-07）；并发/重试常量未被复制；**V-26：同主机两路补丁不重叠、与任务中心提交的一路也不重叠、等满 600 s 判该步失败、异常路径后键已释放**（BR-09 / §14.14） |
| B-05 | `DeployVersionCatalogService`：读取 + §8.1 全量校验 + **§16.3 的 N1…N5 五条规则**（其中 N1/N2 的判定通道**必须绑 `game_metadata` 表快照**、与 `buildDeployConfig` 同一读法；N5 = deployType 支持集合），返回 `CatalogView` 四态 | `EMPTY` 与 `INVALID` 可区分（RISK-13）；`getDeployVersions` 抛异常归 `ABSENT` 不外泄；**V-02 补两条反例**：插件经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` **不得**让 N1/N2 判过（§14.4.1 R1）、表侧模板缺 `${PLATFORM_IMAGE_TAG` 即 `INVALID`（R3） |
| B-06 | `GameServiceImpl` 把 `CatalogView` 汇入 `DeployConfigVO`（§16.4）；**不动** `getDeployConfigs()` 的整节替换 | 无扩展声明游戏的 `deploy-config` 响应逐字段与现状一致 |
| B-07 | `applyVersionSelection` 三态纯函数 + 提交期 BR-07 撞键校验（清单 = PRD §8.4.3 原三项：`variables[].name` ∪ 三系统键 ∪ `gameVersion`；**`PLATFORM_IMAGE_TAG` 不加入该清单**——它会作为声明期保留变量合法地出现在提交载荷里，加进去等于把 AC-14 的正向路径判 400，§14.4.2） | 单测覆盖三态；**目录不可用时不在提交期 400**（§14.10 末段）；**新增反例断言**：声明了该保留变量的游戏提交带该键 ⇒ 不 400 且部署侧不采信其值 |
| B-08 | `buildDeployConfig` 第 5.5 步 `imageTag` 注入（**两级门控 + 仅当 `configInfo` 含 `deployVersion` 才读目录**，§14.4 / §14.4.3） | 未声明该保留变量的游戏 `.env` 与模板逐字节不变；**声明了的游戏：用户提交的该键值不出现在 `.env` 里**（V-15 第四核对物）；无 `deployVersion` 键时不触发任何目录读取与 SPI 调用（V-29） |
| B-09 | `DeployExtensionExecutor`：解析步骤集（16.2 解析顺序，**元素类型 = sealed 上界，按 `kind()` 分派**）→ `ensureStoppedForExtension()`（3×2s 判定，失败即致命）→ 顺序执行 → 每步日志行按 §14.6 契约**五条规则**（含 `exitCode` 与 `ROLLBACK` 记录位）→ **收尾调 `adapter.ensureRunningForExtension(...)`**（不在本类内拼 compose 命令，§14.13.2） | 单测：致命失败后续步骤不执行；非致命失败继续；`SCRIPT` 步骤终态行带 `exitCode`、`PATCH` 步骤 `exitCode == null`；`PATCH` 失败时 `ROLLBACK` 行位置符合规则 5；**executor 内无 `instanceof DeployAdapter` 分派**（V-13 代码审查项） |
| B-10 | `DeployService`：`:214` 后插阶段（`DeployExtensionExecutor` 用已解析的 adapter，§14.13.2）；条件进度 `[80,84]` + 该分支内 `HEALTH_CHECK` 顶层 `progress` 起点 85（§14.7 订正：不是「进入行」）；`mapStageToStatus` 加 `EXTENSION → installing` | 无步骤路径的进度值与阶段序列**逐值不变**（AC-15）；`HEALTH_CHECK` / `START` 代码未改一行；**`ensureRunningForExtension` 在无扩展部署中的调用次数 = 0**（V-10 新增子项） |
| B-11 | `LogEntry` / `LogEntryVO` / `DeployProgressVO` 字段扩展（§14.6 / §6.1） | 既有阶段新字段全 `null` |
| B-12 | `InstanceServiceImpl.updateInstance` 合并式写入（§14.8） | AC-27 四项逐条通过；单测注明「省略键不再等于删键」 |
| B-13 | 脚本执行安全形状：正文/URL 脚本一律**平台侧下载 → sha256 校验（声明了才校验）→ SFTP 上传到 `<workDir>/.platform-extension/E-<n>.sh` → `bash <file>` 执行 → `finally` 删除** | 命令文本里不出现脚本正文（RISK-08 缓解）；未校验通过不在宿主机留文件 |
| B-16 | **（v0.3 新增，组 L / REV-2）**`DeployAdapter` 加 default 方法 `ensureRunningForExtension`（默认抛 `UnsupportedOperationException`）；`DockerComposeAdapter` 与 `LinuxGsmDockerAdapter` 各 +1 覆写，命令与就绪判定**逐类照抄各自 `DEPLOY` 已在用的形状**（compose：`up -d` + 5 s + `ps` 认 `running`/`Up`；lgsm-docker：`up -d` + 8 s + `ps -q` 后逐个探 `.State.Running`，§14.13.3） | 两类各自 V-27 通过；`DockerAdapter` / `LinuxGsmAdapter` / `AbstractDeployAdapter` 零改动；既有 14 个方法签名与实现一字不改；**默认实现仍抛异常**（grep 判据：`return true` 不出现在该方法的默认实现里）；**不引用 private `ensureContainerRunning`** |

### 7.3 @BackendDev — 交付载体（M4）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| B-14 | 新建 `backend/plugin-dnf-tw`（聚合 pom + `-core`），`<modules>` 注册；`plugin.properties` 七键；`DnfTwPlugin extends Plugin` + `@Extension DnfTwExtension`；**不建 frontend** | `mvn clean package` 产出 jar；AC-23 ①④ |
| B-15 | `DnfTwExtension.getDeployVersions()` 返回**未填充模板**（带未填充标记，读取即过滤为空）+ 类与模板标注「占位模板，不可上线」 | `CatalogView.state == EMPTY`；全文无示例版号/URL/路径（AC-24 ⑥） |

### 7.4 @FrontendDev（M3）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| F-01 | `DeployProgress.vue:88-109` `level` 归一化：`toLowerCase()` + `warn → warning` 别名 + 校验 `success` 分支命中 | 单测：`SUCCESS`/`WARN`/`ERROR` 三色与图标各自生效（14.9） |
| F-02 | 阶段带 / 「扩展」步骤点：latch = `logs.some(l => l.stage === 'EXTENSION')`；激活态用顶层 `stage` | 无扩展部署不渲染阶段带（AC-15 / AC-24 ③） |
| F-03 | 步骤行按 ui-spec §6.2 词面渲染；**先把 computed `formattedElapsedTime`（`:72-85`，读 `elapsedTime`）参数化为纯函数 `formatElapsed(seconds)`**，步骤行按 `max(1, round(elapsedMs/1000))` 秒调用它（§14.6 单位口径，v0.3 补 SUG-6） | 词面改动不改变可核对判据（14.6 承载位分工表）；**快速步骤（<1 s）渲染为「1秒」而非「0秒」**；既有 `elapsedTime` 消费点行为不变（回归） |
| F-04 | `deploy.vue` 步骤 2 版本控件（`el-select`，P1 谓词 = 条目数 ≥ 1）+ 步骤 5 摘要行（P2 谓词，随 Leader 对 FR-04 的裁定） | **AC-01 正向判据见 V-21**（缺口期 dnf-tw 整块不渲染 = AC-24 ①②；桩游戏目录 AVAILABLE 时控件与选项必须出现）；不出现 `gameVersion` 读写 |
| F-05 | 提交载荷：非默认选择写 `configInfo.deployVersion`，默认选择省略（删键由服务层 `applyVersionSelection` 承担） | 载荷与现状差异**只多这一个键** |

### 7.5 @Tester（M2 起并行）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| T-01 | 建 `plugin-stub`（仅 `getDeployVersions` 声明 ≥2 条目 + PATCH/SCRIPT 混排）与受控夹具（本地补丁包 + 脚本正文 + 验收期可达源） | 桩声明零游戏语义；夹具不进发布物（AC-26 ②） |
| T-02 | 桩游戏元数据经外置 `./games` 投放（含 `PLATFORM_IMAGE_TAG` 占位模板），**不改 core resources** | AC-14 三核对物可跑（14.4） |
| T-03 | 按 §10 的 V 表写接口/自动化用例（**v0.3 起含 V-21…V-29 九行**）；KPI-02 写**机械核对脚本**（只读 `stepId`/`stepEvent`/`elapsedMs`/`exitCode`） | 脚本不含 `message` 文本匹配；**输出可见性核对（V-22 第二判据块）单独成表，不并入 KPI-02 的机械脚本**（§14.6 承载位分工） |
| T-04 | 回归基线：**改造前**先在 `main` 跑一次并登记（① 后端 `cd backend && mvn test` 的部署/补丁相关用例清单与通过数；② 前端 `cd frontend && npm run test:run`；③ `cd frontend && npm run e2e` 受管模式用例清单），**改造后重跑同一命令集**（§10 V-25，v0.3 补 KPI-04 的核对物） | AC-15 判定表 + KPI-04 分子/分母存档（当次采集，L-01）；**比对面不含 CSS class 与图标名**（v0.3 补 SUG-9：`level` 归一化后此前同为 `log-info` 的非 INFO 行开始变色，若把它算作回归差异会与 RISK-D03 的「缺陷修复的正当外溢」定性自相矛盾）——比对面限定为**阶段序列 / 顶层 `progress` 值 / 日志行的 `stage`+`level` 原值 / 终态**四项 |
| T-05 | **（v0.3 新增）**两类支持集合各跑一次完整带步骤部署（`docker-compose`、`linuxgsm-docker`），并对 `deployType = docker` 构造一份带步骤的目录声明 | V-27 两判据块（两类起回形状分别核对 + 集合外声明 `INVALID` 且 `ensureRunningForExtension` 零调用）；**不得**以「compose 一类过了」推定另一类 |

## 8. 非功能需求

### 8.1 耗时预算（RISK-01 的可核对化）

| 项 | 预算 | 依据 |
| --- | --- | --- |
| 停实例 | ≤ 120 s + 判定 6 s | `compose stop` 既有 `120000`（`DockerComposeAdapter:395`、`LinuxGsmDockerAdapter:379-381` 同值）+ 3×2s |
| 单 `SCRIPT` 步骤 | ≤ 600 s（缺省）/ 1800 s（上限） | §15.2 |
| 单 `PATCH` 步骤：**同主机互斥等待** | ≤ **600 s**（2 s 轮询；等满即判该步失败） | v0.3 新增（§14.14）：与执行器 SSH 预算同量级，不引入第二个数 |
| 单 `PATCH` 步骤：执行 | ≤ 600 s × 尝试数（含 2 次重试退避 5s/20s） | `PatchInstallExecutor:50-54` |
| **阶段收尾起回**（v0.3 新增） | `docker-compose` ≤ 1200 s（shell `timeout`）+ 5 s；`linuxgsm-docker` ≤ 1200 s + 8 s | 两类各自 `DEPLOY` 已在用的命令超时（compose `:259-261`、lgsm `:222-223`），收尾沿用同值，不新造 |
| 阶段最坏情况 | 120s + 6s + Σ(步骤预算 + 各步互斥等待) + 收尾预算；**无聚合上限**（见下） | 声明由插件代码固定、可静态审，故本期不加聚合闸门 |

本期**不**引入「扩展阶段总耗时上限」或步骤数上限：新增一个 PRD 未要求的闸门会把「声明很多步骤」的正常插件误杀，且没有对应的产品判定。登记为 RISK-D08。

### 8.2 并发与互斥

**四件事，分开说（v0.3 按 REV-1 改写：v0.2 这一节的「全部继承」不成立）**：

| 约束 | 本期是否成立 | 承键者 |
| --- | --- | --- |
| 全局并发闸 3 | ✅ 继承 | `PatchInstallExecutor.globalSemaphore`（`:56`、`:86`），直调 `execute()` 即在闸内 |
| 自动重试 2 次（5s/20s 退避） | ✅ 继承 | 同一执行器（`:50-51`、`:794-801`）；扩展阶段外面**不得**再套重试 |
| **每宿主机互斥** | ✅ **本期补上**（不是继承来的） | 任务中心的内存键 `PATCH_INSTALL:<hostId>`（`TaskServiceImpl:566-578` + `TaskMutexManager`）；`installSync` 用**同一个** `TaskMutexManager` 承**同一个**键 ⇒ 同时挡住另一路扩展步骤与用户手工提交的 `PATCH_INSTALL` 任务。锁粒度 = 单次 `execute()`；等待预算 600 s；`finally` 释放。详见 §14.14、V-26 |
| SSH 600 s | ✅ 继承 | `SSH_TIMEOUT_MS`（`:54`） |

其它：扩展阶段在部署 worker 线程内串行，同实例内不并发（FR-12）；**不新增线程池**、不引入任务中心（D-N05）——互斥借用的是键管理器，不是调度器。`@Async → commonPool` 双跳带来的长阻塞占用登记为 RISK-D04；互斥等待与「持锁不得跨 `SCRIPT` 步骤」的红线登记为 RISK-D14。

### 8.3 可观测与内存上限（本期新增的一条硬约束）

现状部署日志在内存中**没有任何条数或体量上限**（`taskStatusMap` 只在部署结束时成块存活），而脚本 `stdout` 体量不可控。故定义：

| 项 | 约定 |
| --- | --- |
| 单步 `stdout`/`stderr` 进日志 | 各截断至 **4000 字符**：保留头 2000 + 尾 2000，中间补一条 `stepEvent = NOTE` 行「输出已截断，共 N 字节」 |
| 完整输出 | 本期**不**落库、不上传对象存储（N-03 / D-N03）；需要全文时由声明侧脚本自行重定向到实例目录 |
| 每步必得行 | 三行（14.6），阶段级另有进入/完成/交棒三行；典型 3 步部署新增约 12–15 行，量级可接受 |
| 命令文本 | 执行与失败时记命令标识与脚本文件名（不含正文），供 RISK-08 审计 |

### 8.4 安全

| 面 | 处置 |
| --- | --- |
| 脚本注入 | 正文**永不拼进命令行**（B-13 的落文件 + `bash <file>` 形状），消除引号转义类注入面 |
| 补丁/脚本源 | 只接受 `http(s)`；声明了 `sha256` 即**先校验后落地宿主机**；`headers` 本期不可声明（§14.2） |
| 路径越界 | `targetPath` 绝对路径 / `..` → 声明不合法（BR-05） |
| 命令面扩大 | 声明仅出自插件 JAR 代码（非运行时可配），沿用宿主既有 SSH 凭证与容器控制权（§4.2「无新增权限位」）；`workDir` 下的 `.platform-extension/` 子目录仅放临时脚本，`finally` 删除 |
| **「无用户输入面」的边界（v0.3 补 SUG-4）** | 这句话对**步骤声明本身**成立，对 `PLATFORM_IMAGE_TAG` 这一**个**键**不成立**：它是 `variables[]` 一项 ⇒ 会出现在 `configInfo` 里，`PUT /instances/{id}/config` 可写任意值。三层处置把它压回成立：**① 值不采信**（第 5.5 步一律由平台写，§14.4）；**② 声明期格式校验**（R4，禁空白/换行/`${`/`}`，堵住 `.env` 行结构被改写）；**③ 不进提交期禁止清单**（§14.4.2，否则 AC-14 正向路径自毁）。定性：平台是**单管理员信任模型**，故这不是越权漏洞，而是「输入校验缺失 + 自述不准确」两项缺陷的收口 |
| 审计 | 步骤级日志可归因到声明步骤与插件；部署日志仍内存态（L-01 / BR-13）→ 本期不宣称审计能力 |
| 多数据库 | 零 schema 变更 ⇒ ADR-0015 三方言无新增负担 |

### 8.5 兼容性

`backend/plugin` 只加 default 方法 → 既有插件二进制兼容；`getDeployConfigs()` 语义不动 → 无回归面；`configInfo` 合并式写入的行为差异见 RISK-D02；前端 level 归一化影响所有使用 `DeployProgress.vue` 的流程（RISK-D03，属修复）。

**v0.3 补两条（REV-2 / REV-3）**：

| 面 | 兼容性判定 |
| --- | --- |
| `backend/core` 的 `DeployAdapter` +1 **default** 方法 | 对**实现者**兼容：4 个既有适配器与任何测试替身不改即可编译运行（与 `stopServer`、`getDeployConfigs()` 同一先例形状）；对**调用者**是新能力位，只被扩展分支调用 ⇒ 无扩展步骤的部署零路径变化（V-10 子项 + V-27 反例）。集合外适配器继承的是「抛异常」，而集合外声明在 §8.1 即不合法 ⇒ 生产路径不可达（RISK-D13 记这条封套） |
| deployType 支持集合收窄到 compose 两类 | 对现状零影响：今天没有任何游戏声明扩展步骤，收窄只是**拒绝将来可能的非法声明**；对已按 PRD FR-11 字面理解的读者是一次口径明确化，须回写（D-P15） |

## 9. 迁移与回滚

### 9.1 迁移

| 项 | 结论 |
| --- | --- |
| 数据库 | **无**：零表、零列、零迁移脚本（§5.1）⇒ 三方言（ADR-0015）与 `db/migration/` 只覆盖 SQLite 的既有缺口都不被触碰 |
| 存量实例 | 零影响：`configInfo` 里没有 `deployVersion` ⇒ 恒 `S1`；`gameVersion` 坏键不清理（D-N16） |
| 上线顺序 | ① 主应用（`backend/core` + `api` + `plugin`）随 `scripts/start-all.sh` 重启 → 此时无任何插件声明扩展步骤，行为与今天逐字相同；② `plugin-dnf-tw` 热部署——**点名走「`--jar` + env 覆盖」这一条路，不改脚本**（v0.3 补 SUG-8）：`cd backend && mvn -pl plugin-dnf-tw/plugin-dnf-tw-core -am install -DskipTests` 后执行 `PLUGIN_ID=plugin-dnf-tw JAR_NAME=plugin-dnf-tw-core-1.0.0.jar bash scripts/deploy-plugin.sh --jar backend/plugin-dnf-tw/plugin-dnf-tw-core/target/plugin-dnf-tw-core-1.0.0.jar`。**为什么是这条**：脚本的 `FRONTEND_DIR` / `PLUGIN_MODULE` 硬编码 l4d2（`:26-27`），只有 `PLUGIN_ID` / `JAR_NAME` 可 env 覆盖（`:29-30`），而 `--jar`（`:44`、`:53-57`）**整段跳过构建分支** ⇒ 恰好绕开两处硬编码（本插件无前端、不需要脚本代打 JAR）；卸载/覆盖/加载三步全部按 `PLUGIN_ID`+`JAR_NAME` 走（`:110-124`、`purgeTasks=false` 沿用）。**不选「参数化脚本」**：那要动 l4d2 在用的既有脚本，属本期范围外，且 `--jar` 路线零改动即可用。**B-14 完成判据含本行**（交付日不得卡在这一步）；③ 桩插件与夹具只出现在验收环境，不进 `plugins/`（AC-26 ②） |
| 分支与 ADR 载体 | 实现统一走 `release/MERC-3-deploy-extension-steps`；`docs/design/adr/0029-*.md` 与 `CONTEXT.md` / `glossary.md` 需从 `agent/leader/chat-1cfa252af963 @ 590af8d` **merge 带入**（远端可读） |
| 配置项 | 本期不新增任何 `game-platform.*` 或 `plugin.*` 配置开关 |

### 9.2 「关闭」手段（不加特性开关的替代说明）

| 新行为 | 如何关闭 |
| --- | --- |
| 扩展阶段执行 | 天然开关：**无插件 / 卸载插件 / 目录为空** ⇒ `ABSENT`/`EMPTY` ⇒ 不进阶段（dnf-tw 本期就恒在此态） |
| `imageTag` 注入 | 两级门控（§14.4 v0.3 订正）：该 deployType 的表侧 `variables[]` 不声明 `PLATFORM_IMAGE_TAG` ⇒ 完全不写；声明了 ⇒ 值由平台写（条目 `imageTag` 或该变量 `defaultValue`） |
| **deployType 支持集合（v0.3 新增）** | 不是开关，是**校验规则 N5**：集合外 deployType 带步骤或 `imageTag` 的目录判 `INVALID` → BR-12。放进集合 = 将来某类的收口形状被逐类验证过（本期两类），移出集合 = 一行常量 |
| **收尾 `ensureRunningForExtension`（v0.3 新增）** | 无独立开关：只被扩展分支调用 ⇒ 「无插件 / 目录空 / 声明不合法」三种关闭手段任一成立即永不执行。它的默认抛异常**不是**一个可关的东西，而是集合外路径不可达性的兜底断言（RISK-D13） |
| 合并式写入（§14.8） | 不提供开关——它是 BR-16 的正确性修复，留开关等于留一条能静默丢版本键的路 |
| `level` 归一化（§14.9） | 同上，属缺陷修复 |

### 9.3 回滚

| 场景 | 回滚后状态 | 诚实说明 |
| --- | --- | --- |
| 主应用回退到旧 jar | `EXTENSION` 阶段消失；`deployVersion` 成为旧代码不读的未知键（无害留存）；`deployVersions`/`stage` 等新字段随旧 VO 消失 | **已落地的补丁文件不会随代码回滚而复原**（BR-14 ②③：无反向补偿、脚本无回滚）。要把实例退回默认版本，只有按既有方式重新部署一个默认版本实例 |
| 卸载 `plugin-dnf-tw` | dnf-tw 目录 `ABSENT`；无键实例照常默认版本部署 | 已写 `deployVersion` 的实例（本期仅桩插件承载）重部署会被 **BR-12 拦截**——这是设计行为，不是回滚缺陷；恢复路径见 PRD §12「向导不展示但键已存在」行 |
| 单步失败 | `PatchInstallService` 自动回滚该步目标路径（备份在 `<resolvedPath>/.patch_backup/<ts>/`，保留 5 份） | 前序已成功步骤与所有脚本副作用一律保留（BR-14）；平台不提供任何恢复控件（N-08） |

## 10. 验证计划

> 每条都必须是**执行者能独立跑并独立判定**的动作。缺口期「谁承载」一律查 PRD §11.1；本表只补「怎么核对」。

| # | 对象 | 方法 | 判据 | 对应 |
| --- | --- | --- | --- | --- |
| V-01 | 目录四态 | 单测 `DeployVersionCatalogService`：无插件 / 抛异常 / 0 条目 / 含非法条目 / 合法 | `ABSENT` / `ABSENT` / `EMPTY` / `INVALID(+reason)` / `AVAILABLE`；`EMPTY` **不**产生「不合法」文案 | RISK-13、AC-24 ③ |
| V-02 | §8.1 全量校验 + 三条新规则 | 单测逐规则一条非法样本 | 任一不合法 ⇒ 整目录 `INVALID`，无「跳过该条继续」的部分采纳 | AC-20、§14.4、§15.2 |
| V-03 | 三态与键 | 单测 `applyVersionSelection` 三支 + 提交期撞键 | S1 不写、S2 删既存键、S3 写且值精确等于 `versionId`；撞禁止清单 ⇒ 400 且原因可辨识；**目录不可用不在提交期 400** | AC-19、AC-22（服务层）、AC-20 |
| V-04 | 步骤集串行与致命性 | 集成（桩插件 + 夹具）：`PATCH#1` 成功 → `SCRIPT#2` 非致命失败 → `PATCH#3` 致命失败 | 严格声明序、无并发；#2 记 `WARN` 后继续；#3 后无第 4 步；部署失败、`ERROR`、未进 `START` | AC-06、AC-08、AC-10、AC-21 |
| V-05 | 补丁回滚边界 | 同上，比对 `PATCH#1` 落位结果与 `#2` 文件改动 | `#3` 目标路径回到改动前；`#1` 与 `#2` 的改动一律保留 | AC-09、AC-21、BR-14 |
| V-06 | `sha256` 不符 | 集成 | 步骤失败、按致命性处置、日志原因段指名期望/实际 | AC-11 |
| V-07 | `includePattern` 真实生效 | 集成：带多成员的包 + 单一 glob 声明 | 只有匹配成员落位（证明同步入口未被 payload 截断） | §14.2 行 2、决策 6 |
| V-08 | 日志呈现契约 | **机械核对脚本**：只读 `logs[].{stage,stepId,stepEvent,elapsedMs}` | 每条 `stage == "EXTENSION"`；每 `stepId` 恰一 `START` + 恰一终态且终态 `elapsedMs != null`；比例 = 100%；**脚本内不得出现 `message` 匹配** | AC-03、AC-16、KPI-02 |
| V-09 | `level` 归一化 | 组件单测 + 目视 | `SUCCESS`/`WARN`/`ERROR`/`INFO` 四类各自的 class 与图标不再同色同图标 | §14.9、AC-08/AC-10 界面侧 |
| V-10 | 进度序列 | 抓 `deploy-progress` 全量轮询样本两条：无扩展部署 vs 有扩展部署 | 无扩展：与改造前逐值相同（含 band 插值）；有扩展：单调不减、扩展占 `[80,84]`、`COMPLETE == 100` | AC-15、§14.7、BR-10 |
| V-11 | 停实例语义 | 集成：`docker inspect` 采样 | 第一条步骤行的时间戳之后容器 `Running == false`；停实例失败注入时部署 `ERROR` 且**零步骤行** | AC-04、§14.5、ui-spec 态 S |
| V-12 | 健康判定路径 | 集成：两条部署各抓全量轮询样本 | **有扩展步骤**：`HEALTH_CHECK` 行存在且**通过**（容器已在扩展阶段收尾按依赖顺序起回），其后 `UPDATE_STATUS` / `START` / `retryHealthCheck` 与今天逐字同形；**无扩展步骤**：整条序列不含 `EXTENSION` | §14.12、AC-04、N-06/BR-10 |
| V-13 | 脚本安全形状 | 代码审查 + 日志核对 | 命令文本不含脚本正文；未通过校验的下载不在宿主机落地；`finally` 删除临时文件 | BR-05、RISK-08、§8.4 |
| V-14 | 输出截断 | 集成：产超大 `stdout` 的脚本 | 头 2000 + 尾 2000 + 一条 `NOTE`「输出已截断，共 N 字节」；日志体量受控 | §8.3 |
| V-15 | `imageTag` 三核对物 | 集成（桩游戏 + 外置元数据） | 模板逐字节不变 ∧ `.env` 中 `PLATFORM_IMAGE_TAG` 精确等于声明值 ∧ `docker compose config` 渲染出 `<repo>:<tag>` | AC-14、§14.4 |
| V-16 | BR-16 保键 | 接口 + 界面双跑 AC-27 (a)(b)(c)(d) | (a) 键值精确不变；(b) 键保留；**(c) 该入口返回成功**（非「失败也算过」）；(d) 三次之后重部署均进扩展阶段交付同一版本 | AC-27、§14.8 |
| V-17 | 无专用分支 | 全量构建 → 启动 → 插件清单 → 读目录 → `grep -rn '"dnf_tw"' backend/core/src/main/java --include=*.java` | 命中数 0（口径见 AC-23 ③：必须带引号）；模块无前端产物 | AC-23 |
| V-18 | 证据归属 | 验收记录核对 | 桩/夹具结果未被登记为 AC-05、KPI-01、KPI-03；发布物不含桩；dnf-tw 目录仍为未填充模板 | AC-25、AC-26、BR-15 |
| V-19 | 通用性 | 同一份 `core` 构建物，先接桩插件跑通 V-04…V-14，再接 `plugin-dnf-tw`（空目录）跑 V-01 | 期间 `core/` 零改动 | AC-25、G-01 |
| V-20 | dnf-tw 缺口期默认路径 | 走完 5 步向导并部署，对照 AC-15 checklist | 无版本控件、载荷无键、无扩展行、结果与改造前逐项一致、无任何示例值 | AC-02、AC-24 |

## 11. 需求追溯

> 状态列 = 本设计交付后该 AC 的**可验收性**；「承载方」= PRD §11.1 归属行（缺口期判定基准）。带 ↓↑ 的是本设计改动过的归属，逐条在 §13 列给 Leader 回写。

| AC | 承载方 | 本期状态 | 设计落点 |
| --- | --- | --- | --- |
| AC-01 | 桩插件+夹具 | 可验收 | §16.4 GET、F-04、V-20 反例 |
| AC-02 | dnf-tw 默认路径 | 可验收 | B-08 门控、F-05、V-20 |
| AC-03 | 桩插件+夹具 | **可验收**（契约已定稿，脱离「不可测」） | §14.6、B-09/B-11、V-08 |
| AC-04 | 桩插件+夹具 | 可验收 | §14.5 停止语义、§14.12、V-11 |
| AC-05 | dnf-tw 真实资料 | 不可测（L-02，不变） | — |
| AC-06 | 桩插件+夹具 | 可验收 | 16.2 解析顺序、V-04、§14.6 规则 3 |
| AC-07 | 桩插件+夹具 | 可验收 | B-07、retry-deploy 读 `configInfo` |
| AC-08 | 桩插件+夹具 | 可验收 | B-09、F-01、V-04/V-09 |
| AC-09 | 桩插件+夹具 | 可验收 | B-04（执行器内建回滚）、V-05 |
| AC-10 | 桩插件+夹具 | 可验收 | B-09 `fatal=false` 继续 + WARN、V-04 |
| AC-11 | 桩插件+夹具 | 可验收 | B-04/B-13 sha256、V-06 |
| AC-12 | 桩插件+夹具 | 可验收 | B-13（宿主机 + exitCode 判定）、V-04 |
| **AC-13** | 第一行 → **第五行** | **本期不验收**（14.5 判不合法，`container` 移出本期） | — |
| **AC-14** | 第五行 → **第一行** | **转可验收**（14.4 给出机制，载体 = 桩游戏外置元数据） | §14.4、B-08、V-15 |
| AC-15 | dnf-tw 默认路径 | 可验收（门控式改动的直接受益者） | §14.7 无步骤分支、B-08/B-10/B-12、V-10 |
| AC-16 | 桩插件+夹具 | **可验收**（契约 + F-02/F-03） | §14.6、§14.11、V-08 |
| ~~AC-17~~ | 表外豁免 | 废弃编号，不属验收对象 | — |
| AC-18 | 桩插件+夹具 | 可验收 | 16.2 解析顺序 ①② |
| AC-19 | 桩插件+夹具 | 可验收 | B-07、§5.2、V-03 |
| AC-20 | 桩插件+夹具 | 可验收（**前提保护：提交期不得改判为 400**） | §14.10 末段、B-05、V-02 |
| AC-21 | 桩插件+夹具 | 可验收 | V-05（回滚/保留边界） |
| **AC-22** | 第一行 | **服务层可验收；界面端到端前提不成立**（14.10，待 Leader 裁 A/B；推荐 B） | B-07 |
| AC-23 | 本期构建产物 | 可验收 | B-14/B-15、V-17 |
| AC-24 | dnf-tw 默认路径 | 可验收 | B-15 `EMPTY`、V-01、V-20 |
| AC-25 | 桩插件+夹具 | 可验收 | T-01、V-19 |
| AC-26 | 本期构建产物/验收记录 | 可验收 | K 边界、V-18 |
| AC-27 | 桩插件+夹具 | 可验收（合并式写入天然满足 (c)，无「恒失败」风险） | §14.8、B-12、V-16 |

**FR / BR 侧收口点**：FR-05（两入口 + 唯一解析顺序 16.2）、FR-12（同步直调 §14.1）、FR-14（同执行器，未另起链路）、FR-20（§14.6 契约）、BR-07（禁止清单 + `PLATFORM_IMAGE_TAG`）、BR-11（条件门控 §14.7 / B-08）、BR-14（回滚边界如实记日志，不加反向补偿）、BR-16（§14.8）、BR-15（K/V-18 三不得）。

## 12. 风险与边界

| # | 风险 | 影响 | 处置 |
| --- | --- | --- | --- |
| RISK-D01 | **`HEALTH_CHECK` 与停实例时点相冲**（§14.12）——若按字面同时满足 FR-10/FR-11/BR-10，任何带扩展步骤的 compose 部署必然在健康检查处失败 | 阻断级：AC-03/04/05/08 全灭 | 已定判定：**扩展阶段收尾按依赖顺序把容器起回来**（复用既有 `up -d` 形状），`HEALTH_CHECK` 的判据、动作与时点一字不动 ⇒ 不触碰 N-06/BR-10，也不改无步骤分支。成本与「后移探测」方案相同（都是起停各一次） |
| RISK-D11 | **`compose start` 不处理 `depends_on` 顺序**：dnf-tw 的 compose 项目含 MySQL 与多个服务，`DockerComposeAdapter.start` 走 `compose -p … start`（`:365-379`），只有 `up` 尊重依赖顺序。今天这一步是 `up -d` 之后的空操作，本期停实例后若由它来真实启动，首启可能早于数据库就绪 | 表现为「部署成功但版本没生效」——G-02 最坏的失效形态 | **与 RISK-D01 同一条解法收口**：起回容器由扩展阶段收尾用既有 `up -d`（`:260`，DEPLOY 已在用、尊重依赖）完成，`START` 沿用今天「已运行 → 空操作 → 复检」的行为。**禁止**把 `DockerComposeAdapter.start()` 全局改成 `up -d`（会改既有语义，违反 AC-15 / N-06） |
| RISK-D02 | `updateInstance` 从整表替换改合并：任何依赖「省略即删除」隐含语义的调用方会失效 | 中：仓内核对只有配置管理表单与通用接口两条路径，而该隐含语义本身正是 F-18 判定的缺陷 | 合并范围只在 `configInfo` 一个字段；单测 + V-16 四项；不改 `create`。缺口期 dnf-tw 恒无键，风险面实际落在桩插件承载的实例 |
| RISK-D03 | `DeployProgress.vue` 归一化会外溢到备份/还原等复用该组件的流程（非 INFO 行**开始**变色） | 低（属缺陷修复的正当外溢） | @Tester 目视回归一次；不改后端取值集合 |
| RISK-D04 | `deployAsync` 的 `@Async → ForkJoinPool.commonPool` 双跳（`:318-332`）叠加本期长阻塞步骤 | 并发多实例部署时可耗尽 commonPool，拖慢其它异步任务 | 本期不改部署主流程（D-N05）；上线后按 KPI/实测评估专用线程池（另立需求） |
| RISK-D05 | `install()` 异步路径仍丢 `headers`/`includePattern`（`PatchInstallServiceImpl:47-56`） | plugin-l4d2 若开始依赖这两字段会静默失效 | 本期不修（无使用方依赖，PRD 亦未要求）；在 SDK 注释处标出「需 `includePattern` 请用 `installSync`」，把坑写在脸上 |
| RISK-D06 | 脚本超时只保证「不再等待」，不保证远端进程已终止（§15.3） | 挂死脚本可能在宿主机继续跑，与重放叠加产生竞态 | 日志明示 + 声明侧幂等（BR-06）+ 本期不引入 kill 台账 |
| RISK-D07 | ADR-0008 通道不对称：`getDeployConfigs()` 的声明进不了 `buildDeployConfig` 的部署配置（16.1） | 后来者按「插件声明即生效」理解会做出「向导看得见、部署看不见」的功能 | 本期不碰该通道（版本目录走 `getDeployVersions`）；建议另立 Issue（含补测试与文档），交 Leader 裁 |
| RISK-D08 | 扩展阶段无聚合耗时/步骤数上限 | 一个声明很多慢步骤的插件可长期占住部署 worker | 有意为之（不加 PRD 未要求的闸门）；单步已有上下限（§15.2）与体量上限（§8.3） |
| RISK-D09 | AC-22 / §8.4.2 S2 的删键无真实界面入口（14.10） | 「换回默认版本」只能靠新建实例；`deployVersion` 一旦写入，同实例上无界面手段解除 | 待 Leader 裁 A/B；推荐 B（不扩范围），并把该事实回写 PRD 而非留在聊天记录 |
| RISK-D10 | 桩插件/夹具结果被误当 dnf-tw 真实版本通过 | 违反 BR-15「三不得」，验收结论失真 | 流程性防线：V-18 记录核对 + 夹具不进 `plugins/` + AC-05/KPI-01/KPI-03 在验收记录里显式写「不可测 + L-02 因」 |
| 边界 | 本期结束后，dnf-tw 想跑非默认版本**仍需人工改文件** | 诚实结论（PRD L-02 ③）：本期消除的是「平台没这个能力」，不是「dnf-tw 还要手工干」 | 真实资料填充即生效（BR-15 ④），框架与声明接口都不需要再改 |

## 13. 待决事项

### 13.1 须 Leader 下派 @ProductManager 回写 PRD 的清单（本设计不自行改 PRD）

| # | 条目 | 回写内容 | 来源 |
| --- | --- | --- | --- |
| D-P01 | AC-13 / FR-08 / FR-11 / §8.3 / §12 / RISK-05 | `position = container` 移出本期（取值只 `host`；声明即不合法）；AC-13 自 §11.1 第一行移入第五行记「本期不验收」；决策 7 的条件性收缩记修订记录 | §14.5 |
| D-P02 | §14.2（增第 9 行待改依赖）+ §9 状态机表 | 登记新事实：FR-11 停实例与既有 `HEALTH_CHECK`（探测 `.State.Running`）时点相冲，本期由「扩展阶段收尾按依赖顺序起回容器」收口；§9「进入扩展阶段」行补一句「阶段收尾容器恢复运行，故 `HEALTH_CHECK` 语义与时点不变」。**本项不改动决策 1–9，也不触碰 N-06/BR-10** | §14.12、RISK-D01/D11 |
| D-P03 | §8.3 `timeoutMs` 行、§12「脚本超时」行、§16.2 OP-04 | 缺省 `600000`、合法区间 `[1000, 1800000]`、越界即声明不合法；OP-04 标已关闭 | §15.2 |
| D-P04 | AC-14 与 §11.1 第五行 | 前提成立 → AC-14 转入第一行（框架类可验收）；RISK-12 关闭 | §14.4 |
| D-P05 | §8.4.3 禁止清单 + §8.1 校验内容 | 增保留键 `PLATFORM_IMAGE_TAG`；增两条蕴含规则（`imageTag ⇒ 该 deployType 声明该保留变量`；`timeoutMs` 区间） | §14.4、§15.2 |
| D-P06 | §14.2 增「`level → 视觉映射`」行（Leader 已承诺随 v0.6 回写）+ FR-20/§8.5 | 判据定稿为前端归一化，后端取值集合不变 | §14.9 |
| D-P07 | AC-22 / §8.4.2 S2 / §12 恢复路径 | 裁 A（新增入口，扩范围）或 B（服务层验收 + 界面端到端记前提未成立）；B 为推荐 | §14.10、RISK-D09 |
| D-P08 | FR-22 / §5.1 第 3 项 / F-08 / §14.1 | 「通过 ADR-0008 声明接口」→「通过 ADR-0008 声明**体系**」，并把 16.1 的通道不对称登记为事实 | §16.5 |
| D-P09 | 桩承载与 KPI-02 生效前提 | OP-04 已定稿、呈现契约已登记 ⇒ KPI-02 自此可考核（不再挂「不可测」）；§16.2 OP-04 行改已关闭 | §14.6、§15 |

### 13.2 不在本设计权限内、也不阻断实现的三项

- `getDeployConfigs()` 通道缺陷（RISK-D07）是否另立 Issue、何时修 → Leader 派 / 人类 Owner；
- 「实例详情 → 配置管理」表单该展示什么（OP-07）→ 人类 Owner（Leader 裁定 2 已收口本期下限，本期只落 §14.8 的保键）；
- `ui-design-spec.md` §3.3「4 步向导」与代码 5 步不一致、`--platform-accent` / `--platform-accent-soft` 无定义 → Leader 已判本期不修，是否另立 Issue 交人类 Owner。**本设计已在原型口径上取 `--platform-cyan`，不新增色值。**

### 13.3 本设计已关闭、不再留开口的三项

`deployVersion` 键名不改（§8.4 固定，无「最终名待定」）；OP-04 已拍板（§15.2）；三条硬判定（AC-14 / AC-13 / 呈现契约）逐条给了结论且**没有一处写「实现时再看」**。

## 14. §14.2 待改依赖逐行结论（十四条 + 逐行状态收口，无留空）

### 14.0 结论速览

| # | 待改依赖 | 归属 | **结论** |
| --- | --- | --- | --- |
| 1 | 异步 → 阻塞桥接 | @Architect（RISK-03） | **给**：不走 `install()`；SDK 新增同步入口 `installSync(request, listener)`，core 侧直调 `PatchInstallExecutor.execute()`（本就是阻塞 + 回调），零轮询、零任务中心 |
| 2 | `includePattern` / `headers` 透传 | @Architect / @BackendDev（RISK-02） | **给**：取 RISK-02 两个合法选项中的「改用同链路内执行」——同步入口传请求对象引用，不经 payload 序列化，`includePattern` 自然生效；`headers` 本期结构性不可声明；异步路径的丢键缺陷本期**不修**（本期无使用方受影响） |
| 3 | 容器内脚本执行通道（exitCode / timeoutMs） | @Architect / @BackendDev（F-16） | **本期不改**：行 5 判 `position = container` 不合法 ⇒ 容器脚本通道本期零使用方，F-16 的两个缺口不再阻塞任何条目；登记为「`container` 恢复时的前置」 |
| 4 | **硬判定①** `imageTag` 落位机制 | @Architect | **给得出 ⇒ AC-14 转可验收**。机制 = 保留变量 `PLATFORM_IMAGE_TAG` 经既有 `.env` 生成链注入（compose 原生插值，不新增渲染器），注入点 = `buildDeployConfig` 而非扩展阶段（时点决定，见 14.4）。dnf-tw 本体不受惠（不改 `dnf_tw.yml`），由桩游戏外置元数据承载验收 |
| 5 | **硬判定②** 停实例 + `position = container` 合法性 | @Architect | **判不合法 ⇒ AC-13 移出本期**（自 §11.1 第一行转入第五行），`position` 本期取值只有 `host`，决策 7 的「容器内为步骤级可选项」随之收缩；属需求范围收缩，须回写 PRD（FR-08 / FR-11 / §8.3 / §12 / AC-13 / RISK-05）并记修订记录 |
| 6 | **硬判定③** §8.5 日志呈现契约三项 | @Architect | **定稿并登记**：① 步骤标识 = `LogEntryVO` 新增结构化字段 `stepId`（归组主键）+ `stepIndex`/`stepTotal`/`stepLabel`/`stepType`/`stepEvent`；② 耗时 = 同 VO 的 `elapsedMs`，单位毫秒（界面渲染文本不是核对对象）；③ 归组 = 同 `stepId` 归一步，「齐备」＝恰一 `START` + 恰一终态行且终态行 `elapsedMs != null`。**（v0.3 补齐两处契约缺口**：新增第六字段 `exitCode`（FR-15 的退出码有承载位了）、规则 4（`exitCode ≠ 0` ⇔ `FAILURE`）与规则 5（`ROLLBACK` 行的机械判据），并把 `stdout`/`stderr` 显式排除出机械判据、只作可见性核对——见 §14.6 的「承载位分工」表） |
| 7 | 扩展阶段进度百分比区间 | @Architect | **条件分配**：无扩展步骤的部署**一个数字都不动**（BR-11 / AC-15 零风险）；有步骤时扩展阶段占 `[80, 84]`，该分支内 `HEALTH_CHECK` 的**顶层 `progress` 值**由 80 改报 85（v0.3 订正：不是「进入行」，见 §14.7），`COMPLETE = 100` 与其余阶段不变 |
| 8 | `configInfo` 覆盖式写入丢键 → BR-16 手段 | @Architect 定手段 / @BackendDev 落地 | **取「合并式写入」**：`updateInstance` 的整表替换改为「取库中既有值 → 逐键合并 → 本次载荷覆盖」。不需要任何「拒绝写入」分支即满足 AC-27 (a)(b)(c)(d)，配置管理入口照常成功 |
| 9 | （S1b 门禁新增）`level → 视觉映射` 失效 | @Architect | **给**：前端归一化（`DeployProgress.vue:88-109` 先 `toLowerCase()` + 补 `warn → warning` 别名 + 补 `success` 分支）。后端 `level` 取值集合是 PRD §8.5 已固定口径，不改后端 |
| 10 | （本设计新发现）AC-22 / §8.4.2 S2 的界面前提不成立 | 交 Leader 裁定 | **登记**：部署向导只创建新实例，既有实例的重部署入口不接受版本改选 ⇒ 「在向导改选默认版本并重新部署 → 删键」这条路径今天不存在。给出 A/B 两方案与推荐（B），见 14.10 |
| 11 | （本设计新发现，**阻断级**）FR-11 停实例 与 `HEALTH_CHECK` 探测容器运行态相冲 | @Architect 判定 + 登记 | **给判定**：扩展阶段收尾以既有 `up -d` 形状把容器**按依赖顺序起回**，`HEALTH_CHECK` 的判据、动作、时点一字不改 ⇒ 不触碰 N-06/BR-10，PRD 正文无需改（只建议登记这一事实，D-P02）；顺带修掉 `compose start` 不处理 `depends_on` 的隐蔽失效（RISK-D11）。见 14.12（**v0.3 修正：该结论对三类容器适配器同时成立，调用面与适用范围由 14.13 收口**） |
| 12 | （ArchReviewer REV-1）BR-09 的每主机互斥**并未被同步入口继承** | @Architect（Leader 裁定取①） | **给**：互斥的真实承担者是任务中心的内存键 `taskType + ":" + scopeKey` = `PATCH_INSTALL:<hostId>`，绕开任务中心即绕开它。本期在 `installSync` 内用**同一个** `TaskMutexManager` 承同一个键（2 s 轮询、600 s 等待预算、`finally` 释放、锁粒度 = 单次 `execute()`），既补回 BR-09 又不引入任务中心、不改部署主流程（不违反 D-N05）。临时路径实例命名空间（③）本期不授权，登记为后续增量。见 14.14 |
| 13 | （ArchReviewer REV-2 + REV-3）收尾动作**没有可调用的面**，且「停实例 → 必挂」是**三类**容器适配器共同的问题 | @Architect（Leader 裁定按 FR-11 字面收口） | **给**：① `DeployAdapter` 新增**一个 default 方法** `ensureRunningForExtension(instanceId, config)`（与既有 `stopServer` 同形），compose / linuxgsm-docker 各覆写，其余适配器继承「抛异常」默认值 ⇒ 三个适配器不再整体不进改动清单（§4 负向清单同步）；② 本期 **EXTENSION 支持集合 = {`docker-compose`, `linuxgsm-docker`}**，集合外（含 plain `docker`）带步骤或 `imageTag` 的声明**即不合法**，走 §8.1 → BR-12；③ **逐类**给出「起回」命令形状与就绪判定（两类的命令与判据都不同）。见 14.13 |
| 14 | （ArchReviewer REV-7①）PRD 点名交 @Architect 的 **RISK-09**（retry-deploy 先 `adapter.uninstall`）| @Architect | **给**：`retryDeploy` 确实先 `uninstall`，而 compose 类的 `uninstall` = `down` + **`rm -rf <workDir>`** ⇒ retry = 干净重跑，**补丁必然全量重放**；故 BR-14 的「前序已成功步骤改动保留」**只在当次部署内成立，不跨 attempt**（v0.3 写死，防误读）。`configInfo` 那半边核对为**安全**（`DEPLOY` 末回写是既有值的拷贝再 put，`deployVersion` 不会被冲掉）。见 14.16 |

### 14.0.1 逐行状态收口（v0.3 新增，供 PRD §14.2 与下游按此读，不再把已判定项当开环）

> 本节是 §13.1 D-P14 的核对对象：PRD §14.2 原八行 + 本文新增六行，**每行都给状态与证据指向**。回写 PRD 时以本表为准逐行收口，不得保留「待定调」字样而不指向证据。

| 行 | 待改依赖 | 状态 | 证据（本设计节号）| 回写项 |
| --- | --- | --- | --- | --- |
| 1 | 异步 → 阻塞桥接 | **已关闭**（含对 PRD 该前提的事实修正） | §14.1、§14.14 | D-P08、D-P16 |
| 2 | `includePattern` / `headers` 透传 | **已关闭**（`includePattern` 生效；`headers` 结构性不可声明；异步路径丢字段本期不修 → RISK-D05） | §14.2、V-07 | 无需回写（RISK-02 的两个合法选项取其一） |
| 3 | 容器内脚本执行通道（exitCode / timeoutMs） | **本期不适用**（因行 5 判 `container` 不合法 ⇒ 零使用方；恢复 `container` 的三项前置已登记） | §14.3、§14.5 末行 | D-P01（含恢复前置） |
| 4 | `imageTag` 落位机制 | **已关闭**（机制给出 ⇒ AC-14 转可验收；v0.3 补判定通道绑定与取值由平台控制） | §14.4、§14.4.1、V-15 | D-P04、D-P05（v0.3 扩写）、D-P13 |
| 5 | 停实例 + `position = container` 合法性 | **已关闭**（判不合法；AC-13 移出本期，属范围收缩须人类 G4 复核） | §14.5、§14.5.1 | D-P01、D-P12 |
| 6 | §8.5 日志呈现契约三项 | **已关闭**（六字段 + 五条规则，v0.3 补 `exitCode` 与 `ROLLBACK` 判据） | §14.6、§6.3 | D-P09、D-P12 |
| 7 | 扩展阶段进度百分比区间 | **已关闭**（条件分配；v0.3 把「进入行」订正为「顶层 `progress` 值」） | §14.7、V-10 | 无需回写（PRD 未规定数值区间） |
| 8 | `configInfo` 覆盖式写入丢键 → BR-16 手段 | **已关闭**（合并式写入，无需拒绝分支） | §14.8、V-16 | 无需回写（BR-16 的两个许可手段之一） |
| 9 | `level → 视觉映射` 失效（S1b 门禁转来） | **已关闭**（前端归一化，含 `warn ≠ warning` 补充事实） | §14.9、V-09 | D-P06 |
| 10 | AC-22 / S2 删键缺真实入口（本文新发现） | **已关闭**（Leader 裁 B：服务层 + 单测验收，界面端到端记「前提不成立」） | §14.10、V-03 | D-P07 |
| 11 | 停实例与 `HEALTH_CHECK` 时点冲突（本文新发现，阻断级）| **已关闭**（扩展阶段收尾起回容器；v0.3 补齐调用面与适用范围） | §14.12、§14.13 | D-P02、D-P15 |
| 12 | BR-09 每主机互斥未被同步入口继承（ArchReviewer REV-1） | **已关闭**（同步入口自行承 `PATCH_INSTALL:<hostId>` 键，与任务中心互斥同源） | §14.14、V-26 | D-P16（事实修正）、RISK-D14 |
| 13 | 收尾动作缺可调用面 + 适用范围未钉（ArchReviewer REV-2/REV-3） | **已关闭**（`DeployAdapter` 一个 default 方法 + 两类逐形状收口；支持集合 = compose 两类，集合外声明不合法） | §14.13、V-27 | D-P15 |
| 14 | RISK-09 retry-deploy 与扩展阶段的先后关系（PRD 点名交 @Architect） | **已关闭**（retry-deploy = `uninstall`（含 `rm -rf workDir`）+ 全量重部署 ⇒ 补丁必然重放；`configInfo` 半边核对为安全） | §14.16、RISK-D12、V-28 | D-P10 |

### 14.1 行 1：异步 → 阻塞桥接（RISK-03）


**事实修正（PRD §14.2 的缺口证据需更新）**：`PatchInstallService.install()` 确实是异步提交器（`core/.../patch/PatchInstallServiceImpl.java:58-70` 组 `TaskSubmitRequest` 后 `taskService.submit` 返回 taskId），但它只是执行器的外壳。真正的链路 `PatchInstallExecutor.execute(PatchInstallRequest, ProgressListener)`（`core/.../patch/PatchInstallExecutor.java:83-100`）**本来就是阻塞方法**，过程经 `ProgressListener{onProgress,onLog,isCancelled}`（`:70-74`）回调；全局并发闸 3（`:56`、`:86`）、自动重试 2 次退避 5s/20s（`:50-51`、`:794-801`）全在这个类内。任务中心那条路只是把它包了一层。「必须先解决异步→阻塞桥接」这个前提误判了层次。

**结论**：扩展阶段不碰 `install()`，在插件 SDK 模块新增同步入口——

```java
// backend/plugin/.../patch/PatchInstallService.java（新增 default 方法，不破坏既有实现者与调用方）
default void installSync(PatchInstallRequest request, PatchInstallProgressListener listener) {
    throw new UnsupportedOperationException("该宿主未提供同步补丁通道");
}
```

`PatchInstallProgressListener` 是新 SDK 接口（三个方法照执行器现有回调形状）。core 侧 `PatchInstallServiceImpl` 实现为同进程直调 `PatchInstallExecutor.execute(request, adapter)`。

| 要点 | 判定 |
| --- | --- |
| 是否另起一套补丁实现（FR-14 红线） | 否。同一个执行器、同一份代码路径，只是从「提交给任务中心」换成「调用方等待」 |
| BR-09 资源约束（并发 3 / 每主机互斥 / 重试 2 / SSH 600s） | **分两半，不是一句「原样继承」**（v0.3 修正，REV-1）：并发闸 3（`:56`、`:86`）、重试 2 次退避 5s/20s（`:50-51`、`:794-801`）、SSH 600s（`:54`）**确在 `PatchInstallExecutor` 内**，直调即继承；**每主机互斥不在该类内**——该类 Javadoc 自述「同主机互斥（由任务中心 `scopeKey=hostId` 承担）」（`:35`），类内只有 `globalSemaphore`，直调 `execute()` 恰好丢掉它。本期由 `installSync` 自行承同一个键，见 **§14.14**。实现者不得在扩展阶段外面再套一层重试 |
| `isCancelled()` 语义 | 扩展阶段传 `() -> false`：部署路径今天没有取消入口（F-04 不走任务中心），造一个取消通道属扩范围 |
| 阻塞性验收口径（FR-12 / AC-06） | 扩展阶段在 `deploy()` 的调用线程内顺序执行；`deploy()` 已由 `deployAsync` 置于异步线程（`DeployService.java:318-332`），故阻塞发生在部署 worker 线程，不阻塞 HTTP 线程 |
| 双跳风险（顺带登记） | `deployAsync` 是 `@Async` 内再 `CompletableFuture.supplyAsync`（ForkJoinPool.commonPool）。扩展阶段引入的长阻塞步骤会占用 commonPool 线程；本期不为它改部署主流程（D-N05），登记为 RISK-D04 |

### 14.2 行 2：`includePattern` / `headers` 透传（RISK-02）

丢字段的根因是异步任务边界：`PatchInstallServiceImpl.java:47-56` 只把 `instanceId/url/targetPath/format/sha256` 写进 payload，`PatchInstallHandler.java:70-76` 重建请求时同样只认这五个，于是执行器里消费 `headers`（`:146-150`、`:270-323`）与 `includePattern`（`:154-155`、`:556-591`）的代码经 SPI 不可达。

**结论**：RISK-02 的两个选项「补齐字段透传 / 改用同链路内执行」——取后者。14.1 的同步入口把 `PatchInstallRequest` **对象引用**直接交给执行器，不经 JSON 往返，因此：

- `includePattern` 自动生效；AC-11 之外补一条落位断言（§10 V-07），决策 6 的承诺兑现；
- `headers` 不在本期声明模型里出现（§8.2 硬约束、D-N10）：步骤 DTO 无此字段、组装请求时永远为 `null`，结构上不可能被设置，因此不需要「拒绝含 headers 声明」这类校验分支。若补丁源要求鉴权头，执行器现有行为（`ERR_UNSUPPORTED_HEADER_FILE`）即失败原因，按 §8.2「另立需求」处理；
- 异步路径的丢字段缺陷本期不修：受影响方只有经 `install()` 的既有调用方（plugin-l4d2），改 payload 组装会动到本期范围外的行为。登记为已知敞口（§12 RISK-D05），不动代码。

### 14.3 行 3：容器内脚本执行通道（F-16）

F-16 核对为真且比转述更完整：`DockerComposeAdapter.executeCommand`（`:710-731`）拼 `docker compose -p … exec <service> <cmd>`，超时字面量 `60000`（`:728`），返回 `output + error`（`:730`）——退出码在这一层丢掉；`DockerAdapter.java:466-479` 同型（`docker exec` + `60000`）。再下一层 `AbstractDeployAdapter.executeCommand(Host, String, long)`（`:110`）返回 `SshUtil.CommandResult`，**`exitCode` 在这一层是有的**（`SshUtil.java:994-1014`），只是被上面两层的 `String` 签名抹掉。

**结论**：本期不改这条通道，理由是 14.5 的判定使它零使用方，而不是「缺口不存在」。附带结论：

- `LinuxGsmDockerAdapter` 是反例证据：其私有 `executeLinuxGsmCommand`（`:933-950`）用 GNU `timeout` 包 `docker exec` 并返回 `CommandResult`，证明「保留退出码 + 可声明超时」在既有代码里有先例，将来恢复 `container` 时照此形状改造；
- 仓库内 `ExecCreateCmd` / `ExecStartCmd` 零命中；docker-java 3.3.4（`backend/pom.xml:40`、`core/pom.xml:96-101`）虽在 classpath 且能取退出码，但面向本机 docker socket 而非远端主机，本期不引入。

### 14.4 行 4（硬判定①）：`imageTag` 落位机制 ⇒ 给出，AC-14 转可验收

**为什么不能落在扩展阶段**：compose 模板驱动模式下 `DockerComposeAdapter` 把 `composeTemplate` 上传前只做**结构性**后处理（`ensureVolumesDeclaration` / `injectHostCertsMount`，`:1216-1219`），**不含任何 tag / 变量类字符串替换**（v0.3 按 SUG-3① 订正：原写「原文上传、无任何字符串替换」过强——两处后处理确实存在，但它们不碰 `image:` 与 `${}`，tag 的可选性仍只能靠 compose 自身的 `.env` 插值）；而 `.env` 在 `PRE_DEPLOY` 就生成并上传（`:183-197`），容器在 `DEPLOY` 就起来了。扩展阶段在 `DEPLOY` 之后（决策 3）——在它里面改 tag 已经来不及，除非重跑 `DEPLOY`，那违反决策 3 与 BR-10。**所以注入点必须落在部署配置组装期。**

**机制（全部复用既有链路，不新增渲染器）**：

| 环节 | 约定 |
| --- | --- |
| 游戏元数据侧 | 想让 tag 可变的 deployType，模板里写 `image: <repo>:${PLATFORM_IMAGE_TAG:-<默认 tag>}`（compose 原生 `${VAR:-default}` 语法），并在该 deployType 的 `variables[]` 声明一项 `name = PLATFORM_IMAGE_TAG`、`hidden = true`、`defaultValue = <默认 tag>` |
| 为什么这样就够 | `generateEnvFileContent`（`:1397-1435`）本就遍历 `variables[].name` → 取 `config.get(name)` → 落 `.env`。只要 `config` 里出现同名键，tag 就走完整既有链路进 `.env`、被 compose 插值，**core 的渲染代码一行不改** |
| 框架侧唯一新增 | `InstanceServiceImpl.buildDeployConfig`（`:687-759`）第 5 步之后插入第 5.5 步。**门控条件从「三条件齐才写」改为两级（v0.3，SUG-4）**：① 该 deployType 的表侧 `variables[]` **未声明** `PLATFORM_IMAGE_TAG` ⇒ 完全不写（未声明该变量的游戏 `.env` 与模板逐字节不变）；② 声明了 ⇒ **该键的值一律由平台写**：命中目录条目且条目带 `imageTag` → 写条目值；否则 → 写该 `variables[]` 项声明的 `defaultValue`。⇒ 用户 / 通用写接口提交的该键值**永不进 `.env`**（`deploy.vue:246-250` 会把含 hidden 的全部变量回填进 `configInfo`、`:713` 平铺展开，所以「不写」并不等于「用默认值」，而是等于「采信提交值」——这一条在 v0.2 是漏的） |
| 与第 6 步 `image`+`tag` 拼接的关系 | 无关系。那条只对 `services[].image` 结构化生成模式（`generateComposeFile`，`:905-935`）生效，模板驱动模式不经过它。本机制不借用它，以免把两种 deployType 的镜像语义搅在一起 |
| AC-14 的机械核对物 | ① 远端 `docker-compose.yml` 原文与未声明时逐字节相同；② `.env` 中 `PLATFORM_IMAGE_TAG=` 精确等于声明值；③ 该工作目录 `docker compose config` 渲染出的 `.services.<serviceName>.image` = `<repo>:<声明 tag>`。三项齐备即通过，不以文本比对冒充 |
| dnf-tw 是否受益 | **不受益**：本期不改 `dnf_tw.yml`（D-N15 / BR-02 / AC-02 / AC-15），其模板无占位符，故 dnf-tw 的 `imageTag` 恒不使用（FR-09 已如此规定）。机制由桩游戏承载验收：其元数据经既有外置目录 `game-platform.metadata.external-dir`（默认 `./games`）投放，不改 core resources、不进产品 jar（AC-26 ②） |

**回答 S1b 门禁转来第 2 项（只声明 `imageTag`、无 patches/scripts 的条目是否展示）**：**不隐藏、不加标注，而是判为声明不合法**。若条目声明 `imageTag` 而该 deployType 未声明保留键 `PLATFORM_IMAGE_TAG`，该 tag 必然静默无效——正是 Designer 担心的「看上去可选、实际什么都不做」。按 §8.1 校验内容扩展一条蕴含规则（「`imageTag` 存在 ⇒ 该 deployType 的 `variables[]` 必含 `PLATFORM_IMAGE_TAG`」），走 §8.1 既有处置（无键 → 默认版本 + 一条说明行；有键 → BR-12 拦截）。失败在声明读取期暴露，不在执行期静默吞掉。

**须回写 PRD（交 Leader，随 v0.6）**：§8.1 校验内容增加 14.4.1 的四条规则；§8.4.3 的口径按 14.4.2 **改写**（不是「往禁止清单里加一个键」——v0.2 那条建议会把 AC-14 自己的正向路径判成提交失败）。

#### 14.4.1 `imageTag` 判定通道的绑定（v0.3 新增，回应 REV-5）

v0.2 的蕴含规则「`imageTag` ⇒ 该 deployType 的 `variables[]` 必含 `PLATFORM_IMAGE_TAG`」**没有说这个 `variables[]` 从哪读**，而 §16.1 已经证明有两条通道且它们不对称。整条运行链**全在 `game_metadata` 表这一侧**（上传的 `composeTemplate` 取自表、`generateEnvFileContent` 读的 `config["variables"]` 也取自表），所以规则不绑定通道就会在最需要它的场景放行。定死为四条：

| # | 规则 | 依据 / 后果 |
| --- | --- | --- |
| R1 | **唯一判定通道 = `game_metadata` 表快照**（内置 `games/*.yml` + 外置 `./games` 扫描落库的结果）。`DeployVersionCatalogService` 读 `variables[]` / `composeTemplate` 必须与 `buildDeployConfig`（`InstanceServiceImpl:690-699` 的 `gameMetadataMapper.selectById(...).getDeployConfig().get(deployType)`）**同一读法、同一个 map 实例**；**禁止**经 `GameServiceImpl` 的合并视图（`:195-201`）判定 | 否则插件用 `getDeployConfigs()` 整节替换声明出的 `variables` / `composeTemplate` 会**通过校验而部署侧完全看不见**——tag 静默无效，正是本规则声称要防的「看上去可选、实际什么都不做」，与 §14.4 自立的判据同构 |
| R2 | 经 `getDeployConfigs()` 声明的 `variables[]` / `composeTemplate` **不参与 `imageTag` 机制**，其状态与 RISK-D07 属同一缺陷（合并只作用于 VO，不作用于部署配置），本期不修、不测 | 把「不生效」写成显式结论，避免下游以为换个声明入口就能用 |
| R3 | **表侧该 deployType 的 `composeTemplate` 必须含字面量 `${PLATFORM_IMAGE_TAG`**，否则声明 `imageTag` 即不合法 | 注入后无消费者 = 静默无效。取字面量前缀匹配，**不认** `$PLATFORM_IMAGE_TAG` 简写（与 `${VAR:-default}` 的既有推荐写法一致，简化校验且避免与 shell 变量歧义）。这是 R1 之外唯一能证明「注入有人接」的静态证据 |
| R4 | `imageTag` 与「该保留变量的 `defaultValue`」的取值都要过**格式校验**：非空、匹配 `[A-Za-z0-9][A-Za-z0-9._@/-]{0,127}`、**禁空白 / 换行 / `${` / `}`**（compose tag 合法字符集） | `generateEnvFileContent` 对值不做转义与换行过滤 ⇒ 未校验的值可以直接改写 `.env` 结构（注入面的具体形状）。平台是单管理员信任模型，所以这定性为**输入校验缺失**而非漏洞，但必须补：`.env` 一行写坏会让整个 compose 项目起不来，属可用性问题 |

#### 14.4.2 `PLATFORM_IMAGE_TAG` 与 BR-07 撞键清单的关系（v0.3 新增，修一处 v0.2 的自相矛盾）

v0.2 §5.2 建议「把 `PLATFORM_IMAGE_TAG` 列入 §8.4.3 禁止清单」，而 PRD §8.4.3 的原文语义是「**`deployVersion` 不得与以下键同名**」（① `variables[].name` 任一取值 ② 三系统键 ③ `gameVersion`）。按 v0.2 的字面落法会得到两个错误结果：

| 若照 v0.2 字面实现 | 后果 |
| --- | --- |
| 把 `PLATFORM_IMAGE_TAG` 当「提交载荷不得出现的键」 | **AC-14 的正向路径自己被判 400**：任何声明了该保留变量的游戏，向导提交时都会带上它（`deploy.vue:713`），于是「用 `imageTag` 部署」这条主路径在提交期即失败 |
| 只按原文语义做「`deployVersion` 名字冲突」检查 | 加与不加都是空操作（两个键名永不相等），等于没写 |

**定稿口径（两条，分开）**：

1. **`deployVersion` 的撞键清单保持 PRD 原三项**（`variables[].name` ∪ `database`/`containerWorkDir`/`serviceName` ∪ `gameVersion`），`PLATFORM_IMAGE_TAG` **不进该清单**；
2. `PLATFORM_IMAGE_TAG` 是**声明期保留键**，其约束全部落在 R1/R3/R4（声明侧）+ 14.4 表的②（提交值不采信）——**用户提交的该键值不参与任何判定，且在 5.5 步被平台值覆盖**。因此 §8.4.3 需要回写的不是「加一个禁止键」，而是「补一句：该键为平台保留、提交值不采信」（D-P05 v0.3 改写项）。

#### 14.4.3 注入点的副作用登记（v0.3 新增，回应 SUG-5）

第 5.5 步在 `buildDeployConfig` 内，意味着**目录读取 + SPI 调用 + §8.1 全量校验**被引入 `buildDeployConfig` 的全部 10 个调用点（含 `updateInstance:183`、start/stop/restart/文件/备份路径与 retry-deploy）。功能上安全（`ABSENT` 有快路径、插件抛异常归 `ABSENT` 不外泄），但「每次读文件列表 + 每次调 SPI」不是零成本。处置：

| 项 | 判定 |
| --- | --- |
| 收敛条件 | **仅当 `configInfo` 含 `deployVersion` 键时才读目录并注入**（无键 → 走 R2 的默认值分支需要 `variables[]`，而 `variables[]` 本就在 `buildDeployConfig` 第 5 步读出的同一 map 里，不额外读盘）⇒ 绝大多数（本期全部）游戏与全部非版本路径**零新增 IO、零 SPI 调用** |
| 不引入缓存 | 本期不加目录缓存：缓存会把「热部署即生效」（§16.2）变成「过一会儿才生效」，属新语义；无键即不读之后，读盘只发生在真正用版本的部署上，量级为个位数 |
| 与 RISK-D07 的关系 | 该收敛同时把 RISK-D07 的扩散面从「所有配置组装」缩到「带版本键的部署」 |


### 14.5 行 5（硬判定②）：停实例 + `position = container` ⇒ 判不合法，AC-13 移出本期

**判定依据（三条）**：

1. compose 类「停实例」= `docker compose -p … stop`（`DockerComposeAdapter.java:383-399`，超时 `120000`），**容器停止但保留**（不是 `down`）——容器定义仍在、卷与项目名不变，故 RISK-05 关于「`stop` 与 `down` 数据/网络后果不同」的担忧在选定语义下不成立；
2. `docker exec` / `compose exec` 的前置条件是容器 running，对已停止容器必然失败（F-16；`DockerComposeAdapter.executeCommand` 无任何拉起动作）；
3. 绕开方式共三条，全部否决（**v0.3 按 SUG-2 换成经得起复核的依据，并把评审点名的第三条路显式登记**，见 14.5.1）：① 扩展阶段先把容器起起来、执行完再停；② `docker run --rm` 起临时容器挂同样的卷；③ `docker compose run --rm <service>`。①直接制造决策 3 明确否掉的「先起错版本再重启」中间态；②③的执行对象都已不是目标容器，属新造执行模型，超出本期范围。

⇒ **`position = container` 与 FR-11「进入扩展阶段前实例停止」互斥，本期只交付 `position = host`。**

**由此产生的确定性收缩**（PRD 五处口径本就为此预留；须由 Leader 下派 @ProductManager 回写，不得由实现侧静默取舍）：

| 对象 | 结论 |
| --- | --- |
| AC-13 | 自 §11.1 承载表第一行移入第五行，本期记「本期不验收」 |
| FR-08 / §8.3 `position` | 本期取值只有 `host`（缺省即 `host`）；声明 `container` 属声明不合法，走 §8.1 → BR-12 处置（不是「忽略该字段继续」） |
| 决策 7 的「容器内执行为步骤级可选项」 | 随本条移出本期 = 需求范围收缩，须记 PRD 修订记录。ADR-0029 决策 7 正文不改（其「保留步骤级出口」作为后续增量仍成立） |
| SDK 枚举是否删掉 `container` | **保留字面量但校验期拒绝**（恢复时不必改接口签名），注释标明「本期校验期拒绝；恢复前提见下行」。AC-23 ③ 的 grep 口径与 SDK 兼容性均不受影响 |
| 恢复 `container` 的前置（登记，本期不做） | ① 退出码：`DockerComposeAdapter:710-731` / `DockerAdapter:466-479` 改返回带 `exitCode` 的结果（照 `LinuxGsmDockerAdapter:933-950` 的 GNU `timeout` + `CommandResult` 形状）；② 超时：两处 `60000` 字面量参数化；③ 执行模型：定义「容器停止状态下如何执行」并解决与 FR-11 的冲突——③ 不解决，①② 无意义 |

**「停止状态」的可观察定义 + 停失败处置（RISK-05 要 @Architect 定调的两件事）**：

| 项 | 定义 |
| --- | --- |
| 前置动作 | 扩展阶段进入行之后、任何步骤之前执行；**无扩展步骤则整个阶段（含停实例）不执行**，既有部署一个数字都不动 |
| 停止判定 | 调适配器停止后以 `DeployAdapter.getStatus(instanceId, config)` 轮询（3 次 × 2s）判定非 RUNNING；成立才算停止完成（v0.3 按 SUG-3② 订正签名：`DeployAdapter:226` 是双参） |
| 不复用 `DeployService.stop()` | 现有 `stop`（`:418-430`）会把 `run_status` 回写 STOPPED，而 PRD §9 要求扩展阶段期间仍为 `INSTALLING(5)`。故新增私有 `ensureStoppedForExtension()`：只调适配器、不写状态 |
| **停实例失败处置** | **致命**：记 ui-spec 状态 S 的失败行（`实例停止失败：…`，`level = ERROR`），部署判失败、实例 `ERROR`、不执行任何步骤、不进入 `HEALTH_CHECK` / `START`。理由：在未确认停止的实例上替换文件正是决策 3 要消除的中间态。ui-spec 的「预留文案，生效前提是 @Architect 定调」自此生效 |
| 后续启动与 `HEALTH_CHECK` | 步骤全部判定完成后，扩展阶段收尾以既有 `up -d` 形状把容器按依赖顺序起回（§14.12 判定 + §14.13.3 逐类形状与调用面），之后进入既有 `HEALTH_CHECK → UPDATE_STATUS → START`，三者的判据与时点一字不改 |

#### 14.5.1 三条绕法逐条否决（v0.3 新增，回应 SUG-2；AC-13 移出须经人类 G4，依据文本必须经得起核）

| 绕法 | 命令形状 | 否决依据（逐条独立成立） |
| --- | --- | --- |
| ① 起→执行→再起停 | 扩展阶段内先 `up -d`，执行完再 `stop` | 直接制造决策 3 明确否掉的「先起错版本再重启」中间态；且 `HEALTH_CHECK` 会探测到那个错版本容器（§14.12 同构失效） |
| ② 临时容器 + 手工挂卷 | `docker run --rm -v <同样的卷> … <image> <cmd>` | (a) 执行对象不是目标容器，「在实例的运行环境里执行」这一产品语义不成立；(b) **镜像内文件层的改动随 `--rm` 丢弃**——补丁若落在非卷路径（dnf-tw 这类镜像的二进制常年在镜像层里）即**静默无效**；(c) 挂载 / 网络 / 环境变量全套需从 compose 服务定义重新推导，而 `getProjectName`/`getWorkDir` 都是适配器 private（compose `:1101`/`:1113`），要推导就得改适配器或复制一份 compose 解析器 ⇒ 新造执行模型 |
| ③ **`compose run`**（评审指出的第三条路，v0.2 未处理） | `docker compose -p <project> run --rm --no-deps <service> <cmd>` | v0.2 依据 3 的后半句「要重新推导全部挂载与网络」**对本条不成立**——compose 会自行解析服务定义，这正是它比 ② 近一步的地方，故本条必须换依据。换后三条各自独立成立：(a) **执行对象是兄弟容器而非目标容器**：`run` 新建一个一次性容器，补丁落它身上即与目标容器无关；(b) 同样吃 **(b) 镜像内文件层改动随 `--rm` 丢弃**，非卷路径静默无效；(c) **必须带 `--no-deps`**（此刻依赖服务已被 `compose stop` 停下，`run` 默认会连带把依赖再拉起一遍，破坏 FR-11 的停止语义），而带 `--no-deps` 后依赖不再保证就绪 ⇒ 要么违背「停实例」前提，要么在依赖缺失的环境里跑脚本。**结论不变（判不合法），但依据换成本表三条** |
| 三条共同的第四层理由 | — | 即便某条技术上通了，它引入的是「三种容器执行模型」中的第三种，而本期 `position = host` 已能完整交付 dnf-tw 的全部需求（版本 = 补丁包 + 脚本产出，落位路径由声明给出）。为「界面取值多一个」付「执行模型翻倍」的代价，属 §8.1「不引入 PRD 未要求的闸门/能力」的反向情形 |
| 与 AC-13 移出的关系 | — | 本表是 AC-13 移出本期（D-P01）在人类 G4 复核时的依据文本；ADR-0029 决策 7 的「保留步骤级出口」作为后续增量仍成立，恢复前置三项见 §14.5 末表 |

### 14.6 行 6（硬判定③）：日志呈现契约三项 ⇒ 定稿

PRD F-03 的限制是真的：`LogEntryVO{id,level,message,stage,time}`（`DeployService.java:62-71`，映射在 `InstanceController.java:876-897`）既无步骤标识也无耗时字段。§8.5 允许「复用 message 固定前缀 / **扩展 VO 字段** / 其它」三选一——**取扩展 VO 字段**：`message` 前缀方案的归组判据是文本，而 KPI-02 要求「机械判定、不得靠人工文本判读」，用文本当锚点等于把 AC-03 / AC-16 / KPI-02 的核对建立在另一个文本约定上（UI 评审 MF-2 踩的正是这类软锚点）。

`LogEntry` / `LogEntryVO` 新增六个可选字段（既有阶段全部传 `null`，前端不读即零行为变化，AC-15 安全）：

| 字段 | 类型 | 契约项 | 约定 |
| --- | --- | --- | --- |
| `stage` | 既有 String | 阶段标识 | 扩展阶段全部行取常量 `"EXTENSION"`（与既有 `INIT/ENV_CHECK/…/COMPLETE` 均不冲突；该字段此前被前端完全丢弃，见 14.9） |
| `stepId` | String | ① 步骤标识（归组主键） | `E-<序号>`（如 `E-1`），同一次部署内唯一；阶段级行（进入 / 交棒 / 停实例 / BR-12 拦截 / 目录不合法说明）为 `null` |
| `stepIndex` / `stepTotal` | Integer | ① 步骤标识（展示位） | 序号 1 起；`stepTotal` 在阶段入口算步骤集时即得（FR-05 / FR-12），不为此新增接口 |
| `stepLabel` / `stepType` | String | ① 步骤标识（展示位） | `stepType ∈ PATCH / SCRIPT`；`stepLabel` 取声明侧 `label` |
| `stepEvent` | String | ③ 归组判据 | `START` / `SUCCESS` / `FAILURE` / `ROLLBACK` / `NOTE` |
| `elapsedMs` | Long | ② 耗时承载位与单位 | 毫秒整数（权威值）；仅 `SUCCESS` / `FAILURE` 与阶段完成行非空 |
| `exitCode` | Integer | ②′ **脚本退出码承载位**（v0.3 新增，回应 REV-6 的契约缺口） | 仅 `SCRIPT` 步骤的终态行（`SUCCESS` / `FAILURE`）非空；`PATCH` 步骤与阶段级行恒为 `null`（执行器不回报退出码，不为此改 `PatchInstallExecutor`）。FR-15 的「`exitCode ≠ 0` 判失败」自此有字段可判，不必落到 `message` 文本 |

**承载位分工（写死，防止「日志里有」被读成「可核对」）**：

| 内容 | 承载位 | 是否机械判据 |
| --- | --- | --- |
| 步骤归组 / 齐备 / 串行 | `stepId`、`stepEvent`、`elapsedMs` | **是**（规则 1–3） |
| 脚本退出码 | `exitCode` | **是**（规则 4） |
| 补丁回滚结果的**存在性** | `stepEvent = ROLLBACK` 行的存在与位置 | **是**（规则 5） |
| 回滚**是否成功** | `message` 文本（转写执行器 `已回滚备份` / `回滚失败: …`，`:215`/`:218`） | **否**——AC-09 的判据是**目标路径文件比对**（V-05），不是这行文本 |
| `stdout` / `stderr` | 仅进 `message`（`stepEvent = NOTE` 行，§8.3 截断规则） | **否**——可见性核对单独登记（V-22 的第二个判据块），与 AC-12 的失败判定分离，不用文本匹配冒充机械核对 |

**五条规则，逐条可核对**：

1. **归组**：`stepId` 相同的所有行属于同一步骤；一次部署内 `stepId` 与 `stepIndex` 一一对应。
2. **「三项齐备」判据**：某步骤齐备 ⇔ 该 `stepId` 下恰有一个 `stepEvent = START` 行 + 恰有一个终态行（`SUCCESS` 或 `FAILURE`），且该终态行 `elapsedMs != null`。KPI-02 分子 = 满足此式的步骤数，分母 = `stepId` 去重计数——纯字段判定，不读 `message`。
3. **串行可见**：步骤 N 的终态行之后才允许出现步骤 N+1 的 `START` 行（AC-06 判据，等价于 FR-12 的阻塞语义）。
4. **退出码判定**（v0.3）：`SCRIPT` 步骤终态行 `stepEvent == FAILURE` **⇔** 该步 `exitCode != 0`（或超时——超时时 `exitCode` 为 `null` 且原因段指名超时，二者可靠区分）。AC-12 的「`exitCode ≠ 0` 判失败」按本条核对。
5. **回滚记录位**（v0.3，回应 SUG-7）：`stepEvent = ROLLBACK` 行的机械判据 = 「某 `PATCH` 步骤终态为 `FAILURE` ⇒ 该 `stepId` 下、该终态行之后、下一步骤 `START` 之前**恰有一行** `ROLLBACK`」。该行是**回滚窗口的记录位**，其语义与 `level`：执行器回报 `已回滚备份` → `level = INFO`；回报 `回滚失败: …` → `level = ERROR`（PRD §12「补丁目标回滚失败」行自此在部署日志里有落点）；失败发生在备份之前（无可回滚）→ 仍写该行，`level = INFO`、`message` 记「无备份可回滚」。**回滚是否真的成功不由本行判定**（见上表），由 V-05 的文件比对判定——这是刻意的：要拿到一个「回滚结果」的结构化信号必须改 `PatchInstallExecutor` 的回调接口，而 Leader 已就同类改动（③ 临时路径命名空间）裁定「不动 ADR-0006 既有行为」。**不采纳**评审建议中「按回滚结果决定是否产该行」的形态，理由即此。

`message` 文本仍是给人读的：词面由 ui-spec §6.2 定稿（例 `步骤 1/3 〈标签〉 · 补丁替换 · 成功 · 耗时 12秒`）。**渲染文本不是核对对象，`elapsedMs` / `exitCode` 才是**——这条分工写死，避免界面词面改动连带破坏 KPI-02。

**耗时单位口径（v0.3，回应 SUG-6）**：`elapsedMs` 是毫秒权威值，`DeployProgress.vue:71-85` 的 `formattedElapsedTime` 是**读 `elapsedTime` 的 computed**（不可复用），故 F-03 的实现形状 = 先把它参数化为 `formatElapsed(seconds)` 纯函数，再补 ms→s 的换算口径：**`Math.max(1, Math.round(ms / 1000))` 秒**——既有公式下 `<1000ms` 会渲染成「0秒」，与 AC-03「耗时可见」的观感冲突（快速步骤显示 0 秒等于没显示）。换算只发生在渲染层，VO 与核对脚本永不出现秒值。

`level` 口径不变（§8.5：致命 `ERROR`、非致命 `WARN`、成功 `SUCCESS`、过程 `INFO`），后端取值集合保持大写不变，改动落在前端（14.9）。

### 14.7 行 7：扩展阶段的进度百分比区间

现状逐字面量核对（`DeployService.java`）：`INIT 0` → `ENV_CHECK 5/10` → `PORT_CHECK 10/15` → `RESOURCE_CHECK 15/20` → `PRE_DEPLOY 20` + band `20–40` → `DEPLOY 40` + band `40–80` → `HEALTH_CHECK 80/90` → `UPDATE_STATUS 90` → `START 95/98` → `COMPLETE 100`；band 内插值公式在 `:860`。

**方案：条件分配（gated allocation）——有步骤才改数字。**

| 分支 | 分配 | 理由 |
| --- | --- | --- |
| 无扩展步骤（本期绝大多数游戏） | 完全沿用现有序列，不改任何字面量 | BR-11 / AC-15 / G-04 的回归基线对象就是这批游戏；任何全局重排都会改掉既有 `DEPLOY` band 的插值结果，直接违反 AC-15 |
| 有扩展步骤 | 扩展阶段占 `[80, 84]`：阶段起点报 80，其后按已完成步骤数 `80 + floor(4 × i / total)`，上限 84；`HEALTH_CHECK` 的**顶层 `progress` 值**在该分支内由 80 改报 85（v0.3 按 SUG-3③ 订正核对对象：`updateTaskStatus`（`:844-851`）只写 `DeployTaskStatus` 的 stage/progress/message，**不产生日志行**——产日志的是 `notifyProgress` / `notifyStage*` 路径。所以「进入行」这个说法在 V-10 与 §14.7 都不成立，统一改述为「顶层 `progress` 值」，V-10 的采样对象 = `deploy-progress` 接口的轮询样本） | ① 不与任何既有阶段共用窗口（§6.2 约束）：`DEPLOY` 仍 `[40,80]`，扩展只吃 80–84，`HEALTH_CHECK` 仍收在 90；② `COMPLETE = 100` 与 `START`/`UPDATE_STATUS` 一字不改（BR-10）；③ 单调不减：80 → 80..84 → 85 → 90 → 95 → 98 → 100；④ 全部改动只有「有步骤时 HEALTH_CHECK 起点」一个数字 |

失败路径不变：致命步骤失败时 `progress` 停在最后上报值（现状失败也不到 100，`DeployService.java:258-310`），`completed = true` / `success = false` / `error` 非空——沿用，不新增语义。

### 14.8 行 8：BR-16 保键手段 ⇒ 合并式写入，且不需要「拒绝写入」分支

BR-16 的两个许可手段（合并式写入 / 覆盖前保留本次未显式改动的键）在实现上是同一件事的两种说法。本设计明确取**合并式写入**并**取消「拒绝写入」这条路**——不是因为它被禁止，而是合并方案让它不需要：

```
InstanceServiceImpl.updateInstance(dto)                  // :153-195
  现：BeanUtil.copyProperties(dto, instance, "id")       // :177 ← 整表替换 configInfo，未出现即丢
  改：1. 取本次操作前库中既有 configInfo（同一实体查出的旧值即可）
      2. copyProperties 之后：merged = new LinkedHashMap<>(既有); merged.putAll(dto.configInfo)
         ← 本次载荷逐键覆盖，未提及的键保留
      3. instance.setConfigInfo(merged)
      4. :182-190 的 database 回注保持不动（幂等，且它本就依赖声明重组）
```

| AC-27 项 | 结果 |
| --- | --- |
| (a) `PUT /api/instances/{id}/config` 提交不含 `deployVersion` 的 map | 键保留且值精确不变 → 走 PASS 的第一支路（「仍精确等于原值」），无需触发拒绝分支 |
| (b) 配置管理入口点一次保存（载荷 `{configFile, content, restart}`） | 三键照写，其余既有键含 `deployVersion` 全部保留 → PASS |
| (c) 同入口再做一次不涉及版本项的普通保存 | **必须返回成功**——合并式不引入任何失败条件，故不可能「恒失败」；`deployVersion` 与该 map 内其它既有键逐键不变 → PASS |
| (d) (a)(b)(c) 之后各重部署一次 | retry-deploy 读 `configInfo`（`buildDeployConfig`）→ 键在 → 进扩展阶段 → 按同一配方交付同一非默认版本 → PASS |

**必须同时成立的三条口径**（否则不变式仍会翻转）：

1. **删键只有一个合法入口**：版本选择应用逻辑（14.10 的 `applyVersionSelection`）。合并语义把「省略键」变成「不动键」，因此不能再靠省略来删键——这条要写进代码注释与测试，否则实现者会按旧直觉省略。
2. **合并是加法式改动**：只影响 `updateInstance` 这一条写路径；`create` 与部署提交路径的 `configInfo` 组装不变，故不改变「首次部署载荷形状」（AC-02 / AC-24 ② 的判定对象）。
3. 通用写接口自此**不再是**解除版本要求的旁路（BR-03 / BR-16），这由手段本身保证，无需额外校验代码。

### 14.9 行 9：`level → 视觉映射` 失效（S1b 门禁转来第 1 项）

核对为真，且比转述更完整：后端经 `appendLog`（`DeployService.java:565`）写入的取值是 `INFO` / `ERROR` / `SUCCESS` / `WARN`（调用点 `:175/:177/:181/:240/:273/:300/:662/:677/:690/:711/:717`），前端 `getLogClass`（`:88-97`）与 `getLogIcon`（`:100-109`）的分支键是小写 `info/success/warning/error/debug`——**`WARN` 与 `warning` 除大小写外词根也不同**，即使只补 `toLowerCase()` 仍接不上。`DeployProgress.vue` 全文 0 次引用 `stage`（该字段一直有值）。

**归一化方案定稿：前端兼容（改动局限在一个组件内）。** 四个候选里排除其余三个：

| 候选 | 判定 | 理由 |
| --- | --- | --- |
| 后端统一改小写 | 否 | PRD §8.5 把 `level ∈ INFO/WARN/ERROR/SUCCESS` 写成口径，AC-03/AC-08/AC-10 的断言词也是大写；改后端等于让技术设计推翻需求层已定口径，且其它共用该字段的组件断言面不可控 |
| 复用 `stage` 约定 | 否 | `stage` 表达阶段不表达 severity，语义错位 |
| **前端归一化（采纳）** | 是 | ① `const key = String(level ?? '').toLowerCase()`；② 别名 `warn → warning`；③ 确认 `success` 分支可用（后端发 `SUCCESS`，归一化后自然命中）。AC-08 / AC-10 / AC-16 / KPI-02 的界面可观测性一次性恢复 |
| 顺带把 `stage` 渲染进每行 | 本期不 | 阶段带改由 `stage` 驱动（14.11），但「每行印阶段名」不是产品要求，词面归 @Designer（OP-05），不自行加码 |

### 14.10 行 10（新发现）：AC-22 与 §8.4.2 S2 的「删键」缺一条真实入口

PRD 的 §8.4.2 S2、BR-16 ①、§12「向导不展示但键已存在」行的恢复路径、AC-22 都假定存在这样一条路径：**对一个 `deployVersion` 已存在的实例，在部署向导里改选默认版本并重新提交**。代码事实是这条路径不存在：

- 部署向导只创建新实例：`deploy.vue:206` 只读 `route.query.gameId`，提交走创建实例接口；
- 既有实例的重部署入口是 `POST /api/instances/{id}/retry-deploy`（`InstanceController.java:534-536`），它只复用库中 `configInfo`（`buildDeployConfig(instance)`），**不接受任何新选择**；
- 通用配置写接口按 BR-03 明确不是改写版本键的合法手段。

⇒ 「改选默认版本 → 删键」在界面上无处发生。**本期把它做成服务层语义 + 单测，界面端到端不可达。**

| 方案 | 内容 | 代价 |
| --- | --- | --- |
| A | 本期新增「同实例改选版本重部署」入口（详情按钮，或向导支持带 `instanceId` 的第二种提交模式），使 AC-22 界面可测 | 扩范围：PRD 无对应 FR、ui-spec 22 态未覆盖该入口、需 @Designer 补态、需 @FrontendDev 新交互，还要为「重部署前改配置」定义与 FR-13 重放的先后关系 |
| **B（推荐）** | 删键语义照样实现并有单测：`applyVersionSelection(configInfo, catalog, selection)` 三态纯函数——选非默认 → 写键；选默认且键既存 → 删键；无目录 → 不写不删。AC-22 的判定改由「服务层三态 + `retry-deploy` 重放不丢键（AC-07 已覆盖）」承载，其**界面端到端**部分记「前提未成立」，回写 §8.4.2 / AC-22 各加一句限定 | 不扩范围；用户实际损失很小——今天想换回默认版本的真实做法就是新建一个默认版本实例，旧实例的键不再被任何入口读到 |

`applyVersionSelection` 的写入侧规则（A/B 都成立）：**只有部署向导提交能写/删 `deployVersion`**；提交期只做 §8.4.3 撞键校验（BR-07），**不**因「目录不可用」在提交期返回 400——那是 BR-12 的运行期职责。否则 AC-20(a) 要求的「部署失败、实例 `ERROR`」会被降级成「提交被拒、实例不存在」，AC-20 直接判 FAIL。这条是 AC-20 可测性的关键保护，实现者不得反向优化。

### 14.11 S1b 门禁转来第 3 项：阶段带 / 「扩展」步骤点的驱动源

Designer 问 `status` / `statusText` / `stage` 三者取哪个。核对：`DeployProgressVO{progress,status,statusText,logs[],completed,success,error}`（F-03）**顶层没有 `stage`**（`DeployTaskStatus` 有，只是没暴露）；`mapStageToStatus`（`DeployService.java:609-620`）不含新阶段时会落到 `default → "preparing"`。

| 判定对象 | 驱动源 | 说明 |
| --- | --- | --- |
| P3「本次真的进过扩展阶段」（阶段带 / 步骤点是否出现） | `logs` 中存在任一行 `stage === 'EXTENSION'`（latch） | 现有组件已累计全部日志行并按 `log.id` 去重（`DeployProgress.vue:182-183`），故该谓词在 `HEALTH_CHECK` 之后仍为真，不需要额外状态位；`statusText` 会变、`progress` 会跨过，都不能当 latch |
| 「当前正在扩展」高亮（激活态） | 顶层新增 `stage` 字段（`DeployTaskStatus.stage` 直投，成本一行） | 前端不必再猜百分比分桶 |
| `mapStageToStatus` | 新增 `EXTENSION → "installing"` | 与 `DEPLOY` 同词，避免冒出「扩展阶段显示 preparing」的第三种状态 |

百分比区间（14.7）与这三者是独立的：P3 不依赖百分比，Designer 把原型数值标「示意」是对的。

### 14.12 行 11（阻断级新发现）：停实例与 `HEALTH_CHECK` 的时点冲突

PRD §14.2 的八行、S1b 转来的三项都没覆盖这一条，但它是本期**能不能交付**的前提。代码事实三条：

1. 插入点在 `notifyStageComplete(…, "DEPLOY")`（`DeployService.java:214`）之后、`updateTaskStatus("HEALTH_CHECK", 80)`（`:217`，v0.3 按 SUG-3③ 订正行号；`:216` 是注释行）之前——正是 FR-10 要求的位置；
2. `DockerComposeAdapter.healthCheck` 逐容器执行 `docker inspect -f '{{.State.Running}}'`（`:452-483`），**任一容器不是 `true` 即返回 false**，`DeployService` 随即 `throw new DeployException("健康检查失败")`（`:218-220`）；
3. compose 类在 `DEPLOY` 的 `up -d`（`:260`）里已经把容器起起来了，而 FR-11 / 决策 3 要求进入扩展阶段前把它停下。

⇒ **三条同时成立时，任何带扩展步骤的 compose 部署都会在 `HEALTH_CHECK` 处必然失败**：停实例 → 探测 Running → false → 部署 `ERROR`。AC-03 / AC-04 / AC-05 / AC-08 全灭，本期头号交付物直接归零。这不是实现细节，是 FR-10 + FR-11 与既有 `HEALTH_CHECK` 位置之间的口径冲突，PRD 与 ADR 都未预见。

**判定：扩展阶段收尾追加一次「按依赖顺序把容器起回来」，`HEALTH_CHECK` 与 `START` 一字不改。**

| 项 | 结论 |
| --- | --- |
| 改什么 | 扩展阶段全部步骤判定完成后，追加一行收尾动作：以既有 `docker compose -p … up -d` 形状（`DockerComposeAdapter:260` 已在用同一条命令）把容器恢复到运行态；成功 → 记「交棒行」进 `HEALTH_CHECK`；失败 → 按致命处置（部署 `ERROR`） |
| 不改什么 | ① `HEALTH_CHECK` 的**判据、动作、时点**全部不变（容器此时确实运行）⇒ 不需要向 N-06 请假；② `UPDATE_STATUS` 置 `STOPPED`（只写库）、`START` 的 `adapter.start()`、`retryHealthCheck` 3×5s、`COMPLETE = 100` 全部不变（BR-10）；③ 无扩展步骤的游戏零改动（AC-15）——该收尾只在扩展分支内存在 |
| 为什么不用「扩展分支内跳过容器态探测」这一方案 | 那条路要把健康判定的实际发生点后移到 `START` 之后，虽然判据不变，但**时点变了**，需要 Leader 判它是否触碰 N-06 并回写 PRD §9；而本方案让 PRD 一个字都不用改，代价完全相同：两案的容器起停次数都是「`up -d` 起 → 停 → 起」各一次，起的位置从 `START` 挪到扩展阶段收尾而已。取更省的那个 |
| 决策 3 的口径是否仍成立 | 成立，且更贴字面。「第一次启动即目标版本」指**打补丁后的第一次进程启动**：补丁在停止态落位，扩展收尾的 `up -d` 才是那次启动。`DEPLOY` 阶段 `up -d` 那次是既有实现产物（今天所有 compose 部署都有），本设计不新增也不消除 |
| 顺带修掉的一个更隐蔽的失效 | `DockerComposeAdapter.start` 走 `compose start`，而 **`start` 不处理 `depends_on` 顺序**（只有 `up` 处理）。dnf-tw 的 compose 项目里 MySQL 与多个游戏服务同在 `docker-compose.yml`，今天那一步是空操作；若把「真实启动」交给它，首启可能早于数据库就绪，表现为「部署成功但版本没生效」。用 `up -d` 收尾同时解决这条（RISK-D11） |
| 实现红线 | **不得**把 `DockerComposeAdapter.start()` 全局改成 `up -d`（会改变既有实例启停语义，直接违反 AC-15 / N-06）。收尾动作是扩展阶段的内部步骤，与 `adapter.start()` 无关 |
| 登记 | 建议作为 §14.2 第 9 行待改依赖正式登记，并在 PRD §9「进入扩展阶段」行补一句「阶段收尾容器恢复运行 ⇒ `HEALTH_CHECK` 语义与时点不变」（D-P02） |
| **v0.3 修正（REV-2 / REV-3）** | 本节的三条代码事实**只对 compose 一类成立**：① 「起回」当时**没有可调用的面**（`DeployAdapter` 无该能力、`LinuxGsmDockerAdapter.ensureContainerRunning` 是 private 且其命令形状不可用）；② 「停实例 → `HEALTH_CHECK` 必挂」对 `docker` / `linuxgsm-docker` / `docker-compose` **三类容器适配器同时成立**，本节只论证了 `docker-compose`。两者一并由 **§14.13** 收口；本节结论（起停次数、`HEALTH_CHECK`/`START` 一字不改、收尾失败按致命）不变，适用范围收窄为 compose 两类 |

### 14.13 行 13（REV-2 + REV-3）：收尾的调用面，与本期支持集合的逐类收口形状

要收口的两件事来自同一处判断：**§14.12 说「收尾把容器起回」，但它既没说用哪个方法起（REV-2），也没说这条判定管到哪几类适配器（REV-3）。** 前者会让实现方自行决断（并在 `DeployExtensionExecutor` 里 `instanceof` 分派、自行拼 compose 命令），后者会让 `docker` 类游戏一上扩展步骤就必挂。

#### 14.13.1 本期 EXTENSION 支持集合（Leader 裁定：按 FR-11 字面收口）

| 项 | 判定 | 依据 |
| --- | --- | --- |
| 支持集合 | **`{docker-compose, linuxgsm-docker}` 两类，逐类收口**（形状见 14.13.3） | PRD FR-11 原文点名「对『部署动作即起容器』的适配器（**docker-compose / linuxgsm-docker**）」；AC-04 前置写「部署方式仍取 **compose 类**」。集合与 PRD 字面一致，**不是本设计自行收窄** |
| 集合外的 deployType（`docker`、`linuxgsm`，含将来新增的适配器） | **该 deployType 的版本目录若含任何扩展步骤或 `imageTag` ⇒ 声明不合法**，走 §8.1 → BR-12 既有处置（无键 → 默认版本 + 一条说明行；有键 / 显式选择 → 部署失败、实例 `ERROR`）。**不是**「忽略该字段继续」，也不是运行期才炸 | §8.1「禁止部分采纳」+ BR-04「主应用不得推断或覆盖声明」；判定发生在 `DeployVersionCatalogService.read(gameCode, deployType)`，本就以 deployType 为键（§16.3），**无需新增任何上下文** |
| 为什么 plain `docker` 不顺手支持（成本核对，不是留白） | 核对为真：`DockerAdapter.deploy`（`:135`）走 `docker run -d`（`buildDockerRunCommand`，`:741-742`），`healthCheck` 第一件事就是 `isContainerRunning` 判 false 即 return false（`:274-277`）⇒ 与 §14.12 **同构，必挂**。它的「起回」两条形状都不清白：`docker start <name>` 只恢复既有容器（`DEPLOY` 后若容器被删则无对象，且要外推 `getContainerName`——也是 private，`:629`）；`docker run -d` 重跑则要把挂载 / 端口 / 网络 / 重启策略全套 argv 重新推导——**正是 §14.5 判 `position = container` 不合法时用的同一条理由（新造执行模型）**。支持它的成本**高于**回写 FR-11 / AC-04 的成本，故取回写（D-P15） | Leader 给出的改判条件（「某类收口成本低于回写成本可提」）经核对不成立，故按裁定执行 |
| `linuxgsm`（非容器）为什么也不支持 | FR-11 的三段式对它是 `linuxgsm stop`（**进程级**）→ 步骤 → `linuxgsm start`（**会真实启动游戏服务器**），与 compose 类「容器级停止 + `HEALTH_CHECK` 只看容器态」的语义不同；PRD 未点名它，本期不自行扩集合。**诚实登记**：技术上它可能可用（宿主脚本 + SSH 步骤天然搭），但「可能可用而未验证」不构成把它放进集合的理由 | 裁定原文；`LinuxGsmAdapter` 本期零改动 |
| 桩插件承载 AC-04 / V-11 / V-12 的部署方式 | **取 `docker-compose`**（与 AC-04 前置同口径），`linuxgsm-docker` 由 **V-27 单独一行**承载，不用「同类所以不测」糊过去 | 消除「集合内一类无证据」的空档 |

#### 14.13.2 收尾动作的调用面（定死落点，回应 REV-2）

`DeployAdapter` 新增**一个 default 方法**。这不是无先例的发明：本接口已有同形先例 `stopServer`（`:185-187`，default 直接抛 `UnsupportedOperationException`，只由需要的适配器覆写）。

```java
/** 扩展阶段收尾：把「部署动作即起容器」的实例恢复到运行态，供后续 HEALTH_CHECK 按既有判据探测。
 *  默认不支持：本期仅 docker-compose / linuxgsm-docker 覆写（§14.13.1），
 *  集合外的声明已在 §8.1 判不合法，走到这里属实现缺陷 ⇒ 抛异常，绝不静默返回 true。 */
default boolean ensureRunningForExtension(Long instanceId, Map<String, Object> config) {
    throw new UnsupportedOperationException("该部署方式不支持扩展阶段收尾起回");
}
```

| 要点 | 判定 |
| --- | --- |
| 调用方 | `DeployExtensionExecutor` 收尾一行调 `adapter.ensureRunningForExtension(instanceId, config)`。`DeployService:163` 已按 deployType 解析好 adapter ⇒ **禁止**在 executor 里 `instanceof` 分派或自行拼 compose 命令（`getProjectName` / `getWorkDir` 两类里都是 private：compose `:1101`/`:1113`、lgsm-docker `:817`/`:829`） |
| 为什么不按评审建议在 `AbstractDeployAdapter` 给空实现 | 空实现 = 返回 `true` = **静默假装已起回**，恰好制造 §14.12 判掉的那个失效（`HEALTH_CHECK` 必挂）且无归因。抛异常是这条判定唯一诚实的默认值。**此项按「不采纳其形状、采纳其结论」处理**（§17 REV-2） |
| 接口面扩大的代价与封套 | 代价登记为 **RISK-D13（高）**：新增 default 方法对**实现者**二进制兼容（4 个既有适配器不改即可编译），但对调用者新增一个「只有两类能用」的能力位。封套 = 14.13.1 的声明期支持集合校验：集合外进不到这里。红线：**不得**把默认实现改成 `return true`，**不得**把 `adapter.start()` 全局改成 `up -d`（§14.12 实现红线延续） |
| §4 负向清单同步 | 原「三个适配器不改动」**作废**：compose / lgsm-docker 各 +1 覆写（既有方法一字不改），`DockerAdapter` / `LinuxGsmAdapter` 仍零改动。见 §3.3 组 L、§4 |
| 「无扩展部署行为不变」的落点 | 该方法**只被扩展分支调用** ⇒ 无步骤的游戏一行都不执行；判据写进 AC-15 / V-10（新增子项：`ensureRunningForExtension` 在无扩展部署中的调用次数 = 0）与 V-27 |

#### 14.13.3 两类各自的「起回」形状（逐类，不共用一段话）

| 项 | `docker-compose` | `linuxgsm-docker` |
| --- | --- | --- |
| 命令 | `cd <workDir> && COMPOSE_HTTP_TIMEOUT=300 timeout 1200 <composeCmd> -p <projectName> up -d`，与 `DEPLOY` 的 `:259-261` **逐字同形**（含 env 前缀与 shell `timeout` 兜底：`SshUtil` 的 `timeoutMs` 只作用于建连，命令本身无超时会永久阻塞部署线程），SSH 超时 `1200000` | `cd <workDir> && timeout 1200 <composeCmd> -p <projectName> up -d`，与该类 `deploy()` 的 `:222-223` 同形，SSH 超时 `1200000`。**两类不是同一条命令**：lgsm-docker 的既有 `up -d` 不带 `COMPOSE_HTTP_TIMEOUT=300`（该类只有 `stop()` 带）。本设计**逐类照抄各自 `DEPLOY` 已在用的形状**，不做统一——统一即改动既有类别的行为 |
| 就绪判定 | `sleep 5000` → `<composeCmd> -p … ps` 输出含 `running` 或 `Up`（镜像 `:270-287` 既有判定；V1 输出 `Up`、V2 输出 `running`，两者都要认），失败时取 `logs --no-color --tail 50` 并经 `stripAnsiCodes` 后写进失败原因 | `sleep 8000`（镜像该类 `deploy()` `:229-231` 的 entrypoint 等待）→ `ps -q` + **逐个**容器 `docker inspect -f '{{.State.Running}}'` 全为 `true`（镜像该类 `healthCheck` `:446-468` 的判据）。**判据必须与该类自己的 `HEALTH_CHECK` 同形**，否则收尾判过而 `HEALTH_CHECK` 判不过 |
| **禁止**复用 `ensureContainerRunning` | — | 该类 private `ensureContainerRunning`（`:955-993`）在「容器存在但已停止」分支走的是 **`compose start`**（`:970-973`）而非 `up -d`——正是 RISK-D11 否掉的形状；且它对 `ps -q` 的**第一个**容器判完即 `return`（`:979`），多容器项目根本不会逐个确认。所以本类的收尾**不复用它**。同时订正 §14.12 早先的表述：把它当「先例」指的只是「停后再起」这个动作存在，**不是**它的命令形状可用 |
| 起停次数 | `up -d`（DEPLOY）起 → 停 → 起 各一次（§14.12 结论不变） | 同左（`deploy()` 的 `up -d` 起 → `stop()` 停 → 收尾 `up -d` 起） |
| 后续阶段 | `HEALTH_CHECK` / `UPDATE_STATUS` / `START` / `retryHealthCheck` / `COMPLETE` 一字不改；`START` 面对已运行容器 = 今天的「空操作 + 复检」 | 同左；`START` 的 `adapter.start()` 仍 = `ensureContainerRunning`（此时容器已运行 → 短路）+ `linuxgsm start`，与今天 `DEPLOY` 之后的行为逐字相同 |
| 收尾是否重做 `DEPLOY` 末的回写 | **不重做**：`DEPLOY` 已写 `installPath` / `startCommand` / `stopCommand` / `runtimeMetadata`（`:291-300`），收尾只负责「运行态」这一件事 | 同左 |
| 失败处置 | **致命**：记 `stepEvent = FAILURE` + `level = ERROR` 的收尾行（收尾行归组规则见 §14.6 规则 5），部署判失败、实例 `ERROR`、不进入 `HEALTH_CHECK`。与 §14.5 停实例失败同口径；BR-14 只约束失败步骤的回滚边界，收尾不是补丁步骤、无回滚诉求 | 同左 |
| 幂等 | `up -d` 对已运行服务是空操作 ⇒ 即便「实例其实没被停下」这一病态情形，收尾也不会二次启动 | 同左 |
| 耗时预算 | 计入 §8.1 阶段预算：compose 类 ≤ 1200 s（shell `timeout`）+ 5 s；lgsm-docker 类 ≤ 1200 s + 8 s | 同左（数字不同） |

### 14.14 行 12（REV-1）：每主机互斥的真实承担者，与本期补法（Leader 裁定取 ①）

**先把事实钉正**（评审与 Leader 均已复跑）：`PatchInstallExecutor` 类头 Javadoc 原话是「同主机互斥（**由任务中心 `scopeKey=hostId` 承担**）」（`:35`），类内只有 `globalSemaphore`（`:56`）。互斥真正发生在 `TaskServiceImpl.submit`：`computeMutexKey` 默认规则 `taskType + ":" + scopeKey`（`:566-578`）+ 内存管理器 `taskMutexManager.putIfAbsent`（`:151`）。而 §14.1 的机制**恰恰是绕开 `submit` 直调 `execute()`**——被绕掉的正是这条互斥。v0.2 写成「原样继承」不成立。

**后果是具体的**（不是理论风险）：执行器在宿主机使用**非命名空间化**的共享临时路径——`/tmp/patch_install_<millis>`（`:141`，步骤末 `rm -rf`，`:227`）与 `/tmp/patch_push/<fileName>`（`:637`、`:649`，**仅按文件名**）。同主机两实例并发跑扩展步骤、或扩展步骤与用户手工发起的 `PATCH_INSTALL` 任务并发（**今天后者会被互斥键挡住，绕开后就挡不住了**），可互相覆盖同名文件 / 删掉对方正在使用的目录 ⇒ 交付错文件。

**修法取 Leader 裁定的 ①**，且**不新造一把锁**——复用既有的承键者：

```java
// core PatchInstallServiceImpl.installSync（v0.2 已定的「直调执行器」实现点，本期新增注入 TaskMutexManager）
String hostId = String.valueOf(instanceQueryService.getInstanceById(request.getInstanceId()).getHostId());
String mutexKey = "PATCH_INSTALL:" + hostId;      // 与任务中心默认规则算出的键逐字相同
String holder   = "EXT:" + instanceId + ":" + stepIndex;   // 非任务 ID，见下行「与任务中心的边界」
// 2 s 轮询占用，等待预算 600 s；等满 → 抛业务异常（步骤按 fatal 处置）
boolean held = false;
for (long waited = 0; waited < 600_000L; waited += 2_000L) {
    if ((held = taskMutexManager.putIfAbsent(mutexKey, holder))) break;
    Thread.sleep(2_000L);
}
if (!held) throw new BusinessException("等待同主机补丁互斥超时（600s），实例 hostId=" + hostId);
try { executor.execute(request, listener); } finally { taskMutexManager.remove(mutexKey, holder); }
```

| 要点 | 判定 / 依据 |
| --- | --- |
| 为什么用同一个 `TaskMutexManager` 而不是自建 `Map<hostId, Lock>` | 键与持键者同源，才谈得上「等价」：本设计起的锁**同时挡住**另一路扩展步骤与任务中心提交的 `PATCH_INSTALL` 任务；自建锁只能挡住前者，等于「每主机互斥」仍不成立（BR-09 字面要求即落空）。`TaskMutexManager` 是独立 `@Component`（`task/TaskMutexManager.java`），公开 `putIfAbsent` / `remove` / `isHeld`，**本期零改动** |
| 键格式的来源 | `scopeKey = String.valueOf(instance.getHostId())`（`PatchInstallServiceImpl:60`）+ 默认规则 `taskType + ":" + scopeKey` ⇒ `"PATCH_INSTALL:" + hostId`。**红线**：实现者必须逐字复用该拼接，不得改用 `hostId` 数字形式或带 source 前缀，否则与任务中心不同源、互斥失效 |
| 与任务中心的边界（不违反 D-N05） | 只借**内存键管理器**，不 `TaskService.submit`、不建 `TaskRecord`、不占任务中心线程池、不注册 Handler ⇒ 部署主流程与任务中心都不改。holder 用 `"EXT:…"` 前缀的**非任务 ID** 字符串：`removeByTaskId` 只由 DB 任务记录驱动（`TaskServiceImpl:325/:426`、`TaskPendingTimeoutScheduler:75`），拿不到这个键 ⇒ 不会把部署中的锁误释放 |
| 锁粒度 = 共享资源的粒度 | 只包住**单次 `execute()` 调用**，不跨整个扩展阶段、不跨 `SCRIPT` 步骤：两个共享路径都在一次调用内创建、消费、`finally` 清理 ⇒ 锁覆盖范围与资源生命周期一致。**红线**：不得「为省一次轮询」把锁提到阶段级——那会把 30 min 的脚本步骤算进持锁时间，把同主机的补丁能力拖死（登记 RISK-D14） |
| 等待预算取 600 s | 与等对象同量级：一个补丁任务自身的最坏耗时就是 `SSH_TIMEOUT_MS = 600_000`（`:54`）这一档（任务中心给该任务的总超时是 1 h，见 `PatchInstallHandler:33`）。取 30 s / 60 s 会把「同主机正在跑一个正常补丁」判成失败；不封顶则挂键不放。等满 → 该步 `FAILURE`（默认致命 → 部署失败），日志原因段指名「等待同主机补丁互斥超时」——**与超时失败同类可读，不静默降级为「跳过该步」** |
| 失败语义不新造闸门 | 这是 BR-09 已要求的互斥，不是 PRD 未要求的总耗时闸门，故不适用 §8.1「本期不加聚合上限」的理由 |
| 诚实限制（四条，全部进 RISK-D14） | ① 内存键、单进程有效（与 ADR-018 现状同，非本期新增缺口）；② 崩溃后 `TaskCrashRecoveryRunner.clear()` 启动时清空 ⇒ 重启即释放，与既有一致；③ **无排队公平保证**——多部署线程竞争同键可能饿（实际面受全局并发闸 3 约束）；④ 等待期间该部署线程被占住，叠加 RISK-D04 的 commonPool 长阻塞 |
| 评审的 ②（改判「本期不提供」） | **不取**（Leader 裁定）：那是需求口径收缩，须走人类，而无需收缩 |
| 评审的 ③（给临时路径加实例命名空间） | **本期不授权**（Leader 裁定）：动的是 ADR-0006 既有行为、风险半径超出本期。登记为**后续增量建议**（`/tmp/patch_install_<ts>` 加 `_<instanceId>`、`/tmp/patch_push/` 加实例子目录），交人类 Owner 决定是否另立 Issue；本设计不把它算作本期交付 |

### 14.15 行 14（REV-7①）：RISK-09 —— retry-deploy 会先 `uninstall`（含 `rm -rf workDir`）

PRD §15 RISK-09 明写「交 @Architect 明确」，v0.2 全文 0 次提到它。核对为真，且比 PRD 的转述更硬：

| 事实 | 证据 |
| --- | --- |
| `retryDeploy` 在重新部署前先调 `adapter.uninstall(...)`（忽略失败），再 `deployAsync` 全量重跑 | `InstanceServiceImpl:791-798`、`:809` |
| compose 类 `uninstall` = `compose down` → （可选 `down -v`）→ **`rm -rf <workDir>`** | `DockerComposeAdapter:555-573` |
| lgsm-docker 类同样 `rm -rf workDir` | `LinuxGsmDockerAdapter:541` |
| `configInfo` 那半边**安全**：`DEPLOY` 末的回写是既有 map 的拷贝再 put 系统键，不会冲掉 `deployVersion` | `DockerComposeAdapter:329`（`new HashMap<>(instance.getConfigInfo())`）、`:349` |

**四条结论**：

1. **retry-deploy = 干净重跑，补丁必然全量重放。** 宿主机工作目录被删空后重新 `up -d` 起容器，前一次扩展阶段落位的文件**不存在了**——所以本期不需要「跳过已执行步骤」的任何机制，也**不可能**有。幂等前提（BR-06）由此更吃紧：声明侧脚本必须能在「空目录 + 首次落位」与「已落位 + 重放」两种起点上都得到同一终态。
2. **BR-14 的「前序已成功步骤改动一律保留」显式限定为「当次部署内」**，**不跨 attempt**。v0.2 及 PRD 原文若不写这句，AC-21 / AC-09 的验收者在 retry-deploy 语境下会把「保留」读成跨 attempt 保留并判 FAIL。回写项 D-P10。
3. **`configInfo.deployVersion` 跨 retry 存续**（上表第 4 行已核对），故 §5.3 的 S3「既存键被重放」在 retry-deploy 上成立，AC-07 的判定对象不变；retry 后进入扩展阶段的次数 = 每次 retry 一次（起停次数与 §14.12/14.13.3 同形叠加，不新增语义）。
4. **不做任何「retry 前保留扩展产物」的手段**：那需要新造一套 stage 外缓存，PRD 未要求（N-08 已排除撤销/恢复控件），且与「retry = 干净重跑」这一既有语义相对抗。登记为 RISK-D12 的敞口说明。

## 15. OP-04 脚本 `timeoutMs` 缺省值与上限（拍板）

### 15.1 现有量级锚点（拍板依据，全部经代码核对）

| 锚点 | 值 | 出处 |
| --- | --- | --- |
| 宿主机命令通道默认超时 | 30 s | `plugin/.../service/FileAccessService.java:264`（`executeCommand(hostId, command)` 重载的字面量 `30_000L`） |
| compose 容器内执行 | 60 s 硬编码 | `adapter/DockerComposeAdapter.java:728` |
| docker 容器内执行 | 60 s 硬编码 | `adapter/DockerAdapter.java:476` |
| 补丁链路 SSH 命令 | 600 s | `patch/PatchInstallExecutor.java:54`（`SSH_TIMEOUT_MS = 600_000L`） |
| compose 起停 | 60 s / 120 s / 1200 s（`up -d` 用 shell `timeout`） | `DockerComposeAdapter.java:377`、`:395`、`:413`、`:260` |
| 任务中心部署任务超时 | 30 min | `task/DeployTaskHandler.java:50`（`DEFAULT_TIMEOUT_MS = 30*60*1000`） |

### 15.2 拍板结论

| 项 | 值 | 理由 |
| --- | --- | --- |
| **缺省值** | **`600_000 ms`（10 分钟）** | 扩展阶段的两类步骤必须处在同一量级：补丁步骤的既有 SSH 预算是 600 s，脚本取同值使「一步最多占用多久」对运维是**一个数**；取 30 s（宿主机通道默认）会把正常的版本脚本判成超时，取 60 s（容器通道现状）无依据且本期该通道不用 |
| **可声明上限** | **`1_800_000 ms`（30 分钟）** | 对齐仓内既有的最长部署预算锚点（`DeployTaskHandler:50` 的 30 min）。`deployAsync` 路径没有任何整体超时（F-04：不走任务中心），步骤级 `timeoutMs` 因此是**唯一**的上限护栏——不封顶就等于一个挂死的脚本能让部署永久停在 `INSTALLING(5)` |
| 下限 | `> 0`，且 `>= 1_000 ms` | 1 s 以下的脚本预算必然是配置错误，按声明不合法暴露比按超时失败暴露更早 |
| 越界处置 | **判声明不合法**，走 §8.1 → BR-12 既有处置（无键 → 默认版本 + 一条说明行；有键/显式选择 → 部署失败、`ERROR`） | 不做「静默夹到上限」。§8.1 的「禁止部分采纳」与 BR-04「主应用不得推断或覆盖声明」同口径：主应用替插件改超时就是覆盖声明 |
| 超时后的判定 | 步骤失败，按该步 `fatal` 处置（默认致命 → 部署失败）；日志行 `stepEvent = FAILURE` + `level = ERROR/WARN`，原因段取 ui-spec 的 `原因：脚本执行超过 〈timeoutMs〉 未返回，判失败` | FR-15 / §12「脚本超时」行的既有口径 |
| 生效范围 | 仅 `SCRIPT` 步骤（`position = host`）；`PATCH` 步骤的超时是执行器内部常量，本期不可声明 | 14.5 判定后 `container` 不存在；补丁链路的 600 s 是 `SSH_TIMEOUT_MS` 常量，参数化它属改 ADR-0006 既有行为，超出范围 |
| 实现落点 | `FileAccessService.executeCommand(hostId, command, timeoutMs)` **已支持显式超时**（`:254`），本期**零通道改造** | 这正是行 3「容器通道本期不改」不阻塞 OP-04 的原因 |

### 15.3 一条必须登记的诚实限制

`FileAccessService` 的超时语义是「不再等待」，**不保证远端进程已被终止**（SSH 通道关闭后，宿主机上的脚本子进程可能继续跑）。因此：

- 超时行日志必须写明「脚本可能仍在宿主机后台继续执行」，不得给运维一个「已终止」的错觉；
- 声明侧脚本**必须自带幂等与锁**（BR-06 已由声明方负责幂等，此处只是把「超时不等于已停」这条写进插件声明规范）；
- 本期**不**引入「扩展阶段结束时统一 kill 远端进程」的机制（需在宿主机维护 PID 台账，属新增能力）。登记为 RISK-D06。

**须回写 PRD（交 Leader，随 v0.6）**：§8.3 `timeoutMs` 行的「默认：待定（OP-04）」改为 `600000`，校验列改为 `1000 ≤ x ≤ 1800000，越界即声明不合法`；§12「脚本超时」行的「阈值取值待 OP-04」改为该缺省值；§16.2 OP-04 标记已关闭。

## 16. ADR-0008 声明机制落地与 `GET` 侧契约

### 16.1 一条决定承载方式的新代码事实（必须先说）

`getDeployConfigs()` 的读时合并**只作用于 VO 读取路径，不作用于部署执行路径**：

| 读者 | 是否看到插件声明 | 证据 |
| --- | --- | --- |
| 向导 / `getDeployConfig(gameId, deployType)`（VO） | **看到**（整节替换、插件优先） | `GameServiceImpl.java:195-201` |
| `GameVO.supportedDeployTypes` | 看到（选项合并） | 同上 `:237-255` |
| **实际部署用的 config** | **看不到** | `InstanceServiceImpl.buildDeployConfig` `:690-699` 直接 `gameMetadataMapper.selectById(...).getDeployConfig().get(deployType)`，读的是 `game_metadata` 表里扫描器落库的 yml 快照，未经过 `GameServiceImpl` 的合并 |

⇒ **结论：把版本目录挂进 `getDeployConfigs()` 会做出一个「向导看得见、部署看不见」的目录**，BR-12 / AC-20 / FR-12 全部落空（扩展阶段读不到条目，也就无从判定「所选 `versionId` 不在目录中」）。今天没人在这个坑里，只因为 F-08 说的「零使用者」。这是 ADR-0008 落地侧的既有不对称，本期把它登记出来（RISK-D07），并**不**沿用整节替换通道承载版本目录。

### 16.2 声明模型：两类声明、一个归属键、一条解析顺序

沿用 ADR-0008 的**体系**（同一扩展点、按 `gameCode` 归属、读取时合并、插件优先、不落库、热部署即生效），但为两类语义各给一个类型化入口，都加在 `GameEnhancementExtension`（`backend/plugin/.../extension/GameEnhancementExtension.java`，与 `getDeployConfigs()` L255-257 并列）：

```java
/** 静态目录：无实例上下文时（部署向导步骤 2）也要能读，故签名不含 instanceId */
default List<DeployVersionDeclaration> getDeployVersions(String deployType) { return List.of(); }

/** 动态步骤集：FR-05「可按实例配置动态计算」；默认返回空 ⇒ 由目录条目自带步骤承担 */
default List<DeployExtensionStepDeclaration> getDeployExtensionSteps(DeployExtensionContext ctx) { return List.of(); }
```

```java
record DeployVersionDeclaration(String versionId, String displayName, String imageTag,
                                Boolean defaultEntry,
                                List<PatchStepDeclaration> patches,
                                List<ScriptStepDeclaration> scripts) {}

/** 步骤集的公共上界（v0.3 新增定义体，回应 REV-4）：§16.2 的动态入口返回类型、
 *  BR-08 / AC-06 要求的「PATCH 与 SCRIPT 混排于同一有序清单」都由它承载。
 *  两个实现者就是已有的那两个 record ⇒ 目录条目的 patches ++ scripts 零转换即得一个有序混合清单。 */
public sealed interface DeployExtensionStepDeclaration
        permits PatchStepDeclaration, ScriptStepDeclaration {
    String label();          // 展示位（§14.6 stepLabel）
    boolean fatal();         // 致命性，默认 true（BR-04：主应用不推断不覆盖）
    StepKind kind();         // 执行器分派位（Java 17 无 pattern-matching switch，用显式 kind() 而非 instanceof 链）
}

enum StepKind { PATCH, SCRIPT }

record PatchStepDeclaration(String label, String url, String targetPath, String sha256,
                            String includePattern, String format, boolean fatal)
        implements DeployExtensionStepDeclaration {
    @Override public StepKind kind() { return StepKind.PATCH; }
}

record ScriptStepDeclaration(String label, String content, String url, String sha256,
                             ScriptPosition position, boolean fatal, Long timeoutMs)
        implements DeployExtensionStepDeclaration {
    @Override public StepKind kind() { return StepKind.SCRIPT; }
}

enum ScriptPosition { HOST, CONTAINER }   // CONTAINER 本期校验期拒绝（14.5）
record DeployExtensionContext(Long instanceId, String gameCode, String deployType,
                              String selectedVersionId, Map<String, Object> configInfo) {}
```

**新增类型清单（A 组，v0.3 计数订正）**：**7 个**——4 record（`DeployVersionDeclaration` / `PatchStepDeclaration` / `ScriptStepDeclaration` / `DeployExtensionContext`）+ 1 sealed interface（`DeployExtensionStepDeclaration`）+ 2 enum（`StepKind` / `ScriptPosition`）。v0.2 写「4 类型」是把 sealed interface 漏计、并把 `ScriptPosition` 与 `DeployExtensionContext` 少算，§3.3 组 A / §4 / §7.1 B-01 的清单同步改正。全部落在 `backend/plugin` 的 `extension.deploy` 包内（sealed 的 `permits` 要求同包），SDK 层零游戏语义。

| 关系与转换 | 判定 |
| --- | --- |
| 与 `DeployVersionDeclaration.patches` / `scripts` 的关系 | 两者元素类型即 sealed 的两个 permitted 子类型 ⇒ `Stream.concat(patches.stream(), scripts.stream()).toList()` **直接得到 `List<DeployExtensionStepDeclaration>`**，无包装、无适配层、无字段复制。v0.2 的「② 取条目 `patches ++ scripts` 按声明序编号」在缺这个上界时**拼不成一个 list**（只能拼成 `List<Object>`），这正是悬空类型的根因 |
| 与动态入口 ① 的关系 | `getDeployExtensionSteps(ctx)` 返回同一类型 ⇒ 解析顺序 ①② 两路汇入**同一个消费者**（`DeployExtensionExecutor` 按 `kind()` 分派），AC-18「两实例步骤集互不串用」在两条路上由同一套判定核对（V-24） |
| 为什么不用评审给的另一形态（单一 record + `type` 判别位） | 那样 `url` / `content` / `targetPath` / `position` / `timeoutMs` / `includePattern` / `format` 全挤进一个可选位大杂烩，§8.2 / §8.3 的两套必填与「正文 / URL 二选一」规则在同一个构造器上无法用类型表达，只能整体推到运行期校验。本期口径是「不合法即整目录 `INVALID`」，**能在编译期挡住的非法组合不该留到运行期**；sealed 之后插件侧写不出「PATCH 步骤带 position」这类形状 |
| 二进制兼容 | 纯加法；`DeployVersionDeclaration` 的两个 list 字段类型不变（仍是那两个 record），已按 v0.2 写过声明的桩插件 / `plugin-dnf-tw` 无需改动 |

字段口径与 §8.1 / §8.2 / §8.3 一一对应，**SDK 层不含任何游戏语义**（G-01 / BR-01 / N-01：`core/` 内 `"dnf_tw"` 字面量命中数仍须为 0，AC-23 ③）。

| 设计选择 | 判定 | 理由 |
| --- | --- | --- |
| 为什么是两个入口而不是一个 | 部署向导步骤 2 时**还没有实例**（`deploy.vue:206` 只带 `gameId`，实例在提交时才创建），而 FR-05 的步骤集要按实例配置算。一个签名无法同时服务「无实例可读」与「有实例才算」；硬塞 `instanceId` 可空参数会让校验时点（§8.1 声明读取期）在两个读者之间漂移 | 与 14.5 的判定同构：形状不同即入口不同 |
| 步骤集的**唯一解析顺序** | ① `getDeployExtensionSteps(ctx)` 非空 → 用它（代码计算型插件）；② 否则取所选目录条目的 `patches ++ scripts`，按声明序编号（纯声明型插件，dnf-tw 走这条）；③ 都空 → 不进入扩展阶段 | 只有一条链、优先级确定，AC-18（两实例不同版本 → 两步集互不串用）在 ① ② 两条路上同时成立 |
| 目录条目能不能既带步骤又不被选 | 能。`defaultEntry = true` 的条目带步骤 = 合法但**永不执行**（默认版本不写键、不进扩展阶段，BR-02 / §8.4.2 S2） | 不额外禁止，避免比 PRD 更严 |
| 声明来源是代码还是配置 | 代码（插件 JAR 内），非运行时可配 | §4.2「声明只出自插件代码」、RISK-08 缓解 |
| 桩插件的承载 | 同一个 `plugin-stub`（验收资产）实现 `getDeployVersions`，声明 ≥2 条目 + 混排步骤集；**不含任何游戏语义**、不进发布物 | FR-24 / AC-25 / AC-26 |

### 16.3 单一读者：`DeployVersionCatalogService`（core）

§8.1 的校验时点是「声明读取期」，而**三个地方**都要读目录。若各读各的，就会出现「向导以为可用 / 部署认为不合法」的分叉（RISK-13 的同类）。因此收敛成一个 core 侧组件，是三处唯一的读者：

```
DeployVersionCatalogService.read(gameCode, deployType) -> CatalogView
  CatalogView { List<VersionEntry> entries;          // 合法条目，保持声明序
                CatalogState state;                  // ABSENT | EMPTY | INVALID | AVAILABLE
                String invalidReason;                // 仅 INVALID 非空
                boolean availableForWizard(); }      // state == AVAILABLE && !entries.isEmpty()
```

| 状态 | 含义 | 后果（RISK-13 的分列要求） |
| --- | --- | --- |
| `ABSENT` | 无插件 / 未加载 / `getDeployVersions` 抛异常 | 向导不渲染（ui-spec 态 B）；无键 → 默认版本；有键 → BR-12 拦截 |
| `EMPTY` | 读取成功、0 条目（**dnf-tw 本期态**） | 向导不渲染（态 A）；**不产生任何「不合法」提示行**（AC-24 ③⑤） |
| `INVALID` | 有条目但未过 §8.1 校验 | 向导不渲染（态 C，解释行只在部署日志）；有键/显式选择 → BR-12 拦截 |
| `AVAILABLE` | 合法且 ≥1 条目 | 渲染控件（态 D–H） |

校验内容 = §8.1 全部规则（`versionId` 非空 / 唯一 / `[A-Za-z0-9._-]`、`default` 至多一条、条目内 §8.2 §8.3 必填与二选一）+ 本设计新增**五条**（v0.3 计数订正，并按 REV-5 / REV-3 补三条）：

| # | 规则 | 判定通道 | 出处 |
| --- | --- | --- | --- |
| N1 | `imageTag` 存在 ⇒ 该 deployType 的 `variables[]` 必含保留键 `PLATFORM_IMAGE_TAG` | **`game_metadata` 表快照**（与 `buildDeployConfig` 同一读法、同一 map；禁止走 `GameServiceImpl` 合并视图） | §14.4、§14.4.1 R1 |
| N2 | 表侧该 deployType 的 `composeTemplate` 必含字面量 `${PLATFORM_IMAGE_TAG`（不认 `$PLATFORM_IMAGE_TAG` 简写） | 同上（表快照） | §14.4.1 R3 |
| N3 | `imageTag` 与保留变量的 `defaultValue` 匹配 `[A-Za-z0-9][A-Za-z0-9._@/-]{0,127}`，禁空白 / 换行 / `${` / `}` | 声明侧（插件代码给出的值） | §14.4.1 R4 |
| N4 | `timeoutMs ∈ [1000, 1800000]`；`position` 本期只允许 `HOST` | 声明侧 | §15.2、§14.5 |
| N5 | **deployType 支持集合**：`deployType ∉ {docker-compose, linuxgsm-docker}` 而目录含任何条目带 `patches` / `scripts` / `imageTag` ⇒ 不合法 | 入参 `deployType`（本服务签名已有） | §14.13.1 |

**逐条校验、任一不合规即整目录 `INVALID`**（§8.1 禁止部分采纳）。`getDeployVersions` 抛异常归 `ABSENT`，不外泄到向导（AC-20 的构造手段之一）。N1/N2 的存在理由是 §16.1 那条通道不对称：**校验与部署必须读同一份数据，否则「校验通过而部署无效」在本期是可达状态**（v0.2 未钉通道时的缺陷，REV-5）。

### 16.4 `GET` 侧契约（向导读目录）

沿用部署向导**已在调用**的那个接口（`deploy.vue:240` 取 `data.variables`，服务侧 `GameServiceImpl.getDeployConfig` → `DeployConfigVO`），**不新增接口**（FR-20「不新增前端拉取通道」的同一口径）：

| 字段 | 类型 | 约定 |
| --- | --- | --- |
| `deployVersions` | `List<VersionEntryVO>` | 仅 `state == AVAILABLE` 时为合法条目；`ABSENT` / `EMPTY` / `INVALID` 时**为空数组**（不是 `null`，前端 `P1` 谓词因此是「条目数 ≥ 1」，与 ui-spec §5 一致） |
| `versionCatalogState` | enum 字符串 `ABSENT/EMPTY/INVALID/AVAILABLE` | 让前端与验收脚本能区分「空」与「不合法」（RISK-13 的可核对前提）；**界面是否使用它由 @Designer 定**，本期 ui-spec 的 A/B/C 三态渲染相同，因此前端可不读，仅验收核对用 |
| `versionCatalogReason` | String，仅 `INVALID` 非空 | 供部署日志说明行（§8.1 ① 的「校验失败要点」）与验收归因；不进界面 |
| `VersionEntryVO` | `{versionId, displayName, isDefault, stepSummary}` | `stepSummary` = 该条目的步骤预览（`[{index, label, type, fatal}]`），Designer 态 G 的「步骤预览展开」需要它，且只能在服务端算（步骤集解析顺序 16.2 在 core 侧） |

`POST` 侧（提交部署）：**无新增字段**——版本选择沿用 `configInfo` 扁平载荷，键 `deployVersion`（§8.4），由 `applyVersionSelection` 决定写/删/不写（14.10）。校验分界见 14.10 末段（提交期只做撞键校验，不做目录可用性校验）。

### 16.5 对 FR-22 措辞的偏离与回写建议（不自行取舍，交 Leader）

FR-22 字面是「通过 **ADR-0008 声明接口**提供 dnf-tw 版本目录」。本设计把它落在**同一扩展点的类型化入口**（`getDeployVersions`）而非 `getDeployConfigs()`，依据是 16.1 的代码不对称（整节替换 + 部署路径不读合并）：按字面做会得到一个部署期读不到的目录，AC-20 与 AC-24 同时不成立。这不是改写决策 1–9 中任何一条（九条决策都只说「版本目录放插件声明 / ADR-0008 读时合并体系」，未指定方法名），但对 FR-22 的读者是一次口径变化，建议回写：

- FR-22 / §5.1 第 3 项：「通过 ADR-0008 声明接口」→「通过 ADR-0008 声明**体系**（同一扩展点 `GameEnhancementExtension`、按 `gameCode` 归属、读取时合并、插件优先、不落库、热部署即生效）」；
- §14.1 的 ADR-0008 行与 F-08：补记 16.1 的不对称（`buildDeployConfig` 读表、不经合并），并把「首个使用方」的对象由 `getDeployConfigs()` 改述为「ADR-0008 体系的首个使用方」；
- 是否同时把 `getDeployConfigs()` 的通道缺陷另立 Issue：**建议另立**（与 OP-07 同口径，与本期 AC 无耦合，本期无任何条目依赖它）。

## 17. 评审回应

（复审时填）

## 18. 修订记录

| 版本 | 日期 | 作者 | 变更摘要 |
| --- | --- | --- | --- |
| v0.1 | 2026-09-20 | Architect | 骨架先行：章节基线 + §14.2 八行待判定清单 |
| v0.2 | 2026-09-20 | Architect | **全文定稿**（对应 Leader 派单 `01a0bd7f` 的抗中断要求：分次落盘，每次一节）。<br>**§14 待改依赖由八行扩到十一条并逐行给结论**：① 桥接 = SDK 新增 `installSync` 直调执行器（并修正 PRD 对该依赖的前提——执行器本就阻塞）；② `includePattern` 取「同链路内执行」自动生效、`headers` 结构性不提供；③ 容器脚本通道本期不改（因⑤）；④ **硬判定① 给出 `imageTag` 机制**（保留变量 + 既有 `.env` 链，注入点在 `buildDeployConfig`）⇒ **AC-14 转可验收**；⑤ **硬判定② 判 `position = container` 不合法** ⇒ **AC-13 移出本期**（含决策 7 的条件性收缩）+ 停实例语义与停失败致命处置定稿；⑥ **硬判定③ 日志呈现契约三项定稿**（`stepId`/`stepEvent`/`elapsedMs` 毫秒 + 归组判据）⇒ KPI-02 / AC-03 / AC-16 脱离「不可测」；⑦ 进度条件分配 `[80,84]`（无步骤游戏零改动）；⑧ BR-16 取合并式写入（AC-27 四项无需拒绝分支即通过）；⑨ `level → 视觉映射` 前端归一化（含 `warn ≠ warning` 的补充事实）；⑩ 新发现 AC-22 的界面入口不存在（A/B 案交 Leader）；⑪ **新发现阻断级冲突**：FR-11 停实例 vs `HEALTH_CHECK` 探测运行态 ⇒ 判「扩展阶段收尾按依赖顺序起回容器」，顺带收口 `compose start` 不处理 `depends_on` 的隐蔽失效。<br>**§15 OP-04 拍板**：`timeoutMs` 缺省 600s、合法区间 `[1s, 30min]`、越界即声明不合法、超时不等于远端进程已终止。<br>**§16 声明模型与 GET 侧契约**：登记新代码事实「`getDeployConfigs()` 的读时合并只作用于 VO，不作用于 `buildDeployConfig`」⇒ 版本目录走同一扩展点的 `getDeployVersions` / `getDeployExtensionSteps`，唯一读者 `DeployVersionCatalogService` 四态（`ABSENT/EMPTY/INVALID/AVAILABLE`），并给出对 FR-22 措辞的偏离与回写建议。<br>**§3–§13**：建议改动 11 组、受影响组件与负向清单、零数据库变更判定、对外/SDK/日志三层契约、按角色的实现步骤（B-01…B-15 / F-01…F-05 / T-01…T-04）、非功能（耗时预算、输出截断、脚本落文件执行的安全形状）、迁移与回滚、验证计划 V-01…V-20、AC-01…AC-27 全量追溯、RISK-D01…D11、待回写清单 D-P01…D-P09。<br>**未改动**：已确认决策 1–9 全部保持；未改 `prd.md`；未新增任何 dnf-tw 真实版号 / URL / 目标路径（全文 `http(s)://` 字面量命中数 0）。 |
