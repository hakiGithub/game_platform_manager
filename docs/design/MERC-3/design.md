# 技术设计 — MERC-3 部署扩展步骤与 dnf-tw 指定版本部署

| 字段 | 值 |
| --- | --- |
| **创建者** | Architect-41fff2de |
| **创建时间** | 2026-09-20 |
| **版本** | v0.3.4 |
| **状态** | 第 2 轮复审 **PASS 已接受（条件解除）**；v0.3.2 / v0.3.3 两层门禁齐、无前置条件带入 G1。**本版 v0.3.4 = G1 零轮次记账批**（不占评审轮次、不开新判定）：落 SUG-20 表末格标记、SUG-22 自查块排序、元数据「平行输入」锚点随 UI 定稿统一（G1-b）三件；**SUG-21（`stepId == null` 两行的 `stage` 取值）经核对与 v0.3.3 既有内容相撞，本轮不落判定文本、登记待 Leader / @Architect 一句话裁定**——§14.6 `stage` 行 / `stepId` 行、§10 V-08 判据、§3.2 `:81` 存在条件**四处一字未动**。待 Leader 通用门禁复跑（本批只核记账三件 + 回归 + 对 SUG-21 作裁定） |
| **Issue** | MERC-3 |
| **上游 PRD** | docs/prd/MERC-3/prd.md @ 270f9d0（v0.5，双层门禁 PASS） |
| **上游 ADR** | docs/design/adr/0029-deploy-extension-steps.md @ 590af8d（远端 `agent/leader/chat-1cfa252af963`，实现分支须 merge 带入） |
| **平行输入** | docs/ui/MERC-3/ui-spec.md + `index.html` @ `62b49a9`（v0.6，S1b 定稿；分支 `agent/designer/merc-3`，30 屏）——**锚点随 UI 定稿自 `f6312ef`（v0.1）统一，走 G1-b**。**该行携带的交叉引用在换锚点时一并复核、并据复核结果改述**：本文 §14.4 / §14.9 / §14.11 已**给出**其 §10-B 三项界面侧前提的收口结论，逐节归属复核为真（B1 `level → 视觉映射` ↔ §14.9；B2 `imageTag` 落位失败 ↔ §14.4；B3 阶段带 / 「扩展」步骤点驱动源 ↔ §14.11）。**但「已收口」在 UI 侧尚未成立**：`ui-spec.md @ 62b49a9` 的 §10-B 抬头仍明写「不并收，只登记……判门权在 Leader……未定稿前不得自行引入」，v0.6 的 §13.4「不得动」行亦原样保留 B1/B2/B3 的未定稿状态。⇒ 本行的准确读法是「**本文已给结论、待 Leader 判门后由 UI 侧一次性并收**」，不是既成事实。（此格改写正是 G1-b 点名要求的那一步：只换 hash 会把一条当时正确的断言静默变成错的。） |
| **交付分支** | `release/MERC-3-deploy-extension-steps` |

> 本文按「先落盘再细化」分次提交：v0.1 骨架（§1/§2 + 待判定清单）→ v0.2 补齐 §3–§16 → **v0.3 并入架构评审第 1 轮（REV-1…REV-7 全采纳 + SUG-1…SUG-9）** 与 Leader 的两项范围裁定（每主机互斥取「补一层等价互斥」、EXTENSION 支持集合按 FR-11 字面收口为 compose 两类）→ **v0.3.1 并入架构评审第 2 轮新发现的九条（SUG-10…SUG-18）定点落文** → **v0.3.2 并入第 2 轮定点确认新增的两条（SUG-19 / SUG-20），改法按 Leader 裁定 `01a0bf2b` 定死** → **v0.3.3 按 Leader 裁定 `01a0bf39` 把 §10 V-08 与 §14.6 规则 2 的两处六项说明性括注各补一项成七项（同一集合的项数同步，无新判定）** → **v0.3.4 = G1 零轮次记账批**（SUG-20 表末格补已授权标记 + SUG-22 自查块排序 + 元数据「平行输入」锚点随 UI v0.6 定稿统一并复核该行携带的交叉引用；**SUG-21 经核对与 v0.3.3 既有内容相撞 ⇒ 只登记、不改判定文本，交 Leader / @Architect 裁定**）。
>
> **v0.3 的三处实质变化**（其余为同步）：① §14.14 —— BR-09 的每主机互斥**过去被写错**：真实承担者是任务中心的内存互斥键，绕开任务中心即绕开它，本期由同步入口自行承键补上（不改 `PatchInstallExecutor`、不引入任务中心）；② §14.13 —— 扩展阶段收尾「把容器起回」原先**没有可调用的面**，且该结论对三类容器适配器同时成立，现定死为 `DeployAdapter` 一个 default 方法 + **逐类**收口形状，支持集合钉为 {`docker-compose`, `linuxgsm-docker`}，集合外声明不合法；③ §14.15 —— PRD 点名交本设计的 RISK-09（retry-deploy 先 `uninstall`）收口，`BR-14` 的「保留」自此显式限定为**当次部署内**、不跨 attempt。逐条处置见 §17。

> **v0.3.1 的性质**：九条全是**文档与代码对齐**——改动落点的模块归属、`deploy.vue` 与 `deploy-plugin.sh` 的行号、`LogEntryVO` 的真实位置、`Stream.concat` 的可编译性判据、KPI-02 分母的字面可满足性、互斥依据里的一句假话（`patch_push` 的 `finally` 清理）、R1 里一个不可强制的措辞、组 L 就绪判定的来源、AC-15 比对面漏掉的一项。三条硬判定的**结论**、OP-05 / AC-22 等已在册口径、§14.0.1 已关闭项的状态**一律未动**；§14.6 的契约字段表本身未动。逐条处置见 §17 第 2 轮九行。
>
> **v0.3.2 的性质**：两条都是**本文内部三套说法对撞的收口**，不是新判定——① §14.12 的收尾「成功支」与 §14.6 `stepEvent` 行、§3.2 时序互相矛盾（根因：Leader 裁「两支都产行」时未同时点名 §14.12 这个归属节），现按裁定取「两支都产行」，落点 §14.12「改什么」行与 §8.3「每步必得行」行的量级估算；② §14.6 `stepId` 行的「定死」枚举少一类（`elapsedMs` 行与 §3.2 都已把**阶段完成行**当既存阶段级行使用），现补成七项、**不降为示例**。§3.2 时序与 §14.6 `stepEvent` 行一字未动（Leader 明令）；三条硬判定的结论、九条已核销的落点、§14.0 / §14.0.1 十四行、`prd.md` 全部未动。逐条处置见 §17「第 2 轮定点确认新增两项」。
>
> **v0.3.3 的性质**：**同一集合的项数同步**，不是新判定、不是新口径——v0.3.2 把 §14.6 `stepId` 行的「定死」枚举补成七项时，按「不自行扩面」把 §10 V-08 判据与 §14.6 规则 2 判据后的两处说明性括注留作六项并点名交 Leader 判（§17 SUG-20 行末格）；Leader 在 v0.3.2 通用门禁里裁定「补成七项，出 v0.3.3」，边界写死为**不改判据、不改分母口径、不动其它任何一节**（裁定 `01a0bf39`）。本版因此只加两个词：两处括注各补**阶段完成行**一项。KPI-02 的分母口径与「`stepId == null` 的阶段级行不进分母」这条判据**一字未动**（两处的排除靠的从来是判据本身，不是括注的项数——与 SUG-20 的判定同一句话）；§14.0 / §14.0.1 十四行、三条硬判定结论、九条与两项已核销落点、`prd.md`、`docs/ui/MERC-3/*` 全部未动。逐条处置见 §17「v0.3.3 一处同步」。

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
| G 日志与呈现 | `LogEntry` / `LogEntryVO` 新增**六个字段位**（`stepId` / `stepIndex`+`stepTotal` / `stepLabel`+`stepType` / `stepEvent` / `elapsedMs` / **`exitCode`**，v0.3 补 REV-6）——其中两个字段位各含两个属性（`stepIndex`/`stepTotal`、`stepLabel`/`stepType`），**在 VO 上展开为 8 个属性**（SUG-18）；`LogEntryVO`（`InstanceController:889-896`）与 `DeployProgressVO`（`:875-884`）**都是 `InstanceController` 的嵌套静态类**，`backend/api` 的 `vo/` 下没有 `LogEntryVO`（SUG-10）；`DeployProgressVO` 顶层新增 `stage`；`mapStageToStatus` 加 `EXTENSION → installing`；`DeployService` 进度字面量在扩展分支内条件分配 `[80,84]` + `HEALTH_CHECK` 顶层 `progress` 起点 85（§14.6 / §14.7） | `backend/core`（**单模块**，不含 `backend/api`） | 新增可选字段（默认 `null`） |
| **H 前端** | `DeployProgress.vue`：`level` 归一化（含 `warn → warning` 别名）、阶段带与「扩展」步骤点由 `logs[].stage === 'EXTENSION'` latch 驱动、步骤行按 ui-spec §6.2 词面渲染、**耗时先把 computed `formattedElapsedTime` 参数化为 `formatElapsed(seconds)` 再按 `max(1, round(ms/1000))` 秒渲染**（§14.6 单位口径，v0.3 补 SUG-6）；`deploy.vue`：步骤 2 版本选择控件 + 步骤 5 摘要行（P1/P2 谓词） | `frontend` | 改动 |
| I 保键 | `InstanceServiceImpl.updateInstance` 的 `copyProperties` 整表替换改合并式写入（§14.8） | `backend/core` | 改动一处（语义变化面窄） |
| **J 交付载体** | 新建 `backend/plugin-dnf-tw`（聚合 pom + `-core` JAR 子模块，**无前端**）：`DnfTwPlugin` + `DnfTwExtension`（`getGameCode() = "dnf_tw"`）+ `getDeployVersions()` 返回**未填充占位模板**（读取结果 = 空目录）+ 模板内显式标注「占位模板，不可上线」；`backend/pom.xml` `<modules>` 加一项 | `backend/plugin-dnf-tw` | **新增模块** |
| K 验收资产 | `plugin-stub`（仅声明版本目录 + 混排步骤集，无游戏语义）+ 受控补丁包/脚本夹具 + 桩游戏元数据经外置 `./games` 目录投放（不进 core resources、不进发布物） | 测试资产 | 新增（非产品模块） |
| **L 起回调用面（v0.3 新增，回应 REV-2/REV-3）** | `DeployAdapter` **新增一个 default 方法** `ensureRunningForExtension(instanceId, config)`（默认抛 `UnsupportedOperationException`，与既有 `stopServer` 同形）；`DockerComposeAdapter` 与 `LinuxGsmDockerAdapter` **各 +1 覆写**，**命令与就绪判定分两列照抄、来源不同**（SUG-15）：**命令**逐类照抄各自 `DEPLOY` 已在用的那一条；**就绪判定** compose 类照抄其 `DEPLOY` 的 `ps` 认 `running`/`Up`（`:270-287`），lgsm-docker 类**不**照抄自己 `DEPLOY` 的 `ps` 判定（`:234-239`）而取该类 `healthCheck` 的**逐个容器** `.State.Running`（`:430-468`）——两列的取值见 §14.13.3 表；`DockerAdapter` / `LinuxGsmAdapter` / `AbstractDeployAdapter` **零改动**（不给空实现——返回 `true` 等于静默假装已起回） | `backend/core` | 接口 +1 方法（实现者二进制兼容）；两个适配器各 +1 方法，**既有方法一字不改** |

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
| `backend/core/.../controller/InstanceController.java` 的**两个嵌套静态类**：`LogEntryVO`（`:889-896`）与 `DeployProgressVO`（`:875-884`）；`LogEntry → LogEntryVO` 的映射在 `:497-503` 的 `status.getLogs().stream().map(...)`（`vo.setLogs` 在 `:506`）；`backend/core/.../vo/DeployConfigVO.java` | 加可选字段 | 低（前端不读即无变化） | @BackendDev |
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
| `PLATFORM_IMAGE_TAG` | **v0.3 订正（SUG-4）：它确实会成为真实的 `configInfo` 键**，不是「只在临时 map 里」。依据：§14.4 要求声明 `imageTag` 就必须把它声明成该 deployType 的 `variables[]` 一项，而 `deploy.vue` 对**全部**变量（含 `hidden`，`:246-250` 回填 `defaultValue`）做 `...deployVariablesValues` 展开进 `configInfo`（展开在 **`:714`**，其守卫 `...(isComposeVariableDeploy()` 在 `:713`）⇒ **（compose 变量类部署下）**向导提交即带上它；该展开受 deployType 门控，`docker` / `linuxgsm` 两类不会带上（v0.3.1 按 SUG-17 补限定与行号）。**后果可控的理由**：第 5.5 步在「该游戏声明了这个键」时**一律由平台写值**（条目 `imageTag` 或该变量的 `defaultValue`），用户 / 通用写接口提交的值**不参与任何判定、不进 `.env`**（§14.4 表「框架侧唯一新增」+ §14.4.2） | 因此 §8.4「声明只出自插件代码、无用户输入面」这句话**在本键上不再无条件成立**——它成立是因为平台不采信，而不是因为面上没有键。取值格式校验见 §14.4.1 R4 |
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

`DeployTaskStatus.logs` 的元素 `LogEntry` 增加 §14.6 的**六个字段位**（v0.3 含 `exitCode`；`stepIndex`/`stepTotal` 与 `stepLabel`/`stepType` 各占一位、各含两个属性 ⇒ 在 `LogEntry` 与 `LogEntryVO` 上都是 **8 个属性**，SUG-18）；`DeployProgressVO` 顶层增加 `stage`。`taskStatusMap` 的生命周期、淘汰策略、轮询频率**全不变**；新增的输出体量约束见 §8.3。

### 5.5 实例运行状态

与 PRD §9 一致，两处必须写明：① 扩展阶段期间 `run_status` 恒为 `INSTALLING(5)`（`ensureStoppedForExtension` 只调适配器、不回写 STOPPED，§14.5）；② 扩展阶段**内部**容器是停止的（FR-11 的判定点），收尾会按依赖顺序把它起回来，因此 `HEALTH_CHECK` 的判据与时点、`UPDATE_STATUS` 只写库、`START` 面对已运行容器这三条既有表现全部不变（§14.12）。**③（v0.3 补）本段全部结论只对 `docker-compose` / `linuxgsm-docker` 两类成立**——「停 → 起回」的调用面是 `DeployAdapter.ensureRunningForExtension`，两类各自的命令形状与就绪判定不同（§14.13.3）；`docker` / `linuxgsm` 两类本期**不允许声明扩展步骤**，故其运行状态语义与今天无任何差异（§14.13.1）。

## 6. 接口与契约边界

### 6.1 对外 HTTP 契约（三条，全部向后兼容）

| 接口 | 变化 | 兼容性 |
| --- | --- | --- |
| `GET /api/games/{gameId}/deploy-config/{deployType}`（`GameMetadataController:85`；**`deployType` 是路径段，不是 query 参数**——v0.3.1 按 SUG-10 订正本表原写法） | 响应增加 `deployVersions[]`、`versionCatalogState`、`versionCatalogReason`（§16.4） | 新增字段；老前端不读即无变化；空目录返回 `[]` 而非 `null` |
| `POST /api/instances`（向导提交） | **无新增字段**：版本以 `configInfo.deployVersion` 承载 | 载荷形状不变；提交期只新增 BR-07 撞键拒绝（400 + 可辨识原因） |
| `GET /api/instances/{id}/deploy-progress` | `DeployProgressVO.stage` 新增；`logs[]` 每行新增**六个字段位、展开为 8 个 VO 属性**（`stepId` / `stepIndex` / `stepTotal` / `stepLabel` / `stepType` / `stepEvent` / `elapsedMs` / `exitCode`，v0.3 含 `exitCode`，§14.6；SUG-18） | 非扩展部署这些字段恒为 `null`/既有集合不变 |

**扩展字段只在 `deploy-progress` 这一条通道结构化（v0.3.1 补 SUG-10）**：另一条读日志的接口 `GET /api/instances/{id}/logs`（`InstanceController:372`）在实例处于 `INSTALLING` 时确实会取到同一批部署日志，但它把 `LogEntry` **摊平成文本行**（`[time] [level] message\n`，摊平循环在 `:392-396`，整段 `:388-396`），`stepId` / `stepEvent` / `elapsedMs` / `exitCode` 在该通道**不出现**。⇒ KPI-02 / AC-03 / AC-16 的机械核对（V-08）与界面核对（V-23）**一律只走 `deploy-progress`**；验收者不得到 `/logs` 里找扩展字段并据此判「字段缺失」。

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

§14.6 即 KPI-02 / AC-03 / AC-12 / AC-16 的核对契约，**以本节为登记处**：**六个字段位**（VO 上 8 个属性，SUG-18）、`stepEvent` 取值、**五条**归组与判定规则、耗时单位（`elapsedMs` 毫秒）、退出码承载位（`exitCode`）、`stdout`/`stderr` 的**非判据**地位、`stage = "EXTENSION"` 常量。UI 词面归 ui-spec §6.2（`docs/ui/MERC-3/ui-spec.md` @ `f6312ef`，S1b v0.4 在途），两者关系：契约是判据，词面是渲染。

## 7. 实现步骤

> 里程碑：**M1 SDK 与目录 → M2 执行管线 → M3 前端与契约 → M4 交付载体与验收资产**。同一里程碑内可并行，跨里程碑按依赖串行。G1 之后才开 S2a（@BackendDev 的 API 契约细化）与 S2b（@Tester 功能用例）。

### 7.1 @BackendDev — `backend/plugin`（M1）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| B-01 | 新增 **7 个类型**：`DeployVersionDeclaration` / `PatchStepDeclaration` / `ScriptStepDeclaration` / `DeployExtensionContext` / `ScriptPosition` / **`DeployExtensionStepDeclaration`（sealed interface）** / **`StepKind`**（定义体与 `kind()` 见 §16.2，v0.3 补 REV-4） | 编译通过；无 `dnf_tw` 字面量（AC-23 ③ 前提）；**两个步骤 record 是 sealed 的 permitted 实现，且 `Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList()`（带显式类型见证）可赋给 `List<DeployExtensionStepDeclaration>`**（类型闭合即本条判据；v0.3.1 按 SUG-11 改述——**不带**类型见证的字面写法在 javac 17 下编译失败，见 §16.2 推导规则） |
| B-02 | `GameEnhancementExtension` 加两个 default 方法（返回空集合），**不改任何既有方法签名**；`getDeployExtensionSteps` 的返回类型即 sealed 上界 | plugin-l4d2 无需改动即可编译；`install()` 行为未变 |
| B-03 | `PatchInstallService.installSync` default 方法 + `PatchInstallProgressListener` | 既有 `install()` 调用点零改动 |

### 7.2 @BackendDev — `backend/core`（M1→M3）

| # | 步骤 | 完成判据 |
| --- | --- | --- |
| B-04 | `PatchInstallServiceImpl` 实现 `installSync` = **承 `PATCH_INSTALL:<hostId>` 互斥键（`TaskMutexManager.putIfAbsent`，2 s 轮询 / 600 s 预算 / `finally` 释放）→ 直调 `PatchInstallExecutor.execute()`**（请求对象引用透传） | `includePattern` 生效（V-07）；并发/重试常量未被复制；**V-26：同主机两路补丁不重叠、与任务中心提交的一路也不重叠、等满 600 s 判该步失败、异常路径后键已释放**（BR-09 / §14.14） |
| B-05 | `DeployVersionCatalogService`：读取 + §8.1 全量校验 + **§16.3 的 N1…N5 五条规则**（其中 N1/N2 的判定通道**必须绑 `game_metadata` 表快照**、与 `buildDeployConfig` **同一数据源与同一取值路径**；N5 = deployType 支持集合），返回 `CatalogView` 四态 | `EMPTY` 与 `INVALID` 可区分（RISK-13）；`getDeployVersions` 抛异常归 `ABSENT` 不外泄；**V-02 补两条反例**：插件经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` **不得**让 N1/N2 判过（§14.4.1 R1）、表侧模板缺 `${PLATFORM_IMAGE_TAG` 即 `INVALID`（R3） |
| B-06 | `GameServiceImpl` 把 `CatalogView` 汇入 `DeployConfigVO`（§16.4）；**不动** `getDeployConfigs()` 的整节替换 | 无扩展声明游戏的 `deploy-config` 响应逐字段与现状一致 |
| B-07 | `applyVersionSelection` 三态纯函数 + 提交期 BR-07 撞键校验（清单 = PRD §8.4.3 原三项：`variables[].name` ∪ 三系统键 ∪ `gameVersion`；**`PLATFORM_IMAGE_TAG` 不加入该清单**——它会作为声明期保留变量合法地出现在提交载荷里，加进去等于把 AC-14 的正向路径判 400，§14.4.2） | 单测覆盖三态；**目录不可用时不在提交期 400**（§14.10 末段）；**新增反例断言**：声明了该保留变量的游戏提交带该键 ⇒ 不 400 且部署侧不采信其值 |
| B-08 | `buildDeployConfig` 第 5.5 步 `imageTag` 注入（**两级门控 + 仅当 `configInfo` 含 `deployVersion` 才读目录**，§14.4 / §14.4.3） | 未声明该保留变量的游戏 `.env` 与模板逐字节不变；**声明了的游戏：用户提交的该键值不出现在 `.env` 里**（V-15 第四核对物）；无 `deployVersion` 键时不触发任何目录读取与 SPI 调用（V-29） |
| B-09 | `DeployExtensionExecutor`：解析步骤集（16.2 解析顺序，**元素类型 = sealed 上界，按 `kind()` 分派**）→ `ensureStoppedForExtension()`（3×2s 判定，失败即致命）→ 顺序执行 → 每步日志行按 §14.6 契约**五条规则**（含 `exitCode` 与 `ROLLBACK` 记录位）→ **收尾调 `adapter.ensureRunningForExtension(...)`**（不在本类内拼 compose 命令，§14.13.2） | 单测：致命失败后续步骤不执行；非致命失败继续；`SCRIPT` 步骤终态行带 `exitCode`、`PATCH` 步骤 `exitCode == null`；`PATCH` 失败时 `ROLLBACK` 行位置符合规则 5；**executor 内无 `instanceof DeployAdapter` 分派**（V-13 代码审查项） |
| B-10 | `DeployService`：`:214` 后插阶段（`DeployExtensionExecutor` 用已解析的 adapter，§14.13.2）；条件进度 `[80,84]` + 该分支内 `HEALTH_CHECK` 顶层 `progress` 起点 85（§14.7 订正：不是「进入行」）；`mapStageToStatus` 加 `EXTENSION → installing` | 无步骤路径的进度值与阶段序列**逐值不变**（AC-15）；`HEALTH_CHECK` / `START` 代码未改一行；**`ensureRunningForExtension` 在无扩展部署中的调用次数 = 0**（V-10 新增子项） |
| B-11 | `LogEntry` / `LogEntryVO` / `DeployProgressVO` 字段扩展（§14.6 / §6.1）——**三处都在 `backend/core`**：后两个是 `InstanceController` 的嵌套静态类（`:889-896` / `:875-884`），新增六个字段位在 VO 上是 8 个属性，`api` 模块不需要新建 VO 也不需要搬迁（SUG-10 / SUG-18） | 既有阶段新字段全 `null` |
| B-12 | `InstanceServiceImpl.updateInstance` 合并式写入（§14.8） | AC-27 四项逐条通过；单测注明「省略键不再等于删键」 |
| B-13 | 脚本执行安全形状：正文/URL 脚本一律**平台侧下载 → sha256 校验（声明了才校验）→ SFTP 上传到 `<workDir>/.platform-extension/E-<n>.sh` → `bash <file>` 执行 → `finally` 删除** | 命令文本里不出现脚本正文（RISK-08 缓解）；未校验通过不在宿主机留文件 |
| B-16 | **（v0.3 新增，组 L / REV-2）**`DeployAdapter` 加 default 方法 `ensureRunningForExtension`（默认抛 `UnsupportedOperationException`）；`DockerComposeAdapter` 与 `LinuxGsmDockerAdapter` 各 +1 覆写。**命令与就绪判定是两件事、来源不同（v0.3.1 按 SUG-15 分列，原「命令与就绪判定逐类照抄各自 `DEPLOY`」对 lgsm-docker 的就绪判定不成立）**：**命令**逐类照抄各自 `DEPLOY` 在用的那一条（compose `up -d` 带 `COMPOSE_HTTP_TIMEOUT=300`、lgsm-docker 不带）；**就绪判定** compose = 其 `DEPLOY` 已在用的 `ps` 认 `running`/`Up`（`:270-287`），lgsm-docker = **不是**它 `DEPLOY` 的 `ps` 判定（`:234-239`），而取该类 `healthCheck` 的 `ps -q` 后**逐个**探 `.State.Running`（`:430-468`）——即「收尾判据 = 紧随其后的 `HEALTH_CHECK` 判据」，比它自己的 `DEPLOY` 判定更严，故不可能「收尾判过而 `HEALTH_CHECK` 判不过」。两列取值以 §14.13.3 表为准 | 两类各自 V-27 通过；`DockerAdapter` / `LinuxGsmAdapter` / `AbstractDeployAdapter` 零改动；既有 14 个方法签名与实现一字不改；**默认实现仍抛异常**（grep 判据：`return true` 不出现在该方法的默认实现里）；**不引用 private `ensureContainerRunning`** |

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
| T-04 | 回归基线：**改造前**先在 `main` 跑一次并登记（① 后端 `cd backend && mvn test` 的部署/补丁相关用例清单与通过数；② 前端 `cd frontend && npm run test:run`；③ `cd frontend && npm run e2e` 受管模式用例清单），**改造后重跑同一命令集**（§10 V-25，v0.3 补 KPI-04 的核对物） | AC-15 判定表 + KPI-04 分子/分母存档（当次采集，L-01）；**比对面不含 CSS class 与图标名**（v0.3 补 SUG-9：`level` 归一化后此前同为 `log-info` 的非 INFO 行开始变色，若把它算作回归差异会与 RISK-D03 的「缺陷修复的正当外溢」定性自相矛盾）——比对面限定为**阶段序列 / 顶层 `progress` 值 / 日志行的 `stage`+`level` 原值 / 终态 / 状态转移（同频轮询 `GET /instances/{id}` 记下的 `runStatus` 序列）**五项（v0.3.1 按 SUG-16② 补第五项，与 V-10 同步；基线须一并登记该采样） |
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
| 每步必得行 | 三行（14.6），阶段级另有进入 / 收尾 / 完成 / 交棒**四行**（v0.3.2 按 SUG-19：收尾两支都产行，成功支的 `SUCCESS` 行承载 §8.1 的收尾耗时预算）；典型 3 步部署新增约 13–16 行，量级可接受 |
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
| 上线顺序 | ① 主应用（`backend/core` + `api` + `plugin`）随 `scripts/start-all.sh` 重启 → 此时无任何插件声明扩展步骤，行为与今天逐字相同；② `plugin-dnf-tw` 热部署——**点名走「`--jar` + env 覆盖」这一条路，不改脚本**（v0.3 补 SUG-8）：`cd backend && mvn -pl plugin-dnf-tw/plugin-dnf-tw-core -am install -DskipTests` 后执行 `PLUGIN_ID=plugin-dnf-tw JAR_NAME=plugin-dnf-tw-core-1.0.0.jar bash scripts/deploy-plugin.sh --jar backend/plugin-dnf-tw/plugin-dnf-tw-core/target/plugin-dnf-tw-core-1.0.0.jar`。**为什么是这条**（v0.3.1 按 SUG-17 逐处订正行号，结论不变）：脚本的 `FRONTEND_DIR` / `PLUGIN_MODULE` 硬编码 l4d2（`:27-28`），只有 `PLUGIN_ID` / `JAR_NAME` 可 env 覆盖（`:30-31`），而 `--jar` 参数解析在 `:44`、其分支体 `:52-54`——**命中即整段跳过构建臂**（前端 `npm run build` 与 `mvn ... install` 都在 else 臂 `:56-68` 内）⇒ 恰好绕开两处硬编码，且**确实同时跳过前端构建与 JAR 构建**（本插件无前端、不需要脚本代打 JAR）；卸载 / 覆盖 / 加载三步全部按 `PLUGIN_ID`+`JAR_NAME` 走（`:110-135`：卸载 `:110-116`、覆盖 `:118-120`、重复 jar 清理 `:122-130`、加载 `:133`，`purgeTasks=false` 在 `:114`）。**不选「参数化脚本」**：那要动 l4d2 在用的既有脚本，属本期范围外，且 `--jar` 路线零改动即可用。**B-14 完成判据含本行**（交付日不得卡在这一步）；③ 桩插件与夹具只出现在验收环境，不进 `plugins/`（AC-26 ②） |
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
| V-02 | §8.1 全量校验 + **§16.3 的 N1…N5 五条**（v0.3 扩） | 单测逐规则各一条非法样本，另加两条反例 | 任一不合法 ⇒ 整目录 `INVALID`，无「跳过该条继续」的部分采纳；**反例 a**：插件经 `getDeployConfigs()` 整节替换声明出的 `variables` / `composeTemplate` **不得**让 N1/N2 判过（判 `INVALID`，证明校验与部署同读表快照，§14.4.1 R1）；**反例 b**：表侧模板缺 `${PLATFORM_IMAGE_TAG` 字面量 ⇒ `INVALID`（R3） | AC-20、§14.4、§14.4.1、§15.2、§14.13.1 |
| V-03 | 三态与键 | 单测 `applyVersionSelection` 三支 + 提交期撞键 | S1 不写、S2 删既存键、S3 写且值精确等于 `versionId`；撞禁止清单 ⇒ 400 且原因可辨识；**目录不可用不在提交期 400** | AC-19、AC-22（服务层）、AC-20 |
| V-04 | 步骤集串行与致命性 | 集成（桩插件 + 夹具）：`PATCH#1` 成功 → `SCRIPT#2` 非致命失败 → `PATCH#3` 致命失败 | 严格声明序、无并发；#2 记 `WARN` 后继续；#3 后无第 4 步；部署失败、`ERROR`、未进 `START` | AC-06、AC-08、AC-10、AC-21 |
| V-05 | 补丁回滚边界 | 同上，比对 `PATCH#1` 落位结果与 `#2` 文件改动 | `#3` 目标路径回到改动前；`#1` 与 `#2` 的改动一律保留 | AC-09、AC-21、BR-14 |
| V-06 | `sha256` 不符 | 集成 | 步骤失败、按致命性处置、日志原因段指名期望/实际 | AC-11 |
| V-07 | `includePattern` 真实生效 | 集成：带多成员的包 + 单一 glob 声明 | 只有匹配成员落位（证明同步入口未被 payload 截断） | §14.2 行 2、决策 6 |
| V-08 | 日志呈现契约 | **机械核对脚本**：只读 `logs[].{stage,stepId,stepEvent,elapsedMs}` | 每条 `stage == "EXTENSION"`；**统计对象 = `stepId != null` 的行**——分母 = **非空** `stepId` 的去重计数（进入行 / 交棒行 / 停实例行 / BR-12 与目录不合法说明行 / **收尾行** / **阶段完成行**这些阶段级行 `stepId == null`，**一律不进分母**，v0.3.1 按 SUG-13 写死：它们按构造永不满足「恰一 START + 恰一终态」，含进分母则正确实现也只能得到 n/(n+1)，KPI-02 恒 < 100%）；每个非空 `stepId` 恰一 `START` + 恰一终态且终态 `elapsedMs != null`；比例 = 100%；**脚本内不得出现 `message` 匹配** | AC-03、AC-16、KPI-02、§14.6 规则 2 |
| V-09 | `level` 归一化 | 组件单测 + 目视 | `SUCCESS`/`WARN`/`ERROR`/`INFO` 四类各自的 class 与图标不再同色同图标 | §14.9、AC-08/AC-10 界面侧 |
| V-10 | 进度序列 | 抓 `deploy-progress` 全量轮询样本两条：无扩展部署 vs 有扩展部署；**同时**以同频轮询 `GET /instances/{id}` 记下 `runStatus` 变化序列（v0.3.1 补 SUG-16② 的采样对象） | 无扩展：与改造前**逐值相同**（含 band 插值）——比对面 = **顶层 `progress` 值序列 / 阶段序列 / 日志行的 `stage`+`level` 原值 / 终态 / 状态转移（实例 `runStatus` 的取值序列）**五项，与 PRD AC-15 的五项列举（阶段序列、状态转移、进度百分比语义、日志结构、最终结果）一一对齐（v0.3.1 按 SUG-16② 补「状态转移」——v0.3 的四项漏了它，会让 AC-15 明列的一项无核对物）；**不含 CSS class 与图标名**（v0.3 补 SUG-9：`level` 归一化会让此前同为 `log-info` 的非 INFO 行开始变色，那属 RISK-D03 定性的正当外溢，不得被本行判成回归）；有扩展：单调不减、扩展占 `[80,84]`、`COMPLETE == 100`；**子项**：`ensureRunningForExtension` 在无扩展部署中的调用次数 = 0、新字段全 `null` | AC-15、§14.7、BR-10、§14.13.2 |
| V-11 | 停实例语义 | 集成：`docker inspect` 采样（**两类各跑一次**：`docker-compose` 与 `linuxgsm-docker`） | 第一条步骤行的时间戳之后容器 `Running == false`；停实例失败注入时部署 `ERROR` 且**零步骤行** | AC-04、§14.5、ui-spec 态 S、§14.13.1 |
| V-12 | 健康判定路径 | 集成：两条部署各抓全量轮询样本（同一 deployType 内），V-27 负责跨类 | **有扩展步骤**：`HEALTH_CHECK` 行存在且**通过**（容器经扩展阶段收尾的 `adapter.ensureRunningForExtension` 起回），其后 `UPDATE_STATUS` / `START` / `retryHealthCheck` 与今天逐字同形；**无扩展步骤**：整条序列不含 `EXTENSION` | §14.12、§14.13.3、AC-04、N-06/BR-10 |
| V-13 | 脚本安全形状 | 代码审查 + 日志核对 | 命令文本不含脚本正文；未通过校验的下载不在宿主机落地；`finally` 删除临时文件 | BR-05、RISK-08、§8.4 |
| V-14 | 输出截断 | 集成：产超大 `stdout` 的脚本 | 头 2000 + 尾 2000 + 一条 `NOTE`「输出已截断，共 N 字节」；日志体量受控 | §8.3 |
| V-15 | `imageTag` **四**核对物 | 集成（桩游戏 + 外置元数据） | ① 远端 `docker-compose.yml` 与「同一游戏未选版本时落下的那份」**逐字节相同**（两边都经 `ensureVolumesDeclaration` / `injectHostCertsMount` 后处理，比较基线自带后处理，故 SUG-3① 不影响本判据）；② `.env` 中 `PLATFORM_IMAGE_TAG` 精确等于声明值；③ 该工作目录 `docker compose config` 渲染出的 `.services.<name>.image` = `<repo>:<声明 tag>`；**④（v0.3 新增）先 `PUT /instances/{id}/config` 把该键写成任意他值（如 `latest`）再部署 ⇒ `.env` 里仍是平台写入的值，提交值不出现**（§14.4 表「框架侧唯一新增」②、§8.4 边界行） | AC-14、§14.4、§14.4.2 |
| V-16 | BR-16 保键 | 接口 + 界面双跑 AC-27 (a)(b)(c)(d) | (a) 键值精确不变；(b) 键保留；**(c) 该入口返回成功**（非「失败也算过」）；(d) 三次之后重部署均进扩展阶段交付同一版本 | AC-27、§14.8 |
| V-17 | 无专用分支 | 全量构建 → 启动 → 插件清单 → 读目录 → `grep -rn '"dnf_tw"' backend/core/src/main/java --include=*.java` | 命中数 0（口径见 AC-23 ③：必须带引号）；模块无前端产物 | AC-23 |
| V-18 | 证据归属 | 验收记录核对 | 桩/夹具结果未被登记为 AC-05、KPI-01、KPI-03；发布物不含桩；dnf-tw 目录仍为未填充模板 | AC-25、AC-26、BR-15 |
| V-19 | 通用性 | 同一份 `core` 构建物，先接桩插件跑通 V-04…V-14，再接 `plugin-dnf-tw`（空目录）跑 V-01 | 期间 `core/` 零改动 | AC-25、G-01 |
| V-20 | dnf-tw 缺口期默认路径 | 走完 5 步向导并部署，对照 AC-15 checklist | 无版本控件、载荷无键、无扩展行、结果与改造前逐项一致、无任何示例值 | AC-02、AC-24 |
| **V-21** | **AC-01 正向**（向导出现版本项）——v0.2 只有 V-20 反例，正向零行 | 集成 + E2E：桩插件目录置 `AVAILABLE`（≥2 条目），走 `deploy.vue` 步骤 2 → 步骤 5；同时读 `GET /games/{id}/deploy-config/{deployType}` 响应（**路径段，非 query 参数**——v0.3.1 按 SUG-10 订正本行照抄的 v0.3 错形式） | 接口侧 `deployVersions[].versionId` 集合与目录声明**精确相等且同序**、`versionCatalogState == AVAILABLE`；界面侧控件出现、选项数 = 条目数、默认选中项 = `defaultEntry` 条目的 `versionId`、确认页摘要显示所选条目（**断言 locator 与 `versionId`/`displayName` 字段值，不匹配拼好的句子**）；改选非默认后载荷含且只含一个 `deployVersion` 键 | **AC-01**、§16.4、F-04/F-05 |
| **V-22** | **AC-12**（`position = host` 脚本）——v0.2 V 表无一行 | 集成：两个 host 脚本步骤（`exit 3` 致命 / `exit 0` 且打印含标记行的 stdout + 一行 stderr） | **判据块 1（机械）**：`exit 3` 步骤终态行 `stepEvent == FAILURE` ∧ `exitCode == 3` ∧ 该步致命 → 部署 `ERROR`；成功步 `exitCode == 0`；执行走 `FileAccessService.executeCommand(hostId, …, timeoutMs)`（B-13 命令形状，V-13 复核）；命令文本不含脚本正文。**判据块 2（可见性，单独登记、不入 KPI-02 脚本）**：同 `stepId` 下存在承载 stdout 与 stderr 的 `NOTE` 行且含夹具标记（§14.6 承载位分工表明示这是文本核对，不是机械核对） | **AC-12**、FR-15、§14.6 规则 4 |
| **V-23** | **AC-16 界面侧**——v0.2 只有 V-08 读 API 字段 | 前端 E2E（`npm run e2e` 受管模式）+ 组件单测：在扩展阶段执行中采样面板 DOM | ① 阶段带 / 「扩展」步骤点出现（latch = `logs[].stage === 'EXTENSION'`，`HEALTH_CHECK` 之后仍为真）；② 当前进行中的步骤行**可读**：含 `stepIndex/stepTotal` 与 `stepLabel` 字段来源的渲染（断言由字段驱动，词面归 ui-spec）；③ 与 V-08 的分工：V-08 判「数据齐备」，本行判「用户可辨识」 | **AC-16**、§14.11、F-02/F-03 |
| **V-24** | **AC-18**（两实例不同版本不串用）——v0.2 V 表无一行、① 支至今零测试 | 集成：桩插件同时提供两种形态——(a) `getDeployExtensionSteps(ctx)` 按 `ctx.selectedVersionId` 代码算步骤集（① 支）；(b) 只靠目录条目 `patches ++ scripts`（② 支）。同游戏两实例分别选 `V-a` / `V-b` 并发部署 | 两实例的 `stepId → stepLabel` 序列各自等于各自配方的声明序、**互不重叠**；(a) 支的 `ctx.selectedVersionId` / `ctx.instanceId` / `ctx.configInfo` 三值与该实例库中状态一致（证明 ① 支真实被走到，而非回落到 ②）；(a)(b) 两支各测一次 ⇒ §16.2 解析顺序 ①② 都有覆盖 | **AC-18**、§16.2、B-09 |
| **V-25** | **KPI-04**（无扩展声明游戏的既有自动化用例通过率 = 100%，PRD §3.2 本期可考核，Owner=@Tester）——v0.2 无一行 | 三条具体命令，改造前后各跑一次：`cd backend && mvn test`；`cd frontend && npm run test:run`；`cd frontend && npm run e2e`（受管模式自起栈，临时 SQLite）。部署/补丁相关用例清单在基线里逐条登记（现集合含 `DeployServiceTest`、`DeploymentAccessTest`、`DeployResourceLimitTest`、`PatchDecisionEngineTest`、`PatchArchiveExtractorTest` 及前端 e2e `main-app/instance`、`main-app/docker`） | 分母 = 基线登记的部署相关用例数（**清单不减**，新增用例不计入本 KPI）；分子 = 本期合入后通过数；**判据 = 分子/分母 = 100%**，任一原通过用例转 FAIL 即不达标并逐条归因（区分「本期回归」与「环境抖动重跑」，抖动须同命令重跑两次留痕）。T-04 存基线表、验收记录存末次结果 | **KPI-04**、AC-15、§7.5 T-04 |
| **V-26** | **BR-09 每主机互斥**（v0.3 新增，REV-1） | 单测（`TaskMutexManager` 假替身）+ 集成（同主机两实例并发扩展阶段各含 `PATCH` 步骤；另一路经 `install()` 走任务中心） | 三路两两不重叠：两次 `execute()` 的时间戳区间无交集；持锁期间 `isHeld("PATCH_INSTALL:<hostId>") == true`；等满 600 s → 该步 `FAILURE` 且原因段指名「等待同主机补丁互斥超时」；异常/失败路径后键已释放（`isHeld == false`）；holder 前缀 `EXT:` 且不受 `removeByTaskId` 影响 | **BR-09**、§14.14、B-04 |
| **V-27** | **两类逐类收口 + 集合外不合法**（v0.3 新增，REV-2/REV-3） | 集成：deployType 分别取 `docker-compose`、`linuxgsm-docker` 各跑一次带步骤部署；另构造 `deployType=docker` 的带步骤目录声明 | ① 两类各自：`HEALTH_CHECK` 行存在且**通过**，且命中的是**该类自己的** `ensureRunningForExtension` 覆写（compose 路径日志见 `up -d`+`ps` 认 `running`/`Up`；lgsm 路径见 `up -d`+`ps -q`+**逐个**容器 `.State.Running`）；② `docker` 声明 → 目录 `INVALID`（N5）+ BR-12 处置，且 `ensureRunningForExtension` 的默认抛异常路径**命中次数 = 0**；③ 收尾失败注入（桩使 `ps` 不含 running）→ 部署 `ERROR` 且不进 `HEALTH_CHECK` | **AC-04**、§14.13.1/2/3、RISK-D01/D11/D13 |
| **V-28** | **RISK-09：retry-deploy 的全量重放**（v0.3 新增，REV-7①） | 集成：桩插件实例以非默认版本部署成功 → 手工置 `ERROR` → `POST /instances/{id}/retry-deploy`，全程采 `deploy-progress` 与宿主机文件 | retry 先 `uninstall`（宿主机 `<workDir>` 被 `rm -rf`）后全量重跑 ⇒ 扩展阶段**每次都执行**、`stepId` 集合与首次部署一致；`configInfo.deployVersion` 跨 retry **存续**且值精确不变（不出现按默认版本交付）；**BR-14 的「保留」判定明确只在同一 attempt 内成立**：验收记录须写「首次部署中 `PATCH#1` 的产物在 retry 后不保留，属设计行为，不判 AC-21 FAIL」（§14.15 结论 2） | **AC-07**、AC-21、BR-14、BR-06、RISK-09、§14.15 |
| **V-29** | **注入点副作用收敛**（v0.3 新增，SUG-5） | 代码审查 + 计数断言：对不含 `deployVersion` 的实例分别走 start/stop/restart/文件/备份/retry 六条路径 | `configInfo` 无 `deployVersion` 时 `DeployVersionCatalogService.read` 与 `getDeployExtensionSteps`/`getDeployVersions` 的调用次数 = 0（快路径）；带键时调用发生但异常一律归 `ABSENT` 不外泄；`updateInstance:183` 路径不因新增读取而改变返回 | §14.4.3、RISK-D07 |

## 11. 需求追溯

> 状态列 = 本设计交付后该 AC 的**可验收性**；「承载方」= PRD §11.1 归属行（缺口期判定基准）。带 ↓↑ 的是本设计改动过的归属，逐条在 §13 列给 Leader 回写。

| AC | 承载方 | 本期状态 | 设计落点 |
| --- | --- | --- | --- |
| AC-01 | 桩插件+夹具 | 可验收 | §16.4 GET、F-04/F-05、**V-21（v0.3 补正向核对物；此前只有 V-20 反例）** |
| AC-02 | dnf-tw 默认路径 | 可验收 | B-08 两级门控、F-05、V-20 |
| AC-03 | 桩插件+夹具 | **可验收**（契约已定稿登记，AC-03 文本里的「生效前提」句式随 D-P12 出表） | §14.6、B-09/B-11、V-08 |
| AC-04 | 桩插件+夹具 | 可验收（**前置收窄：deployType 两类各测一次**，D-P15） | §14.5 停止语义、§14.12、§14.13.1/3、V-11 + **V-27** |
| AC-05 | dnf-tw 真实资料 | 不可测（L-02，不变） | — |
| AC-06 | 桩插件+夹具 | 可验收 | 16.2 解析顺序（sealed 上界）、V-04、§14.6 规则 3 |
| AC-07 | 桩插件+夹具 | 可验收（**v0.3 补 RISK-09 收口：retry 会先清空宿主机 workDir ⇒ 全量重放**） | B-07、retry-deploy 读 `configInfo`、**§14.15 + V-28** |
| AC-08 | 桩插件+夹具 | 可验收 | B-09、F-01、V-04/V-09 |
| AC-09 | 桩插件+夹具 | 可验收（回滚结果在日志里有 `ROLLBACK` 记录位，**成功与否仍由文件比对判**） | B-04（执行器内建回滚）、V-05、§14.6 规则 5 |
| AC-10 | 桩插件+夹具 | 可验收 | B-09 `fatal=false` 继续 + WARN、V-04 |
| AC-11 | 桩插件+夹具 | 可验收 | B-04/B-13 sha256、V-06 |
| AC-12 | 桩插件+夹具 | 可验收 | B-13（宿主机）、**§14.6 规则 4（`exitCode` 有承载位）、V-22（v0.3 补，此前 V 表无一行）** |
| **AC-13** | 第一行 → **第五行** | **本期不验收**（14.5 判不合法，`container` 移出本期） | — |
| **AC-14** | 第五行 → **第一行** | **转可验收**（14.4 给出机制；**载体 = 桩插件 + 桩游戏元数据**，属验收资产扩张 → D-P13） | §14.4、§14.4.1、B-08、V-15（四核对物） |
| AC-15 | dnf-tw 默认路径 | 可验收（门控式改动的直接受益者；**比对面五项限定见 V-10，不含 CSS class**） | §14.7 无步骤分支、B-08/B-10/B-12/B-16、V-10 |
| AC-16 | 桩插件+夹具 | **可验收**（契约 + F-02/F-03；AC-16 文本里的「生效前提」句式随 D-P12 出表） | §14.6、§14.11、V-08（数据侧）+ **V-23（界面侧，v0.3 补）** |
| ~~AC-17~~ | 表外豁免 | 废弃编号，不属验收对象 | — |
| AC-18 | 桩插件+夹具 | 可验收 | 16.2 解析顺序 ①②、**V-24（v0.3 补：① 代码计算型支此前零测试）** |
| AC-19 | 桩插件+夹具 | 可验收 | B-07、§5.2、V-03 |
| AC-20 | 桩插件+夹具 | 可验收（**前提保护：提交期不得改判为 400**） | §14.10 末段、B-05、V-02 |
| AC-21 | 桩插件+夹具 | 可验收 | V-05（回滚/保留边界） |
| **AC-22** | 第一行 | **服务层可验收；界面端到端前提不成立**（14.10，**Leader 已裁 B** ⇒ 本行不再是待决；A 案登记为后续增量建议交人类 Owner） | B-07、V-03 |
| AC-23 | 本期构建产物 | 可验收 | B-14/B-15、V-17 |
| AC-24 | dnf-tw 默认路径 | 可验收 | B-15 `EMPTY`、V-01、V-20 |
| AC-25 | 桩插件+夹具 | 可验收 | T-01、V-19 |
| AC-26 | 本期构建产物/验收记录 | 可验收 | K 边界、V-18 |
| AC-27 | 桩插件+夹具 | 可验收（合并式写入天然满足 (c)，无「恒失败」风险） | §14.8、B-12、V-16 |

**FR / BR 侧收口点**：FR-05（两入口 + 唯一解析顺序 16.2，公共上界 = sealed `DeployExtensionStepDeclaration`）、FR-11（**停实例 + 收尾起回只对 compose 两类成立，集合外声明不合法：§14.13.1**）、FR-12（同步直调 §14.1）、FR-14（同执行器，未另起链路）、**FR-15（`exitCode` 有承载位 + 规则 4：§14.6）**、FR-20（§14.6 契约）、**BR-07（v0.3 订正：清单保持 PRD 原三项，`PLATFORM_IMAGE_TAG` 走声明期保留键而非提交期禁止键，§14.4.2）**、**BR-09（并发闸/重试继承 + 每主机互斥由 `installSync` 承同一个内存键，§14.14）**、BR-11（条件门控 §14.7 / B-08）、**BR-14（回滚边界如实记日志，不加反向补偿；v0.3 补：「保留」限当次 attempt，不跨 retry，§14.15）**、BR-16（§14.8）、BR-15（K/V-18 三不得）。

## 12. 风险与边界

| # | 风险 | 影响 | 处置 |
| --- | --- | --- | --- |
| RISK-D01 | **`HEALTH_CHECK` 与停实例时点相冲**（§14.12）——若按字面同时满足 FR-10/FR-11/BR-10，任何带扩展步骤的 compose 部署必然在健康检查处失败。**v0.3 扩大认知**：该冲突对 `docker` / `linuxgsm-docker` / `docker-compose` **三类容器适配器同时成立**（`DockerAdapter:274-277`、`LinuxGsmDockerAdapter:446-468`），v0.2 只论证了一类 | 阻断级：AC-03/04/05/08 全灭 | 已定判定：**扩展阶段收尾按依赖顺序把容器起回来**，`HEALTH_CHECK` 的判据、动作与时点一字不动 ⇒ 不触碰 N-06/BR-10，也不改无步骤分支。成本与「后移探测」方案相同（都是起停各一次）。**收口的两半补齐（§14.13）**：调用面 = `DeployAdapter.ensureRunningForExtension` default 方法（组 L / B-16）；适用范围 = 支持集合 {`docker-compose`, `linuxgsm-docker`} 内**逐类**给形状，集合外**声明即不合法**（N5 → BR-12） |
| RISK-D11 | **`compose start` 不处理 `depends_on` 顺序**：dnf-tw 的 compose 项目含 MySQL 与多个服务，`DockerComposeAdapter.start` 走 `compose -p … start`（`:365-379`），只有 `up` 尊重依赖顺序。今天这一步是 `up -d` 之后的空操作，本期停实例后若由它来真实启动，首启可能早于数据库就绪 | 表现为「部署成功但版本没生效」——G-02 最坏的失效形态 | **与 RISK-D01 同一条解法收口**：起回容器由扩展阶段收尾用既有 `up -d`（compose `:260`、lgsm-docker `:223`）完成，`START` 沿用今天「已运行 → 空操作 → 复检」的行为。**禁止**把 `DockerComposeAdapter.start()` 全局改成 `up -d`（会改既有语义，违反 AC-15 / N-06）。**v0.3 追加一条同源禁令**：`linuxgsm-docker` 的收尾**不得复用** private `ensureContainerRunning`（`:955-993`）——它的停止分支正是 `compose start`（`:970-973`），且对 `ps -q` 的第一个容器判完即 `return`（`:979`），两个缺陷叠加会同时踩 D01 与 D11 |
| RISK-D02 | `updateInstance` 从整表替换改合并：任何依赖「省略即删除」隐含语义的调用方会失效 | 中：仓内核对只有配置管理表单与通用接口两条路径，而该隐含语义本身正是 F-18 判定的缺陷 | 合并范围只在 `configInfo` 一个字段；单测 + V-16 四项；不改 `create`。缺口期 dnf-tw 恒无键，风险面实际落在桩插件承载的实例 |
| RISK-D03 | `DeployProgress.vue` 归一化会外溢到备份/还原等复用该组件的流程（非 INFO 行**开始**变色） | 低（属缺陷修复的正当外溢） | @Tester 目视回归一次；不改后端取值集合 |
| RISK-D04 | `deployAsync` 的 `@Async → ForkJoinPool.commonPool` 双跳（`:318-332`）叠加本期长阻塞步骤 | 并发多实例部署时可耗尽 commonPool，拖慢其它异步任务 | 本期不改部署主流程（D-N05）；上线后按 KPI/实测评估专用线程池（另立需求） |
| RISK-D05 | `install()` 异步路径仍丢 `headers`/`includePattern`（`PatchInstallServiceImpl:47-56`） | plugin-l4d2 若开始依赖这两字段会静默失效 | 本期不修（无使用方依赖，PRD 亦未要求）；在 SDK 注释处标出「需 `includePattern` 请用 `installSync`」，把坑写在脸上 |
| RISK-D06 | 脚本超时只保证「不再等待」，不保证远端进程已终止（§15.3） | 挂死脚本可能在宿主机继续跑，与重放叠加产生竞态 | 日志明示 + 声明侧幂等（BR-06）+ 本期不引入 kill 台账 |
| RISK-D07 | ADR-0008 通道不对称：`getDeployConfigs()` 的声明进不了 `buildDeployConfig` 的部署配置（16.1） | 后来者按「插件声明即生效」理解会做出「向导看得见、部署看不见」的功能 | 本期不碰该通道（版本目录走 `getDeployVersions`）；建议另立 Issue（交 Leader 裁，Leader 已判本期不修）。**v0.3 补两点**：① 同一缺陷对 `imageTag` 同样成立——经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` **不参与** `imageTag` 机制（§14.4.1 R2）；② 校验规则 N1/N2 的判定通道已绑死表快照，所以「插件换个入口声明就能骗过校验」这条路径被关在声明期（V-02 反例 a 负责证明） |
| RISK-D08 | 扩展阶段无聚合耗时/步骤数上限 | 一个声明很多慢步骤的插件可长期占住部署 worker | 有意为之（不加 PRD 未要求的闸门）；单步已有上下限（§15.2）与体量上限（§8.3） |
| RISK-D09 | AC-22 / §8.4.2 S2 的删键无真实界面入口（14.10） | 「换回默认版本」只能靠新建实例；`deployVersion` 一旦写入，同实例上无界面手段解除 | 待 Leader 裁 A/B；推荐 B（不扩范围），并把该事实回写 PRD 而非留在聊天记录 |
| RISK-D10 | 桩插件/夹具结果被误当 dnf-tw 真实版本通过 | 违反 BR-15「三不得」，验收结论失真 | 流程性防线：V-18 记录核对 + 夹具不进 `plugins/` + AC-05/KPI-01/KPI-03 在验收记录里显式写「不可测 + L-02 因」 |
| **RISK-D12** | **retry-deploy 不是「干净的重放」而是「先拆再建」**（v0.3 新增，REV-7① / PRD RISK-09）：`retryDeploy` 先 `adapter.uninstall(...)`（`InstanceServiceImpl:791-798`），compose 类 `uninstall` = `down` + **`rm -rf <workDir>`**（`DockerComposeAdapter:555-573`、lgsm-docker `:541`） | 读者与验收者会把 BR-14「前序成功步骤改动一律保留」误读成跨 attempt 保留 → AC-21 在 retry 语境下被误判；扩展阶段每次 retry 都全量重跑，幂等前提（BR-06）比首次部署更吃紧 | §14.15 四条结论 + V-28；**`configInfo` 半边核对为安全**（`DEPLOY` 末回写是既有 map 的拷贝再 put，`deployVersion` 不会被冲掉：`DockerComposeAdapter:329`/`:349`）；不加任何「retry 前保留产物」的机制（对抗既有语义） |
| **RISK-D13** | **`DeployAdapter` 接口面扩大**（v0.3 新增，REV-2）：新增 default 方法 `ensureRunningForExtension` 是本期唯一进入 core 主干公共接口的改动，4 个实现者 + 未来适配器都会看到它 | 中—高：能力位只有两类适配器能用；若被误改成默认返回 `true`、或被 `instanceof` 绕过、或被拿去替代 `start()`，就会把 §14.12 判掉的失效重新引入 | 三层封套：① 声明期支持集合校验 N5（集合外到不了这里）；② 默认实现**抛异常**而非空实现（评审建议的 `AbstractDeployAdapter` 空实现已否决，理由见 §14.13.2）；③ 三条红线入代码注释与 V-27/B-16 判据（不得 `return true`、不得改 `start()`、executor 内不得 `instanceof` 分派）。既有 14 个方法一字不改 + V-10/V-25 守回归 |
| **RISK-D14** | **同主机互斥带来的新等待面**（v0.3 新增，REV-1）：`installSync` 最多等 600 s 才起补丁；键在内存、单进程有效；多部署线程争同键无排队公平 | 并发多实例同主机部署时，扩展阶段可能长时间排队后失败（今天经任务中心提交是直接 409 拒绝，形态不同）；进程崩溃后重启 `clear()` 释放（与既有一致）；等待叠加 RISK-D04 的 commonPool 长阻塞 | 与等对象同量级取预算（不新增第二个数，§14.14 表）；等满判**该步失败**并指名原因，不静默跳过；锁粒度钉在单次 `execute()`（**红线**：不得提到阶段级，否则 30 min 脚本会把同主机补丁能力拖死）；V-26 四判据；剩余敞口（无公平性）如实登记，本期不引入队列 |
| **RISK-D15** | **回滚结果的可见性靠日志文本**（v0.3 新增，SUG-7 的诚实限制）：执行器只经 `progress.onLog("已回滚备份"/"回滚失败: …")`（`:215`/`:218`）回报，无结构化信号 | 若想按「回滚成功/失败」做机械判定，必须改 `PatchInstallExecutor` 的回调接口——那与 Leader 就评审 ③ 已裁的「不动 ADR-0006 既有行为」同类，本期不做 | `stepEvent = ROLLBACK` 定为**记录位**（存在性/位置/`level` 可机械判，§14.6 规则 5），**回滚是否成功由 V-05 的文件比对判**，不用文本匹配冒充机械核对；恢复 `container` 或后续增量若允许改执行器，再把该信号结构化（登记为恢复项） |
| 边界 | 本期结束后，dnf-tw 想跑非默认版本**仍需人工改文件** | 诚实结论（PRD L-02 ③）：本期消除的是「平台没这个能力」，不是「dnf-tw 还要手工干」 | 真实资料填充即生效（BR-15 ④），框架与声明接口都不需要再改 |

## 13. 待决事项

### 13.1 须 Leader 下派 @ProductManager 回写 PRD 的清单（本设计不自行改 PRD）

| # | 条目 | 回写内容 | 来源 |
| --- | --- | --- | --- |
| D-P01 | AC-13 / FR-08 / FR-11 / §8.3 / §12 / RISK-05 | `position = container` 移出本期（取值只 `host`；声明即不合法）；AC-13 自 §11.1 第一行移入第五行记「本期不验收」；决策 7 的条件性收缩记修订记录 | §14.5 |
| D-P02 | §14.2（增第 9 行待改依赖）+ §9 状态机表 | 登记新事实：FR-11 停实例与既有 `HEALTH_CHECK`（探测 `.State.Running`）时点相冲，本期由「扩展阶段收尾按依赖顺序起回容器」收口；§9「进入扩展阶段」行补一句「阶段收尾容器恢复运行，故 `HEALTH_CHECK` 语义与时点不变」。**本项不改动决策 1–9，也不触碰 N-06/BR-10** | §14.12、RISK-D01/D11 |
| D-P03 | §8.3 `timeoutMs` 行、§12「脚本超时」行、§16.2 OP-04 | 缺省 `600000`、合法区间 `[1000, 1800000]`、越界即声明不合法；OP-04 标已关闭 | §15.2 |
| D-P04 | AC-14 与 §11.1 第五行 | 前提成立 → AC-14 转入第一行（框架类可验收）；RISK-12 关闭 | §14.4 |
| D-P05 | §8.4.3 + §8.1 校验内容 | **v0.3 改写（原建议含一处自相矛盾，见 §14.4.2）**：① §8.4.3 的禁止清单**不增** `PLATFORM_IMAGE_TAG`——它是声明期保留键，会作为 `variables[]` 一项合法出现在提交载荷里，进清单等于把 AC-14 正向路径判 400；改为在 §8.4.3 末尾补一句「`PLATFORM_IMAGE_TAG` 为平台声明期保留键，其提交值不参与判定、由平台在部署配置组装时写入」；② §8.1 校验内容由「增两条蕴含规则」改为**增五条 N1…N5**（判定通道 = `game_metadata` 表快照 / 模板必含 `${PLATFORM_IMAGE_TAG` 字面量 / tag 与默认值格式校验 / `timeoutMs` 区间与 `position` 限 `HOST` / deployType 支持集合），并同步 §14.4.1 的四条 | §14.4、§14.4.1、§14.4.2、§15.2、§14.13.1 |
| D-P06 | §14.2 增「`level → 视觉映射`」行（Leader 已承诺随 v0.6 回写）+ FR-20/§8.5 | 判据定稿为前端归一化，后端取值集合不变 | §14.9 |
| D-P07 | AC-22 / §8.4.2 S2 / §12 恢复路径 | 裁 A（新增入口，扩范围）或 B（服务层验收 + 界面端到端记前提未成立）；B 为推荐 | §14.10、RISK-D09 |
| D-P08 | FR-22 / §5.1 第 3 项 / F-08 / §14.1 | 「通过 ADR-0008 声明接口」→「通过 ADR-0008 声明**体系**」，并把 16.1 的通道不对称登记为事实 | §16.5 |
| D-P09 | 桩承载与 KPI-02 生效前提 | OP-04 已定稿、呈现契约已登记 ⇒ KPI-02 自此可考核（不再挂「不可测」）；§16.2 OP-04 行改已关闭 | §14.6、§15 |
| **D-P10** | §12「retry-deploy / 重部署」相关行 + BR-14 + AC-07 + RISK-09 状态 | PRD RISK-09 点名交本设计，现收口：① RISK-09 关闭并写清事实——`retry-deploy` = `uninstall`（compose 类含 `rm -rf workDir`）+ 全量重部署 ⇒ **补丁每次 retry 都重放**；② BR-14 的「前序已成功步骤改动一律保留」**加限定「当次部署 attempt 内」**，防跨 attempt 误读；③ AC-07 预期结果补一句「retry 后仍按同一配方交付同一版本，且不主张前次 attempt 的中间产物」；④ §12 恢复路径行同步该口径 | §14.15、RISK-D12、V-28 |
| **D-P11** | §11.1 承载表的**并集计数注**与**第五行标题** | D-P01（AC-13 移入第五行）+ D-P04（AC-14 转入第一行）**同时**改变各行条目数与「19+3+2+1+1 = 26」注：应回写为第一行 19（−AC-13 +AC-14 = 19）、第五行 1（AC-13）、合计仍 26——**并须显式重跑并集核对**（§11.1 的闭合规则要求每个 AC 恰一个归属行）；第五行现标题「生效前提未定稿 → 本期不验收」自此同时容纳「前提已判**不成立**」的 AC-13 与「前提**已成立**、应出表」的 AC-14，标题与说明文字须改为按「前提不成立 / 移出本期」口径表述，避免自相矛盾 | §14.5、§14.4、REV-7② |
| **D-P12** | §8.5 末段「生效前提」约定句 + **§13.1 引言「生效前提」约定块（PRD 行 441，v0.3.1 按 SUG-16① 补——同一套条件句在 PRD 有两处，只改 §8.5 会留下验收侧那一处继续把已可测条目读成「不可测」；该块同时挂着 AC-13/AC-14 与 KPI-02/AC-03/AC-16，是本清单里载荷最重的一处）** + §11.1 第一行内联标注 + AC-03 / AC-16 的「预期结果」文本 | 契约已于本设计 §14.6 定稿并在 §6.3 登记 ⇒ 上述各处「契约未登记时记不可测」的**条件句式**须改为已 fulfillment 的陈述（否则验收时把**已可测**的条目继续读成「不可测」，方向与本期目标相反）。同批：AC-13 的「本期不验收」结论从条件句改为定稿句 | §6.3、§14.6、REV-7③、SUG-16① |
| **D-P13** | §5.1.1 验收资产行 + §8.6 三不得 + AC-26 ② | AC-14 的验收载体从「最小桩插件」扩张为**「最小桩插件 + 桩游戏元数据」**（需要一个新 gameCode 的游戏 yml 投放到外置 `./games` 才能验 `imageTag`）：① 把「桩游戏元数据属验收资产、经外置 `./games` 目录投放、验收后回收」写进 §5.1.1 与 §8.6；② **AC-26 ② 的核对物从「发布物 jar 不含桩」扩到「`./games` 下不留桩 yml、发布物 jar 不含桩」**——否则这条扩张本期无判据 | §14.4、组 K、V-18 |
| **D-P14** | §14.2 待改依赖表（八行 + 本文新增六行） | 逐行按本文 **§14.0.1 状态收口表**回写「已关闭 / 本期不适用 / 已判定 + 证据指向本设计哪一节」，**不得保留「待定调」字样**而不指向证据；现状只有 D-P02 增了第 9 行，没有一行宣布旧八行的状态 ⇒ 下游会按原文把八行继续当开环 | §14.0.1、REV-7 另加项 |
| **D-P15** | FR-11 / AC-04（前置与预期）+ §14.2 第 9 行（与 D-P02 并批） | 本期 **EXTENSION 支持与收口集合 = {`docker-compose`, `linuxgsm-docker`}**（Leader 裁定按 FR-11 字面）：① FR-11 补一句「集合外 deployType 声明扩展步骤属声明不合法，走 §8.1 → BR-12」；② AC-04 前置把「部署方式仍取 compose 类」明确为「两类各测一次」（V-27），并登记 plain `docker` 的排除理由与成本核对（本设计的改判窗口已用过：该类收口成本高于回写成本）；③ 决策 1–9 不改动——FR-11 原文本就点名这两类 | §14.13.1、REV-3 |
| **D-P16** | F-06 / F-05 与 §14.2 行 1 的事实口径 | 补一条代码事实：`PatchInstallExecutor` 类头自述「同主机互斥（由任务中心 `scopeKey=hostId` 承担）」（`:35`），类内只有 `globalSemaphore`（`:56`）⇒ **绕开任务中心直调执行器即丢掉每主机互斥**；PRD §14.2 行 1 的「桥接后即继承全部资源约束」表述须收窄为「并发闸/重试在类内继承；每主机互斥由调用方承键」，并登记本期做法（`TaskMutexManager` + 键 `PATCH_INSTALL:<hostId>`）。共享临时路径（`/tmp/patch_install_<ts>`、`/tmp/patch_push/<file>`）实例命名空间属**后续增量建议**（Leader 判本期不授权；**v0.3.1 按 SUG-12 补该增量的理由**：`patch_install_<ts>` 有 `finally` 的 `rm -rf`（`:227`），而 `/tmp/patch_push/` 全仓**无任何清理**（只有 `:637`/`:649` 两处写入点）⇒ 残留会**跨实例、跨部署累积**在宿主机 `/tmp` 下，这是命名空间化之外还要收口的理由），交人类 Owner 决定是否另立 Issue | §14.14、BR-09、RISK-D14 |
| **D-P17** | §8.5 契约的核对对象清单 + FR-15/AC-12 的核对方式 | 契约读者加 **AC-12**（其 `exitCode` 判定现由 §14.6 规则 4 承载）；并在 §8.5 写明「`stdout`/`stderr` 只进 `message`、**不参与任何机械判据**，其可见性核对与 AC-12 的失败判定分离登记」——否则 FR-15 的「输出进日志」会被要求成文本匹配 | §14.6、V-22、REV-6 |

### 13.2 不在本设计权限内、也不阻断实现的三项（v0.3 更新为 Leader 已裁状态）

- `getDeployConfigs()` 通道缺陷（RISK-D07）是否另立 Issue、何时修 → **Leader 已裁：本期不修，是否另立 Issue 交人类 Owner**（门禁评论 `01a0be9d` 三-3）；本设计已用同一扩展点的 `getDeployVersions` / `getDeployExtensionSteps` 绕开，本期无条目被它阻塞；v0.3 另把该校验的判定通道绑死表快照（§14.4.1 R1），使「换个入口就能骗过校验」不再可达；
- 「实例详情 → 配置管理」表单该展示什么（OP-07）→ 人类 Owner（Leader 裁定 2 已收口本期下限，本期只落 §14.8 的保键）；
- `ui-design-spec.md` §3.3「4 步向导」与代码 5 步不一致、`--platform-accent` / `--platform-accent-soft` 无定义 → Leader 已判本期不修，是否另立 Issue 交人类 Owner。**本设计已在原型口径上取 `--platform-cyan`，不新增色值**；
- **（v0.3 新增两项登记）**① AC-13 移出本期已被 Leader 批准，但属**范围收缩**，须在 G1 记录与交付说明里显式标注、由人类 **G4** 复核（依据文本按 SUG-2 换为 §14.5.1 三条）；② AC-22 的 **A 案**（新增「同实例改选版本重部署」入口）与 §14.14 的**临时路径实例命名空间**（评审 ③）均登记为**后续增量建议**，交人类 Owner 决定是否另立 Issue——本期不实现，也不计入本期完成判据。**v0.3.1（SUG-12）补该增量的理由文本**：`/tmp/patch_push/<file>` 只有两处写入点（`PatchInstallExecutor:637`/`:649`）、**全仓没有任何清理**（`finally` 的 `rm -rf` 在 `:227` 只删 `hostTmpDir`，即 `/tmp/patch_install_<millis>`，`:141`）⇒ 残留会**跨实例、跨部署累积**在宿主机 `/tmp/patch_push/` 下；本期互斥结论不受此影响（见 §14.14「锁粒度」行的改述），但这条累积是命名空间化之外该收口的事实。

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
| 14 | （ArchReviewer REV-7①）PRD 点名交 @Architect 的 **RISK-09**（retry-deploy 先 `adapter.uninstall`）| @Architect | **给**：`retryDeploy` 确实先 `uninstall`，而 compose 类的 `uninstall` = `down` + **`rm -rf <workDir>`** ⇒ retry = 干净重跑，**补丁必然全量重放**；故 BR-14 的「前序已成功步骤改动保留」**只在当次部署内成立，不跨 attempt**（v0.3 写死，防误读）。`configInfo` 那半边核对为**安全**（`DEPLOY` 末回写是既有值的拷贝再 put，`deployVersion` 不会被冲掉）。见 §14.15 |

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
| 14 | RISK-09 retry-deploy 与扩展阶段的先后关系（PRD 点名交 @Architect） | **已关闭**（retry-deploy = `uninstall`（含 `rm -rf workDir`）+ 全量重部署 ⇒ 补丁必然重放；`configInfo` 半边核对为安全） | §14.15、RISK-D12、V-28 | D-P10 |

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
| 框架侧唯一新增 | `InstanceServiceImpl.buildDeployConfig`（`:687-759`）第 5 步之后插入第 5.5 步。**门控条件从「三条件齐才写」改为两级（v0.3，SUG-4）**：① 该 deployType 的表侧 `variables[]` **未声明** `PLATFORM_IMAGE_TAG` ⇒ 完全不写（未声明该变量的游戏 `.env` 与模板逐字节不变）；② 声明了 ⇒ **该键的值一律由平台写**：命中目录条目且条目带 `imageTag` → 写条目值；否则 → 写该 `variables[]` 项声明的 `defaultValue`。⇒ 用户 / 通用写接口提交的该键值**永不进 `.env`**（`deploy.vue:246-250` 会把含 hidden 的全部变量回填进 `configInfo`、`:714` 平铺展开（守卫 `isComposeVariableDeploy()` 在 `:713`，v0.3.1 按 SUG-17 订正行号），所以「不写」并不等于「用默认值」，而是等于「采信提交值」——这一条在 v0.2 是漏的） |
| 与第 6 步 `image`+`tag` 拼接的关系 | 无关系。那条只对 `services[].image` 结构化生成模式（`generateComposeFile`，`:905-935`）生效，模板驱动模式不经过它。本机制不借用它，以免把两种 deployType 的镜像语义搅在一起 |
| AC-14 的机械核对物 | ① 远端 `docker-compose.yml` 原文与未声明时逐字节相同；② `.env` 中 `PLATFORM_IMAGE_TAG=` 精确等于声明值；③ 该工作目录 `docker compose config` 渲染出的 `.services.<serviceName>.image` = `<repo>:<声明 tag>`。三项齐备即通过，不以文本比对冒充 |
| dnf-tw 是否受益 | **不受益**：本期不改 `dnf_tw.yml`（D-N15 / BR-02 / AC-02 / AC-15），其模板无占位符，故 dnf-tw 的 `imageTag` 恒不使用（FR-09 已如此规定）。机制由桩游戏承载验收：其元数据经既有外置目录 `game-platform.metadata.external-dir`（默认 `./games`）投放，不改 core resources、不进产品 jar（AC-26 ②） |

**回答 S1b 门禁转来第 2 项（只声明 `imageTag`、无 patches/scripts 的条目是否展示）**：**不隐藏、不加标注，而是判为声明不合法**。若条目声明 `imageTag` 而该 deployType 未声明保留键 `PLATFORM_IMAGE_TAG`，该 tag 必然静默无效——正是 Designer 担心的「看上去可选、实际什么都不做」。按 §8.1 校验内容扩展一条蕴含规则（「`imageTag` 存在 ⇒ 该 deployType 的 `variables[]` 必含 `PLATFORM_IMAGE_TAG`」），走 §8.1 既有处置（无键 → 默认版本 + 一条说明行；有键 → BR-12 拦截）。失败在声明读取期暴露，不在执行期静默吞掉。

**须回写 PRD（交 Leader，随 v0.6）**：§8.1 校验内容增加 14.4.1 的四条规则；§8.4.3 的口径按 14.4.2 **改写**（不是「往禁止清单里加一个键」——v0.2 那条建议会把 AC-14 自己的正向路径判成提交失败）。

#### 14.4.1 `imageTag` 判定通道的绑定（v0.3 新增，回应 REV-5）

v0.2 的蕴含规则「`imageTag` ⇒ 该 deployType 的 `variables[]` 必含 `PLATFORM_IMAGE_TAG`」**没有说这个 `variables[]` 从哪读**，而 §16.1 已经证明有两条通道且它们不对称。整条运行链**全在 `game_metadata` 表这一侧**（上传的 `composeTemplate` 取自表、`generateEnvFileContent` 读的 `config["variables"]` 也取自表），所以规则不绑定通道就会在最需要它的场景放行。定死为四条：

| # | 规则 | 依据 / 后果 |
| --- | --- | --- |
| R1 | **唯一判定通道 = `game_metadata` 表快照**（内置 `games/*.yml` + 外置 `./games` 扫描落库的结果）。`DeployVersionCatalogService` 读 `variables[]` / `composeTemplate` 必须与 `buildDeployConfig` 是**同一数据源与同一取值路径**：`gameMetadataMapper.selectById(gameId).getDeployConfig().get(deployType)`（`InstanceServiceImpl:688-699`）；**禁止**经 `GameServiceImpl` 的合并视图（`:195-201`）判定。**v0.3.1 按 SUG-14 订正措辞**：v0.3 这里写的「同一个 map 实例」是**不可强制也无必要**的——`buildDeployConfig` 每次调用都 `new HashMap<>()`（`:688`）再 `putAll(表侧 typeConfig)`（`:698`），另起的读者既拿不到那个实例、也不该试图拿（要复用的是**取数路径**，不是对象身份）。订正后本规则的可执行判据不变，仍由 **V-02 反例 a** 承载（那是行为测试：经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` 必须让 N1/N2 判 `INVALID`） | 否则插件用 `getDeployConfigs()` 整节替换声明出的 `variables` / `composeTemplate` 会**通过校验而部署侧完全看不见**——tag 静默无效，正是本规则声称要防的「看上去可选、实际什么都不做」，与 §14.4 自立的判据同构 |
| R2 | 经 `getDeployConfigs()` 声明的 `variables[]` / `composeTemplate` **不参与 `imageTag` 机制**，其状态与 RISK-D07 属同一缺陷（合并只作用于 VO，不作用于部署配置），本期不修、不测 | 把「不生效」写成显式结论，避免下游以为换个声明入口就能用 |
| R3 | **表侧该 deployType 的 `composeTemplate` 必须含字面量 `${PLATFORM_IMAGE_TAG`**，否则声明 `imageTag` 即不合法 | 注入后无消费者 = 静默无效。取字面量前缀匹配，**不认** `$PLATFORM_IMAGE_TAG` 简写（与 `${VAR:-default}` 的既有推荐写法一致，简化校验且避免与 shell 变量歧义）。这是 R1 之外唯一能证明「注入有人接」的静态证据 |
| R4 | `imageTag` 与「该保留变量的 `defaultValue`」的取值都要过**格式校验**：非空、匹配 `[A-Za-z0-9][A-Za-z0-9._@/-]{0,127}`、**禁空白 / 换行 / `${` / `}`**（compose tag 合法字符集） | `generateEnvFileContent` 对值不做转义与换行过滤 ⇒ 未校验的值可以直接改写 `.env` 结构（注入面的具体形状）。平台是单管理员信任模型，所以这定性为**输入校验缺失**而非漏洞，但必须补：`.env` 一行写坏会让整个 compose 项目起不来，属可用性问题 |

#### 14.4.2 `PLATFORM_IMAGE_TAG` 与 BR-07 撞键清单的关系（v0.3 新增，修一处 v0.2 的自相矛盾）

v0.2 §5.2 建议「把 `PLATFORM_IMAGE_TAG` 列入 §8.4.3 禁止清单」，而 PRD §8.4.3 的原文语义是「**`deployVersion` 不得与以下键同名**」（① `variables[].name` 任一取值 ② 三系统键 ③ `gameVersion`）。按 v0.2 的字面落法会得到两个错误结果：

| 若照 v0.2 字面实现 | 后果 |
| --- | --- |
| 把 `PLATFORM_IMAGE_TAG` 当「提交载荷不得出现的键」 | **AC-14 的正向路径自己被判 400**：任何声明了该保留变量的游戏，在 compose 变量类部署下向导提交时都会带上它（`deploy.vue:714` 的展开，受 `:713` 的 `isComposeVariableDeploy()` 门控；v0.3.1 按 SUG-17 订正行号并补该限定），于是「用 `imageTag` 部署」这条主路径在提交期即失败 |
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

PRD F-03 的限制是真的：`LogEntryVO{id,level,message,stage,time}`（内存态元素是 `DeployService.java:62-71` 的 `LogEntry`；`LogEntryVO` 是 **`InstanceController` 的嵌套静态类 `:889-896`**，`LogEntry → LogEntryVO` 的映射在 `:497-503` 的 stream map——v0.3.1 按 SUG-10 订正：v0.3 写的「`backend/api/.../vo/LogEntryVO.java`、映射在 `:876-897`」两处皆误，`:875-897` 是那两个 VO 的**声明段**）既无步骤标识也无耗时字段。§8.5 允许「复用 message 固定前缀 / **扩展 VO 字段** / 其它」三选一——**取扩展 VO 字段**：`message` 前缀方案的归组判据是文本，而 KPI-02 要求「机械判定、不得靠人工文本判读」，用文本当锚点等于把 AC-03 / AC-16 / KPI-02 的核对建立在另一个文本约定上（UI 评审 MF-2 踩的正是这类软锚点）。

`LogEntry` / `LogEntryVO` 新增**六个字段位**（下表六行；其中 `stepIndex`/`stepTotal` 与 `stepLabel`/`stepType` 各占一个字段位、各含两个属性 ⇒ **在类上展开为 8 个属性**，v0.3.1 按 SUG-18 写明，供 @Tester / @FrontendDev 数得出一致的数；既有阶段全部传 `null`，前端不读即零行为变化，AC-15 安全）：

| 字段 | 类型 | 契约项 | 约定 |
| --- | --- | --- | --- |
| `stage` | 既有 String | 阶段标识 | 扩展阶段全部行取常量 `"EXTENSION"`（与既有 `INIT/ENV_CHECK/…/COMPLETE` 均不冲突；该字段此前被前端完全丢弃，见 14.9） |
| `stepId` | String | ① 步骤标识（归组主键） | `E-<序号>`（如 `E-1`），同一次部署内唯一；**阶段级行**为 `null`，其枚举**定死为七项**：进入行 / 交棒行 / 停实例行 / BR-12 拦截行 / 目录不合法说明行 / **收尾行** / **阶段完成行**（§14.13.3 的「起回容器」与阶段完成不是一条声明步骤，故无 `stepId`；v0.3.1 按 SUG-13 补进收尾行，v0.3.2 按 SUG-20 补进阶段完成行——本表 `elapsedMs` 行与 §3.2 时序都已把它当既存阶段级行使用，缺一类即会被实现者从「定死」反推成「不在表里 ⇒ 它得有 `stepId`」，一旦真给了 `stepId`，SUG-13 杀掉的 n/(n+1) 就回来了；**「定死」保持定死，不降为示例**） |
| `stepIndex` / `stepTotal` | Integer | ① 步骤标识（展示位） | 序号 1 起；`stepTotal` 在阶段入口算步骤集时即得（FR-05 / FR-12），不为此新增接口 |
| `stepLabel` / `stepType` | String | ① 步骤标识（展示位） | `stepType ∈ PATCH / SCRIPT`；`stepLabel` 取声明侧 `label` |
| `stepEvent` | String | ③ 归组判据 | `START` / `SUCCESS` / `FAILURE` / `ROLLBACK` / `NOTE`。**收尾行**（§14.13.3 起回容器）取 `SUCCESS` / `FAILURE` 且 `stepId == null`——它因此**不进 KPI-02 的分母**（规则 2），但仍是可机械判定的阶段级事件行 |
| `elapsedMs` | Long | ② 耗时承载位与单位 | 毫秒整数（权威值）；仅 `SUCCESS` / `FAILURE` 与阶段完成行非空 |
| `exitCode` | Integer | ②′ **脚本退出码承载位**（v0.3 新增，回应 REV-6 的契约缺口） | 仅 `SCRIPT` 步骤的终态行（`SUCCESS` / `FAILURE`）非空；`PATCH` 步骤与阶段级行恒为 `null`（执行器不回报退出码，不为此改 `PatchInstallExecutor`）。FR-15 的「`exitCode ≠ 0` 判失败」自此有字段可判，不必落到 `message` 文本 |

**承载位分工（写死，防止「日志里有」被读成「可核对」）**：

| 内容 | 承载位 | 是否机械判据 |
| --- | --- | --- |
| 步骤归组 / 齐备 / 串行 | `stepId`、`stepEvent`、`elapsedMs` | **是**（规则 1–3） |
| 脚本退出码 | `exitCode` | **是**（规则 4） |
| 补丁回滚结果的**存在性** | `stepEvent = ROLLBACK` 行的存在与位置 | **是**（规则 5） |
| 回滚**是否成功** | `message` 文本（转写执行器 `已回滚备份` / `回滚失败: …`，`:215`/`:218`） | **否**——AC-09 的判据是**目标路径文件比对**（V-05），不是这行文本 |
| `stdout` / `stderr` | 仅进 `message`（`stepEvent = NOTE` 行，§8.3 截断规则） | **否**——可见性核对单独登记（V-22 的第二个判据块），与 AC-12 的失败判定分离，不用文本匹配冒充机械核对（该口径须随 **D-P17** 回写进 PRD §8.5，否则 FR-15 的「输出进日志」会在验收时被要求成文本匹配） |

**五条规则，逐条可核对**：

1. **归组**：`stepId` 相同的所有行属于同一步骤；一次部署内 `stepId` 与 `stepIndex` 一一对应。
2. **「三项齐备」判据**：某步骤齐备 ⇔ 该 `stepId` 下恰有一个 `stepEvent = START` 行 + 恰有一个终态行（`SUCCESS` 或 `FAILURE`），且该终态行 `elapsedMs != null`。KPI-02 分子 = 满足此式的步骤数，**分母 = 非空 `stepId` 的去重计数**（v0.3.1 按 SUG-13 写死：`stepId == null` 的**阶段级行**——进入 / 交棒 / 停实例 / BR-12 / 目录不合法 / **收尾** / **阶段完成行**——一律不进分母。它们按构造永不满足本式，含进来则再正确的实现也只能得到 n/(n+1)，KPI-02 恒 < 100%，判据在字面上不可满足）——纯字段判定，不读 `message`。
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
| 改什么 | 扩展阶段全部步骤判定完成后，追加一次收尾动作：以既有 `docker compose -p … up -d` 形状（`DockerComposeAdapter:260` 已在用同一条命令）把容器恢复到运行态；**成功 → 记 `SUCCESS` 收尾行 → 阶段完成行 → 交棒行（进 `HEALTH_CHECK`）；失败 → 按致命处置（`FAILURE` + `level = ERROR` 收尾行，部署 `ERROR`，不进入 `HEALTH_CHECK`）**——两支各产一条独立收尾行，v0.3.2 按 SUG-19 定死（Leader 裁定取「两支都产行」）。成功支不产行的旧写法作废：§8.1 的收尾耗时预算（compose ≤ 1200 s + 5 s、lgsm-docker ≤ 1200 s + 8 s）自此有它自己的承载行可核对（该行的 `stepEvent = SUCCESS` 落在 §14.6 `elapsedMs` 行的非空集合内；`elapsedMs` 记的是**收尾这一段**，不是整个阶段——阶段总量由紧随其后的阶段完成行承载） |
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

> **本表两列的来源不同（v0.3.1 按 SUG-15 前置说明，供 §3.3 组 L / B-16 指回此处）**：**命令**一列两类都照抄各自 `DEPLOY` 在用的那一条；**就绪判定**一列只有 compose 类照抄自己 `DEPLOY` 的判定（`ps` 认 `running`/`Up`，`:270-287`），`linuxgsm-docker` 类**不**照抄它 `DEPLOY` 的 `ps` 判定（`:234-239`），而取该类 `healthCheck` 的逐个容器 `.State.Running`（`:430-468`）。理由：收尾判据必须与**紧随其后的 `HEALTH_CHECK` 判据**同形，否则可能「收尾判过而 `HEALTH_CHECK` 判不过」——这一支取的是**更严**的那个，不是 `DEPLOY` 的那个。

| 项 | `docker-compose` | `linuxgsm-docker` |
| --- | --- | --- |
| 命令 | `cd <workDir> && COMPOSE_HTTP_TIMEOUT=300 timeout 1200 <composeCmd> -p <projectName> up -d`，与 `DEPLOY` 的 `:259-261` **逐字同形**（含 env 前缀与 shell `timeout` 兜底：`SshUtil` 的 `timeoutMs` 只作用于建连，命令本身无超时会永久阻塞部署线程），SSH 超时 `1200000` | `cd <workDir> && timeout 1200 <composeCmd> -p <projectName> up -d`，与该类 `deploy()` 的 `:222-223` 同形，SSH 超时 `1200000`。**两类不是同一条命令**：lgsm-docker 的既有 `up -d` 不带 `COMPOSE_HTTP_TIMEOUT=300`（该类只有 `stop()` 带）。本设计**逐类照抄各自 `DEPLOY` 已在用的形状**，不做统一——统一即改动既有类别的行为 |
| 就绪判定 | `sleep 5000` → `<composeCmd> -p … ps` 输出含 `running` 或 `Up`（镜像 `:270-287` 既有判定；V1 输出 `Up`、V2 输出 `running`，两者都要认），失败时取 `logs --no-color --tail 50` 并经 `stripAnsiCodes` 后写进失败原因 | `sleep 8000`（镜像该类 `deploy()` `:229-231` 的 entrypoint 等待）→ `ps -q` + **逐个**容器 `docker inspect -f '{{.State.Running}}'` 全为 `true`（镜像该类 `healthCheck` `:446-468` 的判据）。**判据必须与该类自己的 `HEALTH_CHECK` 同形**，否则收尾判过而 `HEALTH_CHECK` 判不过 |
| **禁止**复用 `ensureContainerRunning` | — | 该类 private `ensureContainerRunning`（`:955-993`）在「容器存在但已停止」分支走的是 **`compose start`**（`:970-973`）而非 `up -d`——正是 RISK-D11 否掉的形状；且它对 `ps -q` 的**第一个**容器判完即 `return`（`:979`），多容器项目根本不会逐个确认。所以本类的收尾**不复用它**。同时订正 §14.12 早先的表述：把它当「先例」指的只是「停后再起」这个动作存在，**不是**它的命令形状可用 |
| 起停次数 | `up -d`（DEPLOY）起 → 停 → 起 各一次（§14.12 结论不变） | 同左（`deploy()` 的 `up -d` 起 → `stop()` 停 → 收尾 `up -d` 起） |
| 后续阶段 | `HEALTH_CHECK` / `UPDATE_STATUS` / `START` / `retryHealthCheck` / `COMPLETE` 一字不改；`START` 面对已运行容器 = 今天的「空操作 + 复检」 | 同左；`START` 的 `adapter.start()` 仍 = `ensureContainerRunning`（此时容器已运行 → 短路）+ `linuxgsm start`，与今天 `DEPLOY` 之后的行为逐字相同 |
| 收尾是否重做 `DEPLOY` 末的回写 | **不重做**：`DEPLOY` 已写 `installPath` / `startCommand` / `stopCommand` / `runtimeMetadata`（`:291-300`），收尾只负责「运行态」这一件事 | 同左 |
| 失败处置 | **致命**：记 `stepEvent = FAILURE` + `level = ERROR` 的收尾行（该行的**归组与统计**按 §14.6 规则 1/2：它是阶段级行、`stepId == null`，故不进 KPI-02 分母——v0.3.1 按 SUG-13 订正交叉引用，v0.3 误写为「规则 5」，规则 5 是 `PATCH` 步骤 `ROLLBACK` 行的判据，与本行无关），部署判失败、实例 `ERROR`、不进入 `HEALTH_CHECK`。与 §14.5 停实例失败同口径；BR-14 只约束失败步骤的回滚边界，收尾不是补丁步骤、无回滚诉求 | 同左 |
| 幂等 | `up -d` 对已运行服务是空操作 ⇒ 即便「实例其实没被停下」这一病态情形，收尾也不会二次启动 | 同左 |
| 耗时预算 | 计入 §8.1 阶段预算：compose 类 ≤ 1200 s（shell `timeout`）+ 5 s；lgsm-docker 类 ≤ 1200 s + 8 s | 同左（数字不同） |

### 14.14 行 12（REV-1）：每主机互斥的真实承担者，与本期补法（Leader 裁定取 ①）

**先把事实钉正**（评审与 Leader 均已复跑）：`PatchInstallExecutor` 类头 Javadoc 原话是「同主机互斥（**由任务中心 `scopeKey=hostId` 承担**）」（`:35`），类内只有 `globalSemaphore`（`:56`）。互斥真正发生在 `TaskServiceImpl.submit`：`computeMutexKey` 默认规则 `taskType + ":" + scopeKey`（`:566-578`）+ 内存管理器 `taskMutexManager.putIfAbsent`（`:151`）。而 §14.1 的机制**恰恰是绕开 `submit` 直调 `execute()`**——被绕掉的正是这条互斥。v0.2 写成「原样继承」不成立。

**后果是具体的**（不是理论风险）：执行器在宿主机使用**非命名空间化**的共享临时路径——`/tmp/patch_install_<millis>`（`:141`，`finally` 里 `rm -rf`，`:227`）与 `/tmp/patch_push/<fileName>`（`:637`、`:649`，**仅按文件名**，且**全仓无任何清理**——`:227` 那句 `rm -rf` 删的是前者，不含后者；v0.3.1 按 SUG-12 订正）。同主机两实例并发跑扩展步骤、或扩展步骤与用户手工发起的 `PATCH_INSTALL` 任务并发（**今天后者会被互斥键挡住，绕开后就挡不住了**），可互相覆盖同名文件 / 删掉对方正在使用的目录 ⇒ 交付错文件。

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
| 锁粒度 = 共享资源的粒度 | 只包住**单次 `execute()` 调用**，不跨整个扩展阶段、不跨 `SCRIPT` 步骤。**依据（v0.3.1 按 SUG-12 收窄，v0.3 原文「两处共享路径都在一次调用内创建、消费、`finally` 清理 ⇒ 锁覆盖范围与资源生命周期一致」中「`finally` 清理」那半句对 `patch_push` 不成立）**：真正的依据是「**同一承键窗口内完成写后读**」——`pushExtracted`（`:627`：`/tmp/patch_push/` 路径拼在 `:637`、SFTP 上传 `:638`、`docker cp` 消费 `:639`）与 `pushSingleFile`（`:645`：拼 `:649`、上传 `:650`、消费 `:651`）都在**同一次 `execute()` 的同一循环迭代内**写完即消费（`pushIntoContainer` 在 `:658`），因此键只要覆盖到消费点即安全；而 `/tmp/patch_push/<file>` 的残留（无清理）**对后续同键写入无害**——下一个持键者同名写入即覆盖，不存在读到别人旧值的路径（读只发生在同一次调用内）。**残留跨实例累积的收口属后续增量**（§13.2 ② / D-P16）。**红线**：不得「为省一次轮询」把锁提到阶段级——那会把 30 min 的脚本步骤算进持锁时间，把同主机的补丁能力拖死（登记 RISK-D14） |
| 等待预算取 600 s | 与等对象同量级：一个补丁任务自身的最坏耗时就是 `SSH_TIMEOUT_MS = 600_000`（`:54`）这一档（任务中心给该任务的总超时是 1 h，见 `PatchInstallHandler:33`）。取 30 s / 60 s 会把「同主机正在跑一个正常补丁」判成失败；不封顶则挂键不放。等满 → 该步 `FAILURE`（默认致命 → 部署失败），日志原因段指名「等待同主机补丁互斥超时」——**与超时失败同类可读，不静默降级为「跳过该步」** |
| 失败语义不新造闸门 | 这是 BR-09 已要求的互斥，不是 PRD 未要求的总耗时闸门，故不适用 §8.1「本期不加聚合上限」的理由 |
| 诚实限制（四条，全部进 RISK-D14） | ① 内存键、单进程有效（与 ADR-018 现状同，非本期新增缺口）；② 崩溃后 `TaskCrashRecoveryRunner.clear()` 启动时清空 ⇒ 重启即释放，与既有一致；③ **无排队公平保证**——多部署线程竞争同键可能饿（实际面受全局并发闸 3 约束）；④ 等待期间该部署线程被占住，叠加 RISK-D04 的 commonPool 长阻塞 |
| 评审的 ②（改判「本期不提供」） | **不取**（Leader 裁定）：那是需求口径收缩，须走人类，而无需收缩 |
| 评审的 ③（给临时路径加实例命名空间） | **本期不授权**（Leader 裁定）：动的是 ADR-0006 既有行为、风险半径超出本期。登记为**后续增量建议**（`/tmp/patch_install_<ts>` 加 `_<instanceId>`、`/tmp/patch_push/` 加实例子目录），**该增量的理由随 SUG-12 强化**：`patch_install_<ts>` 至少会被 `finally` 删掉，`patch_push/` 下的文件**全仓无人清理** ⇒ 残留跨实例、跨部署在宿主机 `/tmp` 累积（本期互斥结论不受影响，见上行「锁粒度」）；交人类 Owner 决定是否另立 Issue，本设计不把它算作本期交付 |

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
 *  两个实现者就是已有的那两个 record ⇒ 目录条目的 patches ++ scripts 零包装即得一个有序混合清单
 *  （拼接处需显式类型见证：Stream.<DeployExtensionStepDeclaration>concat(...)，见下表「推导规则」）。 */
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
| 与 `DeployVersionDeclaration.patches` / `scripts` 的关系 | 两者元素类型即 sealed 的两个 permitted 子类型 ⇒ **加显式类型见证后**零包装即得有序混合清单：`Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList()`，**无适配层、无字段复制**。v0.2 的「② 取条目 `patches ++ scripts` 按声明序编号」在缺这个上界时**拼不成一个 list**（只能拼成 `List<Object>`），这正是悬空类型的根因。<br>**推导规则（v0.3.1 按 SUG-11 补，附可复跑证据）**：**不带**类型见证的字面写法 `Stream.concat(patches.stream(), scripts.stream()).toList()` **编译不过**——`concat` 的签名是 `<T> Stream<T> concat(Stream<? extends T>, Stream<? extends T>)`，两个 `? extends T` 独立推导后取交，推出 `T = INT#1`（`INT#1 extends Record,Step`），而**泛型不变** ⇒ `List<INT#1>` 不能赋给 `List<DeployExtensionStepDeclaration>`。javac 17.0.16 实测原文：`错误: 不兼容的类型: List<INT#1>无法转换为List<DeployExtensionStepDeclaration>`（`ArchReviewer` 与 Leader 各跑一次、本文作者第三次跑，结论一致）。⇒ 「零转换」这一结论成立（不需要包装类型、不需要适配层、不复制字段），但**它是有条件的**：类型见证是必要的，B-01 的判据据此改述 |
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
| N1 | `imageTag` 存在 ⇒ 该 deployType 的 `variables[]` 必含保留键 `PLATFORM_IMAGE_TAG` | **`game_metadata` 表快照**（与 `buildDeployConfig` **同一数据源与同一取值路径**，v0.3.1 按 SUG-14 改述，原「同一 map」不可强制；禁止走 `GameServiceImpl` 合并视图） | §14.4、§14.4.1 R1 |
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

> **第 2 轮九行（v0.3.1）**：对照 ArchReviewer 第 2 轮复审（评审对象 `design.md @ fd2c136`，评论 `01a0bef8`，结论 **PASS**）新提出的 SUG-10…SUG-18 与 Leader 的并入裁定（评论 `01a0befc`：九条**一轮全落净**，不留给 PRD v0.6 批）。这九条**不翻任何判定**，全部是落点/依据文本与代码对齐；下表 SUG-10…SUG-18 各行即逐条处置。第 1 轮的 REV/SUG 行**结论与状态一字未动**，只在被这九条订正到的那几格里就地改述并标出「v0.3.1」。
>
> 对照 ArchReviewer 第 1 轮（评审对象 `design.md @ 8a6e2c2`，评论 `01a0beae`）与 Leader 的门禁裁定（评论 `01a0beaf`：REV-1 取①、REV-3 按 FR-11 字面收口）逐条填写。**7 项阻断全部采纳；9 项建议中 8 项全采纳、1 项部分采纳（SUG-7 的判据形态）；REV-2 采纳结论、不采纳其「`AbstractDeployAdapter` 空实现」这一形状**，理由见表末列。三条硬判定的**结论一字未翻**（`imageTag` 给机制 / `position=container` 不合法 / §8.5 契约三项），只按 SUG-2/3 换依据文本与行号。

| # | 处置 | 落点（改了哪几节） | 说明 / 不采纳部分的理由 |
| --- | --- | --- | --- |
| **REV-1** | **采纳，按 Leader 裁定取 ①** | §14.1 表「BR-09 资源约束」行改判（原「原样继承」是假自述）、**§14.14 全节**、§3.3 组 B、§8.2 整节重写、§6.2 责任表 +2 行、§7.2 B-04 判据、§8.1 预算 +互斥等待行、§10 **V-26**、§12 **RISK-D14**、§13.1 **D-P16**、§2 D-N05 边界句 | 补法**不新造锁**：`installSync` 复用任务中心同一个 `TaskMutexManager` 承同一个键 `PATCH_INSTALL:<hostId>` ⇒ 与任务中心提交的 `PATCH_INSTALL` 任务**也**互斥（自建 `Map<hostId,Lock>` 只能挡住扩展阶段内部，等于 BR-09 仍不成立）。锁粒度 = 单次 `execute()`（**依据文本 v0.3.1 按 SUG-12 收窄**：不是「与两处共享路径的生命周期一致」——`/tmp/patch_push/<file>` 无清理；而是「同一承键窗口内完成写后读，残留对后续同键写入无害」，见 §14.14）；不违反 D-N05（不 submit、不建 TaskRecord、不占其线程池）。**②不取**（Leader 裁定：需求收缩须走人类）；**③本期不授权**（Leader 裁定：动 ADR-0006 既有行为），已作为后续增量建议登记进 §13.2 |
| **REV-2** | **采纳结论；形状部分采纳** | **§14.13.2**（default 方法定义 + 调用方 + 红线）、§3.3 **新增组 L**、§4 新增 4 行（含风险列「高（接口面扩大）」）+ 负向清单订正、§7.2 **B-16**、§8.5 兼容性 +表、§10 V-10 子项 + **V-27**、§11 AC-15 行、§12 **RISK-D13** | 采纳「`DeployAdapter` 新增 default 方法 + 各容器适配器覆写 + 同步 §4 负向清单与 §3.3 分组 + 把『新增 default 方法不改变无扩展部署行为』写进 AC-15/V-10 判据」全部四项。**唯一不采纳的是「`AbstractDeployAdapter` 空实现」**：空实现 = 返回 `true` = 静默假装已起回，恰好制造 §14.12 判掉的那个失效且无归因；改为默认抛 `UnsupportedOperationException`，与本接口既有先例 `stopServer`（`:185-187`）同形 |
| **REV-3** | **采纳，按 Leader 裁定按 FR-11 字面收口** | **§14.13.1**（支持集合 + 逐类排除理由）、**§14.13.3**（两类逐类形状）、§16.3 **N5**、§5.5 第③点、§9.2 +2 行、§10 V-11/V-12 改造 + **V-27**、§7.5 **T-05**、§12 RISK-D01（三类同时成立的认知订正）/D11（追加禁令）、§13.1 **D-P15** | 集合 = {`docker-compose`, `linuxgsm-docker`}，集合外（含 plain `docker`）**声明即不合法** → §8.1 → BR-12 既有处置，并回写 FR-11 / AC-04 适用范围。Leader 开的改判窗口（「某类收口成本低于回写成本可提」）已核并**放弃改判**：`docker` 类的「起回」要么依赖 private `getContainerName`（`:629`）、要么重推全套 `docker run` argv（= §14.5 判 `container` 不合法的同一条「新造执行模型」理由），成本高于回写；`linuxgsm` 类的停/起是进程级语义、PRD 未点名，「可能可用而未验证」不作为放进集合的理由。**无留白** |
| **REV-4** | **采纳，二选一定死为 sealed interface** | **§16.2**（定义体 + 7 类型清单 + 关系与转换四条）、§3.3 组 A、§7.1 B-01/B-02 判据（**加显式类型见证后** `Stream.<DeployExtensionStepDeclaration>concat(...)` 可赋给该上界即闭合判据——v0.3.1 按 SUG-11 改述，原判据字面必编译失败）、§4 类型行数、§6.2、§11 AC-06/AC-18 行 | 取「`sealed interface` + 两个**已有** record 实现（带 `kind()`）」，不取「单 record + `type` 判别位」：后者要把 §8.2/§8.3 的两套必填与「正文/URL 二选一」规则整体推到运行期校验，而本期口径是「不合法即整目录 `INVALID`」——能在编译期挡住的非法组合不留到运行期。与 `DeployVersionDeclaration.patches/scripts` 的关系 = 元素类型即两个 permitted 子类型 ⇒ 拼接**零包装、零适配层、零字段复制**（v0.3.1 订正：这条**有条件**——需 `Stream.<DeployExtensionStepDeclaration>concat(...)` 显式类型见证，无见证的字面拼接在 javac 17 下推导交类型后因泛型不变性不可赋值；结论不变，原判据表述不成立，见 §16.2 推导规则与 SUG-11 行）；v0.2 的「② `patches ++ scripts` 按声明序编号」在缺该上界时根本拼不成一个 list，这正是悬空的根因。类型计数由「4」订正为 **7**（v0.2 既漏 sealed interface 也少算了两个 enum/record） |
| **REV-5** | **采纳全部三条** | **§14.4.1 R1/R2/R3**、§16.3（N1/N2 + 判定通道列）、§7.2 B-05、§10 **V-02 反例 a/b**、§12 RISK-D07、§13.1 D-P05（改写） | R1 把蕴含规则的判定对象绑死「`game_metadata` 表快照，与 `buildDeployConfig` **同一数据源与同一取值路径**，禁止走 `GameServiceImpl` 合并视图」（v0.3.1 按 SUG-14 改述：原「同一个 map 实例」不可强制也无必要——`buildDeployConfig` 每次 `new HashMap<>()` 再 `putAll`）；R2 明写经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` 不参与 `imageTag` 机制（与 RISK-D07 同一缺陷）；R3 新增「表侧模板必含 `${PLATFORM_IMAGE_TAG` 字面量」校验（不认 `$VAR` 简写），否则注入后无消费者 = 静默无效。V-02 的反例 a 专门证明「换个入口就能骗过校验」这条路径已关 |
| **REV-6** | **采纳** | §14.6（**第六字段 `exitCode`** + 承载位分工表 + **规则 4/5**）、§10 **V-21…V-25**（五条新行）+ V-26/V-27、§11 AC-01/AC-12/AC-16/AC-18 四行落点、§7.4 F-04、§7.5 T-03/T-04、§3.3 组 G、§6.1、§6.3 | 五条缺行逐条补齐：AC-01 正向（V-21，接口 + 界面双侧）、AC-12（V-22 两个判据块：`exitCode==3 ⇔ FAILURE` 机械 / 输出可见性单独登记）、AC-16（V-23 E2E DOM 断言）、AC-18（V-24 **含 16.2 ① 代码计算型支**，此前零测试）、KPI-04（V-25 三条具体命令 `mvn test` / `npm run test:run` / `npm run e2e` + 分母 = 基线清单不减 + 分子 = 通过率）。`exitCode` 通道能力核对为已有（`FileAccessService.CommandResult:235-244`），属设计漏登记，现补；`stdout`/`stderr` 明确「仅进 `message` 且不参与任何机械判据」，AC-12 判据与之分离（评审给的两个选项取第二个） |
| **REV-7** | **采纳四条 + 另加项** | ① **§14.15**、RISK-D12、V-28、**D-P10**（`configInfo` 半边安全已写进 §14.15 表第 4 行，含 `:329`/`:349` 证据）；② **D-P11**（并集计数「19+3+2+1+1=26」须随 D-P01/D-P04 重跑 + 第五行标题同时容纳两种前提状态）；③ **D-P12**（§8.5/§11.1 内联标注/AC-03/AC-16 四处「契约未登记时记不可测」句式出表）；④ **D-P13**（载体扩到桩插件 + 桩游戏元数据，AC-26 ② 核对物扩到「`./games` 不留桩 yml」）；另加 **D-P14 + §14.0.1**（十四行逐行状态收口，含证据指向） | 无保留意见。v0.2 全文对 RISK-09 0 次出现是实打实的漏项，且 PRD 明写「交 @Architect 明确」；§14.0.1 的写法是「状态列 + 证据节号 + 回写项」三列，确保 S2a/S2b 不再把已判定项当开环 |
| SUG-1 | **采纳（删除）** | §3.3 组 F | 末项「扩展分支内跳过 `HEALTH_CHECK` 容器态探测」已删除——它是被 §14.12 专列一行否决的旧方案，留在改动分组里会把实现者引向被判否的形状（评审判断正确：全文唯一残留）。组 F 现改为「收尾调 `adapter.ensureRunningForExtension(...)`」 |
| SUG-2 | **采纳（换依据 + 显式登记第三条路）** | §14.5 依据 3 改写、**§14.5.1** 三条绕法逐条否决表 | 承认 v0.2 的后半句「要重新推导全部挂载与网络」对 `compose run --rm` **不成立**（compose 自行解析服务定义）。现用三条各自独立成立的依据否决：执行对象是兄弟容器而非目标容器 / 镜像内文件层改动随 `--rm` 丢弃（非卷路径静默无效）/ 必须带 `--no-deps` 而此刻依赖已被停下。`compose run --rm` 已作为**已被否的第三条路**显式登记，供人类 G4 复核 AC-13 移出时使用；结论一字未翻 |
| SUG-3 | **采纳三处** | ①§14.4 首段改述为「无 tag/变量类替换，但 `:1216-1219` 有两处结构性后处理」并说明 AC-14 核对物① 为何仍成立（比较基线自带后处理，V-15 措辞同步）；②§14.5 停止判定签名订正为 `getStatus(instanceId, config)`（`DeployAdapter:226`）；③§14.12 行号 `:216→:217`、healthCheck 抛出处 `:218-220`，§14.7 与 V-10 的「进入行」统一改述为**顶层 `progress` 值**（`updateTaskStatus:844-851` 不产日志行） | 三处均不改结论，属技术事实表述偏强 / 行号偏差，全部照改 |
| SUG-4 | **采纳并加强** | §5.2 `PLATFORM_IMAGE_TAG` 行整行改写（承认它**就是**真实 `configInfo` 键，附 `deploy.vue:246-250`/`:714` 证据（v0.3.1 按 SUG-17 订正：展开行是 `:714`、`:713` 是 `isComposeVariableDeploy()` 守卫，故该结论限定为「compose 变量类部署下」））、§14.4 表「框架侧唯一新增」改两级门控、**§14.4.2**（新增：v0.2 那条「列入 §8.4.3 禁止清单」的建议会把 AC-14 正向路径判 400，已改口径）、§8.4 新增「无用户输入面的边界」行、§14.4.1 R4 格式校验、§10 V-15 第四核对物、§13.1 D-P05 改写 | 除订正自述外，把「值不可被用户左右」从**断言**做成**不变式**：该游戏一旦声明这个保留变量，其值一律由平台写（条目 `imageTag` 或该变量 `defaultValue`），提交值不进 `.env`；未声明则完全不写 ⇒ 「不声明的游戏逐字节不变」仍然成立。格式校验同时堵 `.env` 行结构被改写（`generateEnvFileContent` 不转义、不过滤换行）。附带的 BR-07 清单矛盾是本设计自查发现，评审未点名，一并修 |
| SUG-5 | **采纳** | **§14.4.3**、§3.3 组 E、§7.2 B-08、§10 **V-29** | 登记注入点副作用（10 个调用点含 `updateInstance:183` 与 start/stop/restart/文件/备份），处置为「仅当 `configInfo` 含 `deployVersion` 才读目录」；同时**明写不加缓存的理由**（缓存会把「热部署即生效」变成「过一会儿才生效」，属新语义），并把 RISK-D07 的扩散面从「所有配置组装」缩到「带版本键的部署」 |
| SUG-6 | **采纳** | §14.6 末段单位口径、§3.3 组 H、§7.4 F-03 | 承认 `formattedElapsedTime` 是读 `elapsedTime` 的 **computed**（`:72-85`）不是可复用函数 ⇒ F-03 的形状改为「先参数化为 `formatElapsed(seconds)` 纯函数」；ms→s 口径定死 `max(1, round(ms/1000))`，消掉「快速步骤渲染成 0 秒 = 等于没显示」的观感缺陷；换算只在渲染层，VO 与核对脚本永不出现秒值 |
| SUG-7 | **部分采纳** | §14.6 规则 5 + 承载位分工表、§11 AC-09 行、§12 **RISK-D15**、§10 V-05/V-27③ | 判据已给（机械）：「`PATCH` 步骤终态 `FAILURE` ⇒ 同 `stepId` 下该终态行之后、下一步 `START` 之前**恰有一行** `ROLLBACK`」+ `level` 由回滚结果定（`已回滚备份`→INFO / `回滚失败`→ERROR，PRD §12「回滚失败需人工介入」行自此在部署日志里有落点）。**不采纳**「按回滚结果决定是否产该行」的结构化形态：执行器只经 `onLog` 文本回报（`:215`/`:218`），要拿结构化信号必须改 `PatchInstallExecutor` 的回调接口——与 Leader 就评审 ③ 已裁的「不动 ADR-0006 既有行为」同类，本期不做；回滚是否成功改由 V-05 的文件比对判，不用文本匹配冒充机械核对（诚实限制已登记 RISK-D15） |
| SUG-8 | **采纳，点名一条路** | §9.1 上线顺序②、§7.3 B-14 判据挂钩 | 选 **`--jar` + env 覆盖**，不改脚本：`mvn -pl plugin-dnf-tw/plugin-dnf-tw-core -am install -DskipTests` 后 `PLUGIN_ID=… JAR_NAME=… bash scripts/deploy-plugin.sh --jar <target jar>`。理由（行号 v0.3.1 按 SUG-17 订正，路线与结论不变）：`FRONTEND_DIR`/`PLUGIN_MODULE` 硬编码 l4d2（`:27-28`），`--jar`（解析在 `:44`、分支体 `:52-54`）整段跳过构建臂（前端与 JAR 构建都在 else 臂 `:56-68`）恰好绕开它——**`--jar` 确实同时跳过前端构建与 JAR 构建**，卸载/覆盖/加载三步全按 `PLUGIN_ID`+`JAR_NAME` 走（`:110-135`，加载在 `:133`、`purgeTasks=false` 在 `:114`）。**不选「参数化脚本」**：要动 l4d2 在用的既有脚本，属范围外且无必要 |
| SUG-9 | **采纳** | §10 V-10（比对面限定为**阶段序列 / 顶层 `progress` 值 / 日志行 `stage`+`level` 原值 / 终态 / 状态转移**五项，明写「不含 CSS class 与图标名」；第五项「状态转移」由 SUG-16② 在 v0.3.1 补入，与 PRD AC-15 的五项列举对齐）、§7.5 T-04、§11 AC-15 行 | 定性接受：RISK-D03 的「缺陷修复的正当外溢」与「AC-15 比对含 class」确实互斥——归一化后此前同为 `log-info` 的非 INFO 行开始变色，把它算作回归会让两条结论自相矛盾 |

**第 2 轮九行（v0.3.1 落文，Leader 裁定一轮落净）**：

| # | 处置 | 落点（改了哪几节） | 说明 / 订正后的事实 |
| --- | --- | --- | --- |
| **SUG-10** | **采纳**（Leader 点名的两条必核销项之一） | §3.3 组 G（模块列 + VO 归属）、§4 受影响组件该行整行重写、§7.2 B-11、§6.1 首行 URI + 新增「扩展字段只在 `deploy-progress` 通道结构化」段、§10 V-21 方法列、§14.6 首段 | 核对为真：**仓内不存在** `backend/api/.../vo/LogEntryVO.java`——`api` 的 `vo/` 只有 `GameVO`/`HostVO`/`InstanceVO`/`LoginVO`/`UserVO`/`HostResourceVO` + `docker` 子包（本次 `ls` 复跑一致）；`LogEntryVO` 是 `InstanceController` 嵌套静态类（`:889-896`）、`DeployProgressVO` 同（`:875-884`），`DeployConfigVO` 在 `backend/core/.../vo/`；真正的 `LogEntry → LogEntryVO` 映射在 `:497-503`，v0.3 引的 `:876-897` 是 **VO 声明段**。组 G 模块列改 **`backend/core` 单模块**（原「`+ backend/api`」会把 @BackendDev 引去 api 模块凭空搬一次 VO）。URI 同步：`GameMetadataController:85` 是 `@GetMapping("/{id}/deploy-config/{deployType}")`——**路径段**，v0.3 的 `?deployType=` 形式与 V-21 的省略形式一并订正。另补 v0.3 漏写的一条：`GET /instances/{id}/logs`（`InstanceController:372`）把 `LogEntry` 摊平成 `[time] [level] message` 文本（`:392-396`），**扩展字段在该通道不结构化**，不写清会有验收者去那儿找 `stepId` |
| **SUG-11** | **采纳**（判据缺陷由本设计认领：该判据是第 1 轮 REV-4 要求作者自证时写的） | §16.2「与 patches/scripts 的关系」行（改写法 + 补推导规则）、§16.2 定义体注释、§7.1 B-01 判据、§17 REV-4 行 | 本设计第三次实跑复核（javac 17.0.16）：字面 `Stream.concat(patches.stream(), scripts.stream()).toList()` 赋给 `List<DeployExtensionStepDeclaration>` → `错误: 不兼容的类型: List<INT#1>无法转换为List<DeployExtensionStepDeclaration>`（`INT#1` 为交叉类型，扩展 `Record` 与步骤接口）；`Stream.<DeployExtensionStepDeclaration>concat(...)` → 通过。**REV-4 的结论不变**（sealed 上界仍成立、仍零包装零适配层零字段复制），只是这条「可编译性判据」必须改述为「**加显式类型见证后**可赋给该上界」；推导规则（`concat` 的两个 `? extends T` 独立推导取交 + 泛型不变性）写进 §16.2 |
| **SUG-12** | **采纳**（订正的是一条被门禁跟着写过的假依据） | §14.14「后果是具体的」段 + 「锁粒度」行（依据改述）、§14.14 评审③ 行、§13.1 D-P16、§13.2 ②、§17 REV-1 行 | 核对为真：`finally` 的 `rm -rf`（`:227`）删的是 `hostTmpDir = "/tmp/patch_install_" + millis`（`:141`）；`/tmp/patch_push/`（`:637`/`:649`）**全仓无任何清理**（本次全仓 grep 复跑：只有这两处命中，均为写入）。⇒ v0.3「两处共享路径都在一次调用内创建、消费、`finally` 清理 ⇒ 锁覆盖范围与资源生命周期一致」**不成立**，改为「**同一键窗口内完成写后读**，`patch_push` 残留对后续同键写入无害（下一个持键者同名写入即覆盖，读到旧值的路径不存在——消费只发生在同一次调用内）」。互斥结论不受影响（评审与本设计同判）；「跨实例累积」改写进 §13.2 ② 与 D-P16 作为该后续增量的理由 |
| **SUG-13** | **采纳**（Leader 点名的两条必核销项之一：原判据字面不可满足） | §10 V-08 判据、§14.6 规则 2、§14.6 `stepId` 行枚举 + `stepEvent` 行、§14.13.3「失败处置」行交叉引用 | 核对为真：`分母 = stepId 去重计数`未排除 `stepId == null` 的阶段级行，而进入行 / 交棒行 / 收尾行（`stepEvent=FAILURE`+`level=ERROR`，§14.13.3）按构造永不满足「恰一 START + 恰一终态」⇒ 正确实现也只能算出 n/(n+1)，KPI-02 恒 < 100%。现分母写死「**仅统计非空 `stepId`**」，阶段级枚举补进 §14.6 `stepId` 行（含此前漏列的**收尾行**），并在 `stepEvent` 行说明收尾行的取值与「不进分母」。§14.13.3 的交叉引用「§14.6 规则 5」→ **规则 1/2**（规则 5 是 `PATCH` 步骤 `ROLLBACK` 行的判据，与收尾行无关） |
| **SUG-14** | **采纳** | §14.4.1 R1 行（改述 + 写明为何不可强制）、§16.3 N1 判定通道列、§7.2 B-05、§17 REV-5 行 | 核对为真：`buildDeployConfig` 每次 `new HashMap<>()`（`:688`）再 `putAll(表侧 typeConfig)`（`:698`），另起的 `DeployVersionCatalogService.read()` **拿不到也不必拿**同一个 map 实例 ⇒ 「同一个 map 实例」是一条无法执行也无法测的约束。改述为「**同一数据源与同一取值路径**」（`gameMetadataMapper.selectById(gameId).getDeployConfig().get(deployType)`）。**判据不降级**：可核对性仍由 V-02 反例 a 承载（那是行为测试，不是措辞） |
| **SUG-15** | **采纳**（改措辞，不改被选的判据） | §3.3 组 L、§7.2 B-16、§14.13.3 表前置说明 | 核对为真：`linuxgsm-docker` 的 `DEPLOY` 就绪判定用的是 `ps` 认 `running`/`Up`（`:234-239`），而 §14.13.3 给它的是该类 `healthCheck` 的**逐个容器** `.State.Running`（`:430-468`）⇒「命令与就绪判定逐类照抄各自 `DEPLOY`」对本类的**就绪判定**不成立。现按 §14.13.3 的两列分开写：**命令**照抄各自 `DEPLOY`；**就绪判定**两类来源不同（compose 取自己 `DEPLOY` 的、lgsm-docker 取自己 `HEALTH_CHECK` 的）。设计取的是**更严**的那个（收尾判据 = 紧随其后的健康判据 ⇒ 不可能「收尾判过而 `HEALTH_CHECK` 判不过」），此项已核对与 §14.13.3 原判定一致，不改任何结论 |
| **SUG-16** | **采纳两条** | ① §13.1 D-P12 条目列（回写清单补一处）；② §10 V-10 比对面（四项 → **五项**）+ §7.5 T-04 同步 + §11 AC-15 行「四项」→「五项」+ §17 SUG-9 行同步 | ① 核对为真：同一套「生效前提」条件句在 PRD 有**两处**——§8.5 末段（行 326）与 **§13.1 引言约定块（行 441）**，后者同时挂着 AC-13/AC-14 与 KPI-02/AC-03/AC-16；只改前者会让验收侧继续把已可测条目读成「不可测」（本次读 PRD `270f9d0` 行 326/441 复核一致）。② 择「补一项」而非「加一条 D-P 交代关系」：PRD AC-15 明列五项（阶段序列、状态转移、进度百分比语义、日志结构、最终结果），v0.3 的比对面对齐了其中四项，漏的是**状态转移** ⇒ 现补为 `runStatus` 序列（同频轮询 `GET /instances/{id}` 采样），五项与 AC-15 逐项对上，不需要再解释为什么不测它 |
| **SUG-17** | **采纳**（行号族，与第 1 轮 SUG-3 同口径：结论全不变） | §9.1 上线顺序②、§17 SUG-8 行（`deploy-plugin.sh` 四组行号）；§5.2 `PLATFORM_IMAGE_TAG` 行、§14.4 表「框架侧唯一新增」行、§14.4.2 表首行、§17 SUG-4 行（`deploy.vue` 行号 + 限定） | 本次逐处 `grep -n` 复跑实值：`FRONTEND_DIR`/`PLUGIN_MODULE` `:27-28`（原 `:26-27`）、`PLUGIN_ID`/`JAR_NAME` `:30-31`（原 `:29-30`）、`--jar` 解析 `:44` / 分支体 `:52-54`（原 `:53-57`，构建确在 else 臂 `:56-68`）、卸载 `:110-116` / 覆盖 `:118-120` / 清理 `:122-130` / 加载 `:133`（原合写成 `:110-124`），`purgeTasks=false` 在 `:114` ✅；`deploy.vue` 回填 `:246-250` ✅（`:244-249` 会框进上面的删除循环，未采用）、**展开在 `:714`、`:713` 是 `isComposeVariableDeploy()` 守卫** ⇒ §5.2 那句「向导提交即带上它」加限定「**（compose 变量类部署下）**」，`docker`/`linuxgsm` 两类不带此键。§14.4.2「AC-14 正向路径判 400」的推导只在 compose 变量类部署下成立，与本表原结论一致 |
| **SUG-18** | **采纳** | §3.3 组 G、§5.4、§6.1 第三行、§6.3、§7.2 B-11、§14.6 表前引导句（**§14.6 字段表本身一字未动**） | 契约读者（@Tester / @FrontendDev）数出来会和文档不一致：六个**字段位**里有两位各含两个属性（`stepIndex`/`stepTotal`、`stepLabel`/`stepType`），**展开为 8 个 VO 属性**。全文统一写「六个字段位、8 个 VO 属性」。表本身的列法是对的（它按字段位组织，不是按属性数），故不改表 |

**第 2 轮定点确认新增两项（v0.3.2 落文，改法由 Leader 定死）**：

对照 ArchReviewer 的定点确认（评审对象 `design.md @ 528d471`，评论 `01a0bf2a`，结论 **PASS、条件解除**；九条核销 + 回归干净）新提出的 SUG-19 / SUG-20，与 Leader 的裁定与退回清单（评论 `01a0bf2b`：**取值分支与是否降级都由 Leader 定死，本文不自行扩面**）。两条都不翻判定、不新增编号。

| # | 处置 | 落点（改了哪几节） | 说明 / 定死后的口径 |
| --- | --- | --- | --- |
| **SUG-19** | **采纳，按 Leader 裁定取「两支都产行」这一支** | §14.12「改什么」行（**唯一归属节**）、§8.3「每步必得行」行的量级估算 | 核对为真：v0.3.1 只在 §14.6 `stepEvent` 行写了收尾取 `SUCCESS`/`FAILURE`，而行为归属节 §14.12 仍写「成功 → 记『交棒行』进 `HEALTH_CHECK`」，§3.2 时序又写「收尾起回容器 → 阶段完成行 → 交棒行」⇒ 文档内三套说法。**现按裁定统一为两支各产一条独立收尾行**：成功 → `SUCCESS` 收尾行 → 阶段完成行 → 交棒行；失败 → `FAILURE` + `level = ERROR` 收尾行、部署 `ERROR`、不进入 `HEALTH_CHECK`。**不取反向方案**（把 `stepEvent` 行收窄为只产 `FAILURE`）：成功路径的收尾耗时（§8.1 的 compose ≤1200 s + 5 s / lgsm-docker ≤1200 s + 8 s）在日志里就没有承载行，预算项无从核对——且 §14.6 `elapsedMs` 行的非空集合本就含 `SUCCESS`，收窄反而要与自己冲突。§8.3 那句是量级估算：阶段级常施行按新枚举为**四行**（进入 / 收尾 / 完成 / 交棒），3 步典型部署 12–15 → **13–16** 行，量级结论不变。**§3.2 时序与 §14.6 `stepEvent` 行一字未动（Leader 明令）**；顺手复查 §14.13.3 与 §6.2：前者只有「失败处置」一行、后者只列责任边界，**无「成功只记交棒行」残句**，故未动 |
| **SUG-20** | **采纳，取「补枚举」支，不取「把『定死』降为示例」** | §14.6 `stepId` 行（枚举六项 → **七项**） | 核对为真：本表 `elapsedMs` 行（Leader 评论表里的 `:673`，原文「仅 `SUCCESS`/`FAILURE` 与**阶段完成行**非空」）与 §3.2 时序都把阶段完成行当既存阶段级行使用，但它不在「定死」的六项里 ⇒ 「定死」会被实现者反推成「不在表里 ⇒ 它得有 `stepId`」，而一旦真给了 `stepId`，SUG-13 刚杀掉的 n/(n+1) 就以另一种形式请回来。**补进枚举**而不是降格：「定死」这个词的全部作用就是堵反推，降为「示例」等于拆掉这层保护。KPI-02 分母**不受影响也无需改**——规则 2 与 V-08 的排除靠的是 `stepId == null` 这个**判据本身**，不是靠那张列表枚举（评审与本设计同判）。**未动**：§14.6 规则 2 与 V-08 里那两处「进入 / 交棒 / 停实例 / BR-12 / 目录不合法 / 收尾」的六项写法——它们是判据后的说明性列举、且属九条已核销落点（SUG-13），按「不扩面」保留原样，已在回传评论里点名请 Leader 判是否同步。**〔v0.3.3：已授权补项〕**——Leader 裁定 `01a0bf39` 第二节第 2 条已批，两处六项括注补成七项，闭环见下「v0.3.3 一处同步」块 |

**v0.3.3 一处同步（Leader 裁定 `01a0bf39` 授权，边界写死）**：

对照 Leader 的 v0.3.2 通用门禁评论（`01a0bf39`）——其第二节第 2 条正是对 SUG-20 表末格「未动而点名交 Leader 的一处」的裁定：**两处六项括注补成七项、出 v0.3.3**，边界写死为「不得改判据、不得改分母口径、不得动其它任何一节」；其第二节第 1 条同时确认记账三件（版本升 / 本节逐条 / §18 一行）不算扩面、「以后照此办，不必再问」。

| # | 处置 | 落点（`4494bf0` 原号 → 本版号） | 说明 / 同步后的口径 |
| --- | --- | --- | --- |
| Leader 裁定 `01a0bf39` | **执行，纯枚举同步；不取「把两处降为脚注」「删 `stepId` 行多余项」等其它收口形态** | §10 V-08 判据括注（`:367` → `:369`）、§14.6 规则 2 括注（`:691` → `:693`） | 两处各补**阶段完成行**一项，与 §14.6 `stepId` 行 v0.3.2 已定死的七项枚举逐项对齐（进入 / 交棒 / 停实例 / BR-12 拦截 / 目录不合法说明 / 收尾 / 阶段完成）。V-08 处按 Leader 明令**照它自身的合并写法**落笔（该处把「BR-12 与目录不合法说明行」并作一项，故字面斜杠段数为 6、概念项为 7），补在「收尾行」之后；规则 2 处补在「收尾」之后。**判据与分母口径一字未动**：V-08 仍是「统计对象 = `stepId != null` 的行 / 分母 = 非空 `stepId` 的去重计数 / 每个非空 `stepId` 恰一 `START` + 恰一终态且终态 `elapsedMs != null` / 比例 = 100% / 脚本内不得出现 `message` 匹配」，规则 2 仍是同一式——两处的排除靠的一直是 `stepId == null` 这个判据本身，不是括注的项数（与 SUG-20 表末的判定同一句话），本版只把排除清单的第七类成员补齐。词级 diff 显示这两行的唯一变化就是插入的「 / **阶段完成行**」（见本节下表第三行）。**采纳 Leader 的三条理由**照录：① 同一集合在文内以两种项数出现，是 SUG-19 的同族问题；② `:367`/`:691` 虽属 SUG-13 的核销落点，上轮「不得动」指的是不得扰动九条的**判据与口径**，同一集合的一致性同步不在其内且本轮明确授权、责任在 Leader；③ 两处括注不含「定死」字样、同句已给权威判据，失败方向本属良性，但既然 `:673` 已定死七项，留两份六项就是白送的歧义 |

**第 3 轮定点确认新增两项（v0.3.4 落文：SUG-22 已闭合；SUG-21 登记为待裁定，本轮不落判定）**：

对照 ArchReviewer 定点确认（结论 PASS、九条核销）新提出的 SUG-21 / SUG-22，与 Leader 在 MERC-6 具名登记的 G1 收口两条（零轮次记账）。本批为「下次自然触手」的那一次升版：SUG-22 与 SUG-20 表末格标记两项按登记口径落文；**SUG-21 经核对与本文 v0.3.3 既有内容相撞，按派工边界「有冲突先报、不硬改」不落入判定文本，登记如下交 Leader / @Architect 一句话裁定**。

| # | 处置 | 落点 | 说明 / 核对到的事实 |
| --- | --- | --- | --- |
| **SUG-20 表末格**（G1-a ①） | **闭合，补标记** | §17「第 2 轮定点确认新增两项」表 SUG-20 行末格 | 该格原以「已在回传评论里点名请 Leader 判是否同步」收尾、闭环散在下块 ⇒ 补 `〔v0.3.3：已授权补项〕` 并指向「v0.3.3 一处同步」块，读者在该格当场就能看到它已授权、已落文 |
| **SUG-22**（G1-a ②） | **闭合，纯排版** | §17 自查块顺序 | 原顺序为 `v0.3.3 → v0.3 → v0.3.1 → v0.3.2`（本版把「v0.3.3 一处同步 / v0.3.3 自查」插在 SUG-20 表之后所致）⇒ 将「v0.3.3 自查」整块移至「v0.3.2 自查」之后，自查序列恢复单调 `v0.3 → v0.3.1 → v0.3.2 → v0.3.3 → v0.3.4`；本版自查块再接在其后。**无判定影响**：九行内容逐字未改，只换位置 |
| **SUG-21**（G1-a ③） | **不硬改，登记为待裁定**（本轮 §14.6 `stage` / `stepId` 行与 §10 V-08 判据**一字未动**） | 本节（判定文本零改动） | 缺口核对为真：§14.6 `:673` 的七项枚举里，**BR-12 拦截行与目录不合法说明行确实没钉 `stage` 取值**，连带 §10 V-08 `:369` 首判据「每条 `stage == "EXTENSION"`」的取值域有两种读法。**但登记时给出的两支改法都与 v0.3.3 既有内容相撞**，且相撞方向两支不同：<br>① 取「两行都 = `EXTENSION`」一支 ⇒ 与 §3.2 `:81`「EXTENSION 段仅当解析出步骤集 ≥1 才存在，否则**本段整体不存在**」**及 §16.4 `:1045` 的 `INVALID` 态**冲突：目录不合法 + 无键那一路根本不进扩展阶段，给它的说明行钉 `EXTENSION` 会反向触发 §14.11 / F-02 的阶段带 latch（`logs.some(l => l.stage === 'EXTENSION')`），等于让一次「未进入扩展阶段」的部署长出阶段带——正是 AC-15 / AC-24 ③ 回归核对要禁的形态。<br>② 取「两行都 = 其实际发生的既有阶段」一支 ⇒ 与 **BR-12 拦截行的实物**冲突：ui-spec §4.2 的 P / Q 两行明写「阶段带 **渲染**」，对应屏 LE / LF 的日志序列含「进入部署扩展阶段」且在阶段内终止（ui-spec §6.2 规则① 称其为「**扩展阶段入口**的 BR-12 拦截」）⇒ 该行的实际发生阶段就是 `EXTENSION`，改称既有阶段会让 latch 与 AC-16 的判读反向落空。<br>**核对用的实物证据（对 `ui-spec/index.html @ 62b49a9` 逐屏机械抽取，命令随回传）**：`LE` `LF` = 带 √ + 进入行 √；`LG` = 带 × + 进入行 ×。<br>**因此两行必须分别钉、不能共用一支**：BR-12 拦截行 ⇒ `stage = "EXTENSION"`（阶段以「入口判定不合法」短暂成立，latch 与实物一致）；目录不合法说明行 ⇒ `stage = 其实际发生所在的既有阶段`（本期该点位于 `DEPLOY` 完成行与 `HEALTH_CHECK` 之间 ⇒ `"DEPLOY"`；实现若把读目录判定前移，则为其前移后实际所在的既有阶段，判据不变），并在同一句里把 V-08 首判据的取值域写明为「扩展阶段内的行」而非「logs 全部行」，使脚本不在这两行上误报。**该拆分是否采纳、以及 `INVALID` 说明行的正钉值，交 Leader / @Architect 裁定；裁定后单独一批落文 §14.6 与 §10 V-08。**<br>**影响面复核（与登记一致）**：KPI-02 上为 **0**——两行 `stepId == null`，无论 `stage` 取何值都不进分母；风险只在 V-08 脚本的可执行歧义与 AC-20 / BR-12 的验收归因。**连带发现（一并交裁定）**：LE / LF 在零步骤的情况下渲染阶段带，而 §3.2 `:81` 把 EXTENSION 段的存在条件写成「解析出步骤集 ≥1」——这两处本身也有一格张力（入口拦截算不算「进入过该阶段」），SUG-21 落文时应顺手把 `:81` 的存在条件与「入口判定即终止」这一支对齐，避免同一问题在 §3.2 与 §14.6 两处各修一半。 |

**v0.3 自查（防「改一处漏三处」，逐条可复跑）**：

| 自查项 | 方法 | 结果 |
| --- | --- | --- |
| v0.2 的七处旧口径不再作为**现行结论**出现 | grep `五个可选字段` / `5 个可选字段` / `三条件门控` / `禁止清单含` / `新增 4 类型` / `三个适配器不改动` / `原样继承` | 全文命中 **6 行**，逐行核过：4 处是**带引号的订正说明**（§4 负向清单订正段、§14.1 表 BR-09 行、§14.13.2 表「§4 负向清单同步」行、§14.14 首段引 Javadoc 原文，均标「作废 / 订正 / 分两半」），2 处在本节（REV-1 行与本行）。**除这 6 处外命中 0**，无一处仍作断言 |
| 新增落点不是只在回应表里承诺 | grep `V-21`…`V-29`、`B-16`、`组 L`、`RISK-D12`…`D15`、`D-P10`…`D-P17`、`§14.13`…`§14.15` | 每个编号出现次数 ≥ 2（在 §10/§12/§13.1 定义 + 正文别处引用），无孤立编号；RISK-D15 恰为 2（定义 + 本表） |
| 引用节号存在且无悬空 | 逐条比对本文内部节号 | RISK-09 的收口节是 **§14.15**；写作过程中曾把它误编为 §14.16，已全部订正——`§14.16` / `14.16` 在正文中命中 **0**，唯一残留是**本行**（在此说明该订正过程，故不复现原串） |
| 禁编造复扫（与 v0.2 同口径） | 按 v0.2 那四个样本串各扫一遍（公网 URL 字面量、dnf-tw 的真实版号片段、其基础镜像发行版片段、其镜像仓库名与 `镜像名:` 形式）；**样本串本身不写进本文**，以免污染复扫 | 命中数 **0**。本轮新增的 tag 相关文本只有两类：格式校验的**字符集**（§14.4.1 R4）与 compose 占位符字面量 `${PLATFORM_IMAGE_TAG`——都是机制，不是任何真实镜像、版号或 URL |
| 边界 | 决策 1–9 / `prd.md` / 三条硬判定结论 | 未改；`git diff --name-only origin/main <新 commit>` 仅命中本文件（`prd.md` 命中 0、功能代码 0） |

**v0.3.1 自查（九条逐条命中 + 防「改一处漏三处」，全部可复跑）**：

| 自查项 | 方法 | 结果 |
| --- | --- | --- |
| 九条各有落点，不是只在 §17 承诺 | 逐条对照 Leader 派单（评论 `01a0befc`）的处置列：SUG-10→组 G/§4/B-11/§6.1/V-21/§14.6 首段；SUG-11→§16.2 行 + 注释 + B-01；SUG-12→§14.14 两处 + §13.1 D-P16 + §13.2 ②；SUG-13→V-08 + §14.6 规则 2 与 `stepId`/`stepEvent` 行 + §14.13.3 交叉引用；SUG-14→R1 + N1 + B-05；SUG-15→组 L + B-16 + §14.13.3 前置说明；SUG-16→D-P12 + V-10/T-04/AC-15 行；SUG-17→§9.1 + §5.2 + §14.4 + §14.4.2；SUG-18→组 G + §5.4 + §6.1 + §6.3 + B-11 + §14.6 引导句 | 九条**全部有正文落点**（非仅回应表），上表每格均可 grep 定位 |
| 被订正掉的旧串不再作**现行断言** | grep `backend/api/.../vo/LogEntryVO` / `876-897` / `deploy-config?deployType=` / `:26-27` / `:29-30` / `:53-57` / `:110-124` / `同一个 map 实例` / `零转换` / `比对面…四项` / `新增 6 个` / `生命周期一致` | 全部命中数落在**带引号的订正说明**里（§14.6 首段、§17 各行、§18 v0.3 行的〔…〕标记），**作断言使用 0 处**；`deploy-config?deployType=` / `新增 6 个` 已归零 |
| 九条新落点的代码事实自己复跑过（不采信转述） | `ls backend/api/.../vo/`、`grep -n` 定位 `class LogEntryVO` 与 `class DeployProgressVO`（`InstanceController.java`）、`sed -n '497,506p'`、`grep -rn "deploy-config" GameMetadataController.java`、逐处 `grep -n` 扫 `deploy-plugin.sh` / `deploy.vue` / `PatchInstallExecutor.java` / `InstanceServiceImpl.java` / 两个适配器、读 PRD `270f9d0` 行 326/441、**本设计自己跑 `javac 17.0.16`** | 与评审所给值一致；**三处本文改用更精确的行**：`pushExtracted`/`pushSingleFile`/`pushIntoContainer` 声明在 `:627`/`:645`/`:658`（评审引 `:629-643`/`:647-654`/`:660-676`，指同一段代码但含注释偏移）、`GET /{id}/logs` 的 `@GetMapping` 在 `:372`、lgsm `healthCheck` 声明在 `:430`。行号族按实跑值写，不按转述写 |
| 未翻任何判定 / 未动已在册口径 | 逐条比 §14.0 结论速览十四行、§14.0.1 状态列、三条硬判定结论、OP-05 / AC-22 / D-P01…D-P17 的**结论与状态** | 结论与状态**一字未改**；本版只改依据文本、行号、模块归属、判据措辞与两处枚举（KPI-02 分母、AC-15 比对面第五项）。D-P12 是**清单补全**（加一处回写对象），不是改判 |
| 禁编造复扫（与 v0.2/v0.3 同口径） | 公网 URL 字面量、dnf-tw 真实版号片段、其基础镜像发行版片段、其镜像仓库名形式，各扫一遍；样本串不写进本文 | 命中 **0**（`http(s)://` 全文 0；三项样本 0）。本版新增文本里的具体值全部是**仓内既有代码的行号**与 javac 报错原文，无一项来自臆测 |
| 边界 | 改动范围 | `git diff --name-only fd2c136 HEAD` 仅命中本文件；未碰 `prd.md`、未碰功能代码、未碰 ui-spec；新 commit 续在 `fd2c136` 之后，**不覆盖、不 force-push** |

**v0.3.2 自查（两条落文 + 防「改一处漏三处」，全部可复跑）**：

| 自查项 | 方法 | 结果 |
| --- | --- | --- |
| 两条各命中其落点，且**只**命中裁定点名的那几处 | 逐处读文 + `git diff -U0 528d471 HEAD` 的 hunk 清单（行号一律给「`528d471` 原号 → 本版号」） | hunk 共 **9 处**：元数据表 `:7-8`、版本链 `:15`、本版性质注 `:19→:20`（纯插入）、§8.3 `:295→:297`、§14.6 `stepId` 行 `:669→:671`、§14.12 `:796→:798`、§17 新增两项块 `:1115→:1118+`（纯插入）、§17 v0.3.2 自查块 `:1136→:1148+`（纯插入）、§18 v0.3.1 行的〔…〕标记 + v0.3.2 行 `:1144→:1168-1169`。**裁定点名的三处之外，一节正文未碰**（其余全是记账） |
| 三条旧字面串不再作现行断言 | **复跑方法不硬编码样本串**（写进本文即污染复扫，沿用 v0.3 起该口径）：`git diff 528d471 HEAD` 取被删行的三条旧表述字面串，逐条 grep 工作版 ⇒ 命中须为 **0** | 命中 **0**（旧表述只存在于本节以「旧表述」指代，未逐字复现）|
| **Leader 明令不动的两处确实未动** | 看第一条 hunk 清单 | §3.2 时序（`528d471:78` = 本版 `:80`）与 §14.6 `stepEvent` 行（`:672` = 本版 `:674`）**不在任何 hunk 内** ⇒ 一字未动 |
| 未引入新设计编号（机器核对） | 对 `528d471` 与工作版各抽 `V-`/`D-P`/`RISK-D`/`B-`/`AC-`/`T-`/`F-`/`BR-`/`FR-`/`KPI-`/`OP-`/`D-N`/`N`/`G-` 的**编号集合做对称差** | **全部为空集** ⇒ §11 追溯覆盖集不变、无新悬空。唯一新增的两个标识符是 `SUG-19`/`SUG-20` 本身（评审项编号，只出现在 §17 的记账块与本版性质注里），不是设计侧编号 |
| 未翻任何判定 / 未动已在册口径 | `git diff -U0 528d471 HEAD` 比对 §14.0（`:482` 起）与 §14.0.1（`:501` 起）所在行段 | 该行段**零 hunk** ⇒ 十四行的结论与状态一字未动；三条硬判定结论、九条已核销落点、`prd.md` 未碰 |
| 表格结构 | 按 code-span 先剥离后统计列数的 lint 复跑 | 62 张表、孤立行 **0**、列数不一致 **0** |
| 禁编造复扫（与 v0.2/v0.3/v0.3.1 同口径） | 公网 URL 字面量、dnf-tw 真实版号片段、其基础镜像发行版片段、其镜像仓库名形式各扫一遍；样本串不写进本文 | 命中 **0**（`http(s)://` 全文 0；三项样本 0）。本版新增文本里的具体值只有既有节号、既有字段取值与 §8.1 已登记的预算数字 |
| 边界 | 改动范围 | `git diff --name-only 528d471 HEAD` 仅命中本文件；未碰 `prd.md`、未碰 `docs/ui/MERC-3/*`、未碰功能代码；新 commit 续在 `528d471` 之后，**不覆盖、不 force-push** |

**v0.3.3 自查（一处同步 + 防「改一处漏三处」，全部可复跑）**：

| 自查项 | 方法 | 结果 |
| --- | --- | --- |
| **本次核对对象本身**：同一集合在文内的每处列举项数一致 | 取 `stepId == null` 阶段级行的全部列举——§14.6 `stepId` 行 `:673`、§14.6 规则 2 括注 `:693`、§10 V-08 括注 `:369`——逐处按 `/` 切段，并把「BR-12 与目录不合法说明行」这类合并段还原为概念项，与 `stepId` 行「定死」的七项作集合比对；比对脚本对 `4494bf0` 与工作版各跑一次 | 工作版**三处概念项均为 7、集合相同**（进入 / 交棒 / 停实例 / BR-12 / 目录不合法 / 收尾 / 阶段完成）；`4494bf0` 实测 = `stepId` 行 **7 段 7 项**、规则 2 **6 段 6 项**、V-08 **5 段 6 项** ⇒ 同一集合的两种项数已消除。**字面段数按各处合并写法本就不同**（工作版：V-08 = 6 段 / 7 项，它把 BR-12 与目录不合法并作一段；`stepId` 行与规则 2 = 7 段 / 7 项）——这是措辞密度差异、不是项数差异，故本版不「顺手统一」它们的写法（统一会改到 SUG-13 核销落点的既有行文，超出裁定边界） |
| 该集合的其它同族计数不与本版打架 | 读 §8.3「每步必得行」行的「阶段级常施行**四行**（进入 / 收尾 / 完成 / 交棒）」 | **不是同一集合，无矛盾**：§8.3 数的是**每次有扩展步骤的部署必产**的常施行子集；本版的七项里含三类条件行（停实例行仅在有步骤时产、BR-12 拦截行与目录不合法说明行仅在不合法时产）⇒ 四行 ⊂ 七项。§8.3 一字未动（不在本版任何 hunk 内），此处只说明「为什么四行和七项同时成立」，防被读成第四种项数 |
| 判据与分母口径未动（Leader 边界头两条） | `git diff --word-diff=plain -U0 4494bf0 HEAD` 取 `:369` 与 `:693` 两行的词级差，逐词读 | 两行的**唯一**词级变化 = 插入的「 / **阶段完成行**」；「统计对象 = `stepId != null` 的行」「分母 = 非空 `stepId` 的去重计数」「恰一 `START` + 恰一终态且终态 `elapsedMs != null`」「比例 = 100%」「脚本内不得出现 `message` 匹配」逐字保留 ⇒ SUG-13 建立的可满足性论证不受影响 |
| 除点名的两处外，一节正文未碰（Leader 边界第三条） | **不用「hunk 起始行求交」那种验法**（UI 线已被证明它会漏判新增区的落点），改用两条只依赖 diff 本身的硬核对：① `git diff -U0 4494bf0 HEAD` 取全部**被删除的既有行**；② `git diff --numstat` 取增删数 | ① 被改动的既有行全文 **恰 5 行**，逐行点名：元数据「版本」行、元数据「状态」行、版本链行（`:15`）、§10 V-08 行（`:369`）、§14.6 规则 2 行（`:693`）——**前两行是版本与状态、第三行是版本链，均属裁定第 1 条批准的记账；落在正文的只有 V-08 与规则 2 两行**，正是裁定点名的两处。② `numstat` = **+29 / −5** ⇒ 其余 24 行全是纯插入（本版性质注 2 行 + §17 记账与自查两块 21 行 + §18 本版一行），**插入不改写任何既有文字**。合起来即：§14.6 `stepId` 行 `:673`、§14.0 / §14.0.1 十四行、§17 三张历史表（含 SUG-20 表末格那句「未动而点名交 Leader 的一处」）、§18 v0.1…v0.3.2 各行**一字未动**，按「历史行不改写、订正只在新增块记账」的既有口径保留 |
| 未引入新设计编号（机器核对） | 对 `4494bf0` 与工作版各抽 `V-`/`D-P`/`RISK-D`/`B-`/`AC-`/`T-`/`F-`/`BR-`/`FR-`/`KPI-`/`OP-`/`D-N`/`N`/`G-` 的编号集合做对称差 | **全部为空集** ⇒ §11 追溯覆盖集不变、无新悬空编号。本版新增的标识符只有引用到的评论号 `01a0bf39`（Leader 裁定编号，非设计侧编号），不进入上述抽样 |
| 表格结构 | 验法写明以免绝对数被当判据：**连续以竖线字符开头的行**成一表；每表第 2 行必须是分隔行；列数 = 先剥离 code-span 再按竖线字符切分计数；同一验法对 `4494bf0` 与工作版各跑一次 | 表数 `4494bf0` = **63** → 工作版 = **65**（本版 +2，即本节记账表与自查表）、**列数不一致 0 张**、**缺分隔行 0 张**。（v0.3.2 自查记的「62 张」未附验法，与本行不同源——差异不构成本版问题，但绝对数以本行写明的验法为准） |
| 禁编造复扫（与 v0.2/v0.3/v0.3.1/v0.3.2 同口径） | 公网 URL 字面量、dnf-tw 真实版号片段、其基础镜像发行版片段、其镜像仓库名形式各扫一遍；样本串不写进本文 | 命中 **0**（`http(s)://` 全文 0；三项样本 0）。本版新增文本里的具体值全部是既有节号、既有行号与评论编号，无一项来自臆测 |
| 边界 | 改动范围 | `git diff --name-only 4494bf0 HEAD` 仅命中本文件；未碰 `prd.md`、未碰 `docs/ui/MERC-3/*`、未碰功能代码；新 commit 续在 `4494bf0` 之后，**不覆盖、不 force-push** |


**v0.3.4 自查（G1 零轮次记账批，全部可复跑）**：

| 自查项 | 方法 | 结果 |
| --- | --- | --- |
| **SUG-21 未落入判定文本**（派工硬约束「有冲突先报、不硬改」） | 对 `ee65e66` 与工作版逐字比对**七处**判定锚点：§14.6 `stage` 行、§14.6 `stepId` 行、§14.6 规则 2（`:693`）、§10 V-08 判据行、§3.2 `:81` 存在条件行、§16.4 `INVALID` 行、§14.11 小节标题 | **七处一字未变**（七处各自唯一命中且新旧逐字相等）⇒ 本批对判定面零改动，SUG-21 只以「待裁定」形态登记在 §17。**注**：本批新写的 §17 / §18 文字里引用了 `:81` 的原句，按「含该短语」粗筛会多命中一处，故锚点一律按**行首形态**取，不按子串 |
| 自查块顺序恢复单调（SUG-22 的闭合判据） | 抽 §17 内全部以「**v…自查（**」开头的标题行，按出现顺序列出 | `v0.3 → v0.3.1 → v0.3.2 → v0.3.3 → v0.3.4`，本批之后不再有乱序 |
| 表格结构 | 按 code-span 先剥离后统计列数的 lint 复跑（验法与 v0.3.3 同一套，绝对数以本版实测为准） | **67 张表**、非分隔第二行 **0**、列数不一致 **0**（v0.3.3 为 65 张，本批新增 §17 两张：SUG-21/22 登记表 + 本自查表） |
| 未引入新设计编号（机器核对） | 对 `ee65e66` 与工作版各抽 `V-`/`D-P`/`RISK-D`/`B-`/`AC-`/`T-`/`F-`/`BR-`/`FR-`/`KPI-`/`OP-`/`D-N`/`N`/`G-`/`SUG-`/`REV-` 的编号集合做对称差 | 对称差为**空**（双向均无新增）⇒ 本批只在既有序列上续号 SUG-21/22，未另造编号空间 |
| 禁编造复扫（与 v0.2/v0.3/v0.3.1/v0.3.2/v0.3.3 同口径） | 公网 URL 字面量、`\d+\.\d+\.\d+` 形态串集合、dnf-tw 真实版号片段各扫一遍，并与 v0.3.3 的集合做差 | URL **0**；`x.y.z` 集合与 v0.3.3 **完全相同、新增 0**（命中仍全为节号、jar 名、`javac 17.0.16`、`docker-java 3.3.4` 四类既有项） |
| **平行输入锚点复核（G1-b 的做法要求：换 hash 之外必须复核该行携带的交叉引用）** | 三步：① 把「其 §10-B 三项界面侧前提由本文 §14.4 / §14.9 / §14.11 收口」逐节对读归属（§14.9 ↔ B1 `level → 视觉映射`、§14.4 ↔ B2 `imageTag` 落位、§14.11 ↔ B3 阶段带 / 步骤点驱动源）；② 回读 `ui-spec.md @ 62b49a9` §10-B 抬头的 v0.4 状态注与 §13.4「不得动」行；③ 判该句在新锚点下是否仍为真 | 三节归属**复核为真、不改述**；**但该句若原样搬到老锚点上会变成不准确的断言**：UI 侧至 v0.6 仍明写「不并收，只登记……判门权在 Leader……未定稿前不得自行引入」（v0.6 的 §13.4 原样保留了 B1/B2/B3 的未定稿状态）。⇒ 元数据行按此改写为「设计侧已给结论、UI 侧待 Leader 判门后一次性并收」，而不是只把 `f6312ef` 换成新 hash。**这正是 G1-b 点名要求的那一步。** |
| 边界 | `git diff --name-only ee65e66 HEAD` | 仅命中本文件；未碰 `prd.md`、未碰 `docs/ui/MERC-3/*`、未碰功能代码；新 commit 续在 `ee65e66` 之后，不覆盖、不 force-push |

## 18. 修订记录

| 版本 | 日期 | 作者 | 变更摘要 |
| --- | --- | --- | --- |
| v0.1 | 2026-09-20 | Architect | 骨架先行：章节基线 + §14.2 八行待判定清单 |
| v0.2 | 2026-09-20 | Architect | **全文定稿**（对应 Leader 派单 `01a0bd7f` 的抗中断要求：分次落盘，每次一节）。<br>**§14 待改依赖由八行扩到十一条并逐行给结论**：① 桥接 = SDK 新增 `installSync` 直调执行器（并修正 PRD 对该依赖的前提——执行器本就阻塞）；② `includePattern` 取「同链路内执行」自动生效、`headers` 结构性不提供；③ 容器脚本通道本期不改（因⑤）；④ **硬判定① 给出 `imageTag` 机制**（保留变量 + 既有 `.env` 链，注入点在 `buildDeployConfig`）⇒ **AC-14 转可验收**；⑤ **硬判定② 判 `position = container` 不合法** ⇒ **AC-13 移出本期**（含决策 7 的条件性收缩）+ 停实例语义与停失败致命处置定稿；⑥ **硬判定③ 日志呈现契约三项定稿**（`stepId`/`stepEvent`/`elapsedMs` 毫秒 + 归组判据）⇒ KPI-02 / AC-03 / AC-16 脱离「不可测」；⑦ 进度条件分配 `[80,84]`（无步骤游戏零改动）；⑧ BR-16 取合并式写入（AC-27 四项无需拒绝分支即通过）；⑨ `level → 视觉映射` 前端归一化（含 `warn ≠ warning` 的补充事实）；⑩ 新发现 AC-22 的界面入口不存在（A/B 案交 Leader）；⑪ **新发现阻断级冲突**：FR-11 停实例 vs `HEALTH_CHECK` 探测运行态 ⇒ 判「扩展阶段收尾按依赖顺序起回容器」，顺带收口 `compose start` 不处理 `depends_on` 的隐蔽失效。<br>**§15 OP-04 拍板**：`timeoutMs` 缺省 600s、合法区间 `[1s, 30min]`、越界即声明不合法、超时不等于远端进程已终止。<br>**§16 声明模型与 GET 侧契约**：登记新代码事实「`getDeployConfigs()` 的读时合并只作用于 VO，不作用于 `buildDeployConfig`」⇒ 版本目录走同一扩展点的 `getDeployVersions` / `getDeployExtensionSteps`，唯一读者 `DeployVersionCatalogService` 四态（`ABSENT/EMPTY/INVALID/AVAILABLE`），并给出对 FR-22 措辞的偏离与回写建议。<br>**§3–§13**：建议改动 11 组、受影响组件与负向清单、零数据库变更判定、对外/SDK/日志三层契约、按角色的实现步骤（B-01…B-15 / F-01…F-05 / T-01…T-04）、非功能（耗时预算、输出截断、脚本落文件执行的安全形状）、迁移与回滚、验证计划 V-01…V-20、AC-01…AC-27 全量追溯、RISK-D01…D11、待回写清单 D-P01…D-P09。<br>**未改动**：已确认决策 1–9 全部保持；未改 `prd.md`；未新增任何 dnf-tw 真实版号 / URL / 目标路径（全文 `http(s)://` 字面量命中数 0）。 |
| v0.3 | 2026-09-20 | Architect | **并入架构评审第 1 轮（评论 `01a0beae`：REV-1…REV-7 阻断 + SUG-1…SUG-9）与 Leader 门禁裁定（评论 `01a0beaf`：REV-1 取①、REV-3 按 FR-11 字面收口）**；§17 逐条填写，不采纳项给理由。新 commit 续在 `8a6e2c2` 之后，不覆盖、不 force-push。<br>**七项实质改动**（余为同步）：<br>**① 每主机互斥（REV-1，v0.2 属假自述）**：核对 `PatchInstallExecutor:35` Javadoc「同主机互斥由任务中心 `scopeKey=hostId` 承担」与 `:56` 只有 `globalSemaphore` ⇒ 绕开任务中心即丢掉 BR-09 互斥；本期在 `installSync` 内复用**同一个** `TaskMutexManager` 承**同一个**键 `PATCH_INSTALL:<hostId>`（2s 轮询 / 600s 预算 / `finally` 释放 / 锁粒度 = 单次 `execute()`，与 `/tmp/patch_install_<ts>`、`/tmp/patch_push/<file>` 两处共享路径的生命周期一致**〔该「生命周期一致」的依据由 v0.3.1 / SUG-12 收窄为「同一承键窗口内完成写后读」——`patch_push` 无清理〕**），不引入任务中心故 D-N05 成立。落点 §14.14、§14.1 改判、§8.2 重写、§6.2、§7.2 B-04、§8.1、V-26、RISK-D14、D-P16；③（临时路径命名空间）按裁定不授权，登记后续增量（§13.2）。<br>**② 收尾调用面（REV-2）**：`DeployAdapter` +1 **default 方法** `ensureRunningForExtension`（默认抛异常，随既有 `stopServer:185-187` 同形；**不采**评审的 `AbstractDeployAdapter` 空实现——返回 `true` = 静默假装已起回），compose / lgsm-docker 各 +1 覆写；新增 §3.3 组 L 与 B-16，三适配器移出 §4 负向清单并写明订正理由，风险列标「高（接口面扩大）」，「不改变无扩展部署行为」写进 V-10/AC-15 与 V-27；RISK-D13 登记三层封套与三条红线。<br>**③ 适用范围（REV-3）**：确认「停实例 → `HEALTH_CHECK` 必挂」对**三类**容器适配器同时成立（`DockerAdapter:274-277`、`LinuxGsmDockerAdapter:446-468`）；支持集合钉为 {`docker-compose`, `linuxgsm-docker`} 并**逐类**给命令与就绪判定（compose：`up -d`+5s+`ps` 认 `running`/`Up`；lgsm-docker：`up -d`+8s+`ps -q` 后**逐个**探 `.State.Running`，**禁止**复用 private `ensureContainerRunning`——其停止分支走 `compose start`（`:970-973`）且对第一个容器即 `return`（`:979`））；集合外（含 plain `docker`）带步骤或 `imageTag` 的声明 = 不合法（§16.3 N5 → BR-12），改判窗口经成本核对后放弃；D-P15 回写 FR-11 / AC-04 适用范围，T-05 + V-27 逐类承载。<br>**④ SDK 门面（REV-4）**：补 `DeployExtensionStepDeclaration` 定义体 = `sealed interface` + 两个既有 record 实现（带 `kind()`、`StepKind` enum）⇒ 目录条目 `patches ++ scripts` **零转换**即得有序混合清单〔v0.3.1 / SUG-11：结论成立但需显式类型见证，措辞已改为「零包装、零适配层、零字段复制」〕；不取「单 record + 判别位」（会把 §8.2/§8.3 两套必填推到运行期）；类型计数 4 → **7**，§3.3 组 A / §4 / B-01 同步，B-01 判据改为「`Stream.concat` 可赋给该上界」**〔该判据的字面写法编译不过；v0.3.1 / SUG-11 改述为「加显式类型见证后可赋给该上界」〕**。<br>**⑤ 判定通道（REV-5）**：`imageTag` 蕴含规则绑死「`game_metadata` 表快照、与 `buildDeployConfig` 同一 map」**〔v0.3.1 / SUG-14：「同一个 map 实例」不可强制，改述为「同一数据源与同一取值路径」〕**（R1），新增「经 `getDeployConfigs()` 声明的 `variables`/`composeTemplate` 不参与本机制」（R2，与 RISK-D07 同缺陷）与「表侧模板必含 `${PLATFORM_IMAGE_TAG` 字面量」（R3），V-02 补两条反例。<br>**⑥ 可验收性（REV-6）**：契约补**第六字段 `exitCode`** 与规则 4（`exitCode≠0 ⇔ FAILURE`）/规则 5（`ROLLBACK` 记录位），`stdout`/`stderr` 明写「仅进 `message`、不参与任何机械判据」（承载位分工表）；V 表补 **V-21（AC-01 正向）/ V-22（AC-12）/ V-23（AC-16 界面）/ V-24（AC-18 含 16.2 ① 代码计算支）/ V-25（KPI-04：`mvn test` + `npm run test:run` + `npm run e2e`，分母 = 基线清单不减）**，另加 V-26（互斥）/ V-27（逐类收口与集合外）/ V-28（retry 全量重放）/ V-29（注入点收敛）；§11 AC-01/12/16/18 四行落点同步。<br>**⑦ 上游一致性（REV-7 四条 + 另加项）**：RISK-09 收口于 **§14.15**（retry-deploy = `uninstall`（compose 含 `rm -rf workDir`，`:555-573`）+ 全量重部署 ⇒ 补丁必然重放；**BR-14 的「保留」限当次 attempt**；`configInfo` 半边核对为安全 `:329`/`:349`）+ RISK-D12 + V-28 + D-P10；D-P11（§11.1 并集计数与第五行标题随 D-P01/D-P04 一并回写）；D-P12（§8.5/AC-03/AC-16 的「契约未登记记不可测」句式出表）；D-P13（AC-14 载体扩到桩插件 + 桩游戏元数据，AC-26 ② 扩到「`./games` 不留桩 yml」）；**§14.0.1 十四行逐行状态收口 + D-P14**；D-P17（契约读者加 AC-12）。<br>**九项建议**：SUG-1 删 §3.3 组 F 残留的被否决旧方案；SUG-2 换准确依据并显式登记 `compose run --rm` 为已否第三条路（§14.5.1，三条独立依据）；SUG-3 三处订正（模板上传有两处结构性后处理、`getStatus(instanceId, config)` 签名、`:216→:217` 与「顶层 `progress` 值」改述）；SUG-4 承认 `PLATFORM_IMAGE_TAG` 是真实 `configInfo` 键 + 值改由平台写（**两级门控**）+ R4 格式校验，并**自查发现并修掉 v0.2 一处自相矛盾**（该键进 BR-07 提交期清单会把 AC-14 正向路径判 400 → §14.4.2、D-P05 改写）；SUG-5 §14.4.3 登记注入点副作用 +「无 `deployVersion` 不读目录」+ 明写不加缓存的理由；SUG-6 computed 参数化 + `max(1,round(ms/1000))` 秒口径；SUG-7 `ROLLBACK` 记录位判据（**不改执行器回调接口**，回滚成功与否由 V-05 文件比对判，RISK-D15 登记诚实限制）；SUG-8 点名 `--jar` + env 热部署路线（不改 l4d2 在用的脚本）；SUG-9 V-10/T-04 明确 AC-15 比对面不含 CSS class 与图标名。<br>**新增编号**：§14.13…§14.15、§14.4.1…§14.4.3、§14.5.1、§14.0.1、组 L、B-16、T-05、V-21…V-29、RISK-D12…D15、D-P10…D-P17；§14 由十一条扩到十四条。<br>**未改动**：已确认决策 1–9；`prd.md`；三条硬判定的**结论**（仅按 SUG-2/3 更新依据文本与行号）；禁编造复扫命中 0。 |
| v0.3.1 | 2026-09-20 | Architect | **并入架构评审第 2 轮（评论 `01a0bef8`，结论 PASS）新发现的九条 SUG-10…SUG-18**，按 Leader 裁定（评论 `01a0befc`）**一轮全部落净**，不留给 PRD v0.6 批；新 commit 续在 `fd2c136` 之后，不覆盖、不 force-push。九条**全是「落点/依据文本与代码不符」级，无一条翻判定**：<br>**① SUG-10 落点纠错**：组 G 模块列改 `backend/core` **单模块**（仓内无 `backend/api/.../vo/LogEntryVO.java`，它是 `InstanceController` 的嵌套类 `:889-896`，`DeployProgressVO` 在 `:875-884`，真正的映射在 `:497-503`——v0.3 引的 `:876-897` 是 VO 声明段）；§6.1 首行与 V-21 的 URI 由 `?deployType=` 改为路径段 `/{id}/deploy-config/{deployType}`（`GameMetadataController:85`）；§6.1 补一段：`GET /instances/{id}/logs` 把 `LogEntry` 摊平成文本（`:392-396`），**扩展字段在该通道不结构化**。<br>**② SUG-11 判据纠错**：§16.2 拼接写法改 `Stream.<DeployExtensionStepDeclaration>concat(...)`（显式类型见证），B-01 判据改述为「**加显式类型见证后**可赋给该上界」，并写明推导规则（`concat` 的两个 `? extends T` 独立推导取交类型 + 泛型不变性）；本设计第三次跑 javac 17.0.16 复现原写法编译失败。**REV-4 结论不变**。<br>**③ SUG-12 依据收窄**：`/tmp/patch_push/`（`:637`/`:649`）**全仓无清理**（`finally` 的 `rm -rf` 在 `:227` 只删 `hostTmpDir`，`:141`）⇒ §14.14「锁粒度」的依据由「与两处共享路径生命周期一致」改为「**同一承键窗口内完成写后读**，`patch_push` 残留对后续同键写入无害」；「残留跨实例累积」写进 §13.2 ② 与 D-P16 的后续增量理由。互斥结论不受影响。<br>**④ SUG-13 判据可满足性**：V-08 与 §14.6 规则 2 的分母写死「**仅统计非空 `stepId`**」（阶段级行含 v0.3 新增的**收尾行**，按构造永不满足「恰一 START + 恰一终态」，含进分母则正确实现也只能得 n/(n+1)，KPI-02 恒 < 100%）；§14.6 `stepId` 行的阶段级枚举补「收尾行」〔v0.3.2 / SUG-20：该枚举又缺一类——`elapsedMs` 行与 §3.2 时序都在用的**阶段完成行**，现补成**七项**〕；§14.13.3 的交叉引用由「规则 5」（`ROLLBACK`）改指**规则 1/2**。<br>**⑤ SUG-14 措辞**：R1 的「同一个 map 实例」不可强制（`buildDeployConfig` 每次 `new HashMap<>()` + `putAll`，`:688`/`:698`）⇒ 改述「**同一数据源与同一取值路径**」，判据仍由 V-02 反例 a（行为测试）承载。<br>**⑥ SUG-15 分列**：组 L / B-16 的「命令与就绪判定逐类照抄各自 `DEPLOY`」对 lgsm-docker 的**就绪判定**不成立 ⇒ 按 §14.13.3 两列分开写（命令照抄 `DEPLOY`；就绪判定 compose 取自身 `DEPLOY` 的 `ps`，lgsm-docker 取自身 `healthCheck` 的逐个 `.State.Running`，即更严的那个），并在 §14.13.3 加前置说明。<br>**⑦ SUG-16 回写与比对面**：D-P12 补 PRD **§13.1 引言「生效前提」约定块（行 441）**（同一条件句在 PRD 有两处，只改 §8.5 会留下验收侧误读）；V-10 比对面由四项补为**五项**（补「状态转移」= 同频轮询 `GET /instances/{id}` 记下的 `runStatus` 序列），与 PRD AC-15 五项列举逐项对齐，T-04 / §11 AC-15 行 / §17 SUG-9 行同步。<br>**⑧ SUG-17 行号族**：`deploy-plugin.sh` 四组行号（`:27-28` / `:30-31` / `:44`+`:52-54`，构建在 else 臂 `:56-68` / 卸载-覆盖-清理-加载 `:110-135`，load `:133`、`purgeTasks=false` `:114`）与 `deploy.vue`（展开在 **`:714`**、`:713` 是 `isComposeVariableDeploy()` 守卫）按实跑值订正；§5.2「向导提交即带上它」加限定「**（compose 变量类部署下）**」。<br>**⑨ SUG-18 计数口径**：统一写「**六个字段位、展开为 8 个 VO 属性**」（`stepIndex`/`stepTotal`、`stepLabel`/`stepType` 各占一位、各含两属性），§14.6 字段表本身一字未动。<br>**新增编号**：无（九条全部落在既有节的既有编号上，编号序列无空位）。<br>**未改动**：三条硬判定的**结论**；§14.0 / §14.0.1 十四行的结论与状态；OP-05 / AC-22 等已在册口径；已关闭项状态；`prd.md`；`docs/ui/MERC-3/*`；功能代码。禁编造复扫命中 0。 |
| v0.3.2 | 2026-09-20 | Architect | **并入 ArchReviewer 定点确认（评论 `01a0bf2a`，结论 PASS、九条核销、条件解除）新增的两条 SUG-19 / SUG-20**，改法按 Leader 裁定（评论 `01a0bf2b`：**取值分支与是否降级由 Leader 定死**）；新 commit 续在 `528d471` 之后，不覆盖、不 force-push。两条**都不翻判定、不引入设计侧新编号**：<br>**① SUG-19 收尾「成功支」三套说法收口**：行为归属节 §14.12「改什么」行仍写成功支只记交棒行，与 §14.6 `stepEvent` 行（收尾取 `SUCCESS`/`FAILURE`）、§3.2 时序（收尾 → 阶段完成行 → 交棒行）对撞 ⇒ **按裁定取「两支都产行」**：成功 → `SUCCESS` 收尾行 → 阶段完成行 → 交棒行；失败 → `FAILURE` + `level = ERROR` 收尾行、部署 `ERROR`、不进 `HEALTH_CHECK`。**不取反向方案**（收窄 `stepEvent` 为只产 `FAILURE`）——那样 §8.1 的收尾耗时预算（compose ≤1200 s + 5 s / lgsm-docker ≤1200 s + 8 s）在日志里无承载行。§8.3 的量级估算同步为阶段级常施行**四行**、典型 3 步部署 12–15 → **13–16** 行；**§3.2 时序与 §14.6 `stepEvent` 行一字未动（Leader 明令）**；顺手复查 §14.13.3（只有「失败处置」一行）与 §6.2（只列责任边界）——**无「成功只记交棒行」残句**，故未动。<br>**② SUG-20 阶段级枚举补全**：§14.6 `stepId` 行的「定死」枚举由六项补为**七项**（增**阶段完成行**——本表 `elapsedMs` 行与 §3.2 时序都已把它当既存阶段级行使用）。取「补枚举」而**不取**「把『定死』降为示例」：「定死」的全部作用就是堵实现者反推「不在表里 ⇒ 它得有 `stepId`」，一旦真给了 `stepId`，SUG-13 刚杀掉的 n/(n+1) 会回来。KPI-02 分母无需改——规则 2 与 V-08 的排除靠 `stepId == null` 这个**判据本身**，不靠列表枚举。<br>**未动而点名交 Leader 的一处**：§14.6 规则 2 与 V-08 里那两处六项说明性列举（属九条已核销落点 SUG-13）按「不自行扩面」保留原样，已在回传评论里请 Leader 判是否同步为七项。<br>**新增编号**：无（`SUG-19`/`SUG-20` 是评审项编号，只出现在 §17 记账块与本版性质注）。<br>**未改动**：三条硬判定的**结论**；九条已核销的落点；§14.0 / §14.0.1 十四行的结论与状态（该行段 diff 零 hunk）；`prd.md`；`docs/ui/MERC-3/*`；功能代码。禁编造复扫命中 0。 |
| v0.3.3 | 2026-09-20 | Architect | **执行 Leader 裁定 `01a0bf39` 的一处「同一集合项数同步」**：v0.3.2 把 §14.6 `stepId` 行的「定死」枚举补成七项后，按「不自行扩面」将 §10 V-08 判据与 §14.6 规则 2 判据后的两处**说明性括注**留作六项、点名交 Leader 判；Leader 在 v0.3.2 通用门禁里裁定「补成七项，出 v0.3.3」，边界写死为**不改判据、不改分母口径、不动其它任何一节**。本版因此只加两个词：两处括注各补**阶段完成行**一项（V-08 处照它自身的合并写法、补在「收尾行」之后；规则 2 处补在「收尾」之后）。同一集合自此在文内三处给出同一项数——§14.6 `stepId` 行 7 段 7 项、规则 2 7 段 7 项、V-08 6 段 7 项（段数差来自 V-08 把「BR-12 与目录不合法说明行」并作一段，属措辞密度差异，非项数差异；本版不顺手统一它们的写法，那会改到 SUG-13 核销落点的既有行文）。<br>**判据与口径未动**：KPI-02 的「统计对象 = `stepId != null` 的行」「分母 = 非空 `stepId` 的去重计数」「每个非空 `stepId` 恰一 `START` + 恰一终态且终态 `elapsedMs != null`」「比例 = 100%」「脚本内不得出现 `message` 匹配」逐字保留；两处的排除一直是 `stepId == null` 这个判据本身、不是括注的项数（SUG-20 的原判定），故 SUG-13 建立的可满足性论证不受任何影响。词级 diff 实测：该两行的唯一变化即插入的「 / **阶段完成行**」。<br>**未动而说明的一处**：§17 SUG-20 表末格「未动而点名交 Leader 的一处」按「历史行不改写、订正只在新增块记账」的既有口径保留原样，处置与理由见 §17「v0.3.3 一处同步」。<br>**同族计数的核对**：§8.3「阶段级常施行**四行**（进入 / 收尾 / 完成 / 交棒）」与本版的七项**不是同一集合**（四行 = 每次有扩展步骤的部署必产的常施子集；七项含三类条件行：停实例行仅在有步骤时产、BR-12 拦截行与目录不合法说明行仅在不合法时产），四行 ⊂ 七项、无矛盾，故 §8.3 未动。<br>**新增编号**：无（`01a0bf39` 是 Leader 裁定编号，非设计侧编号；新旧版本的设计编号集合对称差为空集）。<br>**未改动**：三条硬判定的**结论**；九条与两项已核销落点的判据与口径；§14.6 `stepId` 行 `:673`（v0.3.2 已是七项，不在本版任何 hunk 内）；§14.0 / §14.0.1 十四行的结论与状态（该行段零 hunk）；§17 三张历史表与 §18 v0.1…v0.3.2 各行（零 hunk）；`prd.md`；`docs/ui/MERC-3/*`；功能代码。新 commit 续在 `4494bf0` 之后，不覆盖、不 force-push。禁编造复扫命中 0。 |
| v0.3.4 | 2026-09-22 | Spec（按 Leader 派工承接 G1 零轮次记账，非 @Architect 判定改动） | **G1 收口记账批（不占评审轮次、不改判定）**，三件按 MERC-6 登记口径同批一次升版：**①** §17 SUG-20 表末格补 `〔v0.3.3：已授权补项〕` 标记并指向「v0.3.3 一处同步」块（该格原以「点名请 Leader 判是否同步」收尾、闭环散在下块）；**②（SUG-22）** §17 自查块排序——「v0.3.3 自查」整块移至「v0.3.2 自查」之后，自查序列恢复单调 `v0.3 → v0.3.1 → v0.3.2 → v0.3.3 → v0.3.4`（纯排版，九行内容逐字未改、无判定影响）；**③（G1-b）** 元数据「平行输入」锚点自 `f6312ef`（UI v0.1）统一至 `62b49a9`（UI v0.6 定稿），并**按登记要求复核该行携带的交叉引用**：§14.9↔B1 / §14.4↔B2 / §14.11↔B3 三节归属复核为真，但 UI 侧至 v0.6 仍明写「不并收、判门权在 Leader、未定稿前不得自行引入」⇒ 该格由「已收口」改述为「本文已给结论、待 Leader 判门后由 UI 侧一次性并收」，避免只换 hash 把一条当时正确的断言静默变成错的。**④（SUG-21）不落判定文本，登记为待裁定**：`stepId == null` 两行（BR-12 拦截行 / 目录不合法说明行）未钉 `stage` 取值这个缺口核对为真，但**登记时给的两支改法都与 v0.3.3 既有内容相撞、且相撞方向不同**——取「两行都 `EXTENSION`」撞 §3.2 `:81`「无步骤集则本段整体不存在」与 §16.4 `:1045` 的 `INVALID` 态（并会反向触发 §14.11 / F-02 的阶段带 latch）；取「两行都 = 既有阶段」撞 UI 实物（§4.2 P/Q 明写阶段带**渲染**，LE / LF 屏含「进入部署扩展阶段」行）。逐屏实测：`LE` `LF` = 带 √ + 进入行 √；`LG` = 带 × + 进入行 × ⇒ **两行必须分别钉**，方案（BR-12 拦截行 = `EXTENSION`；目录不合法说明行 = 实际发生所在的既有阶段，本期 `"DEPLOY"`；并把 V-08 首判据取值域写明为「扩展阶段内的行」）连连带发现（`:81` 的存在条件与「入口判定即终止」这一支本身有张力）一并落在 §17 登记表，**交 Leader / @Architect 裁定后单独一批落文**。§14.6 `stage` / `stepId` 行、§10 V-08 判据、§3.2 `:81` 四处**一字未动**。判定面零改动、KPI-02 分母口径未动；新 commit 续在 `ee65e66` 之后，不覆盖、不 force-push |
