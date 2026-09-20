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

## 14. §14.2 待改依赖逐行结论（十条，无留空）

### 14.0 结论速览

| # | 待改依赖 | 归属 | **结论** |
| --- | --- | --- | --- |
| 1 | 异步 → 阻塞桥接 | @Architect（RISK-03） | **给**：不走 `install()`；SDK 新增同步入口 `installSync(request, listener)`，core 侧直调 `PatchInstallExecutor.execute()`（本就是阻塞 + 回调），零轮询、零任务中心 |
| 2 | `includePattern` / `headers` 透传 | @Architect / @BackendDev（RISK-02） | **给**：取 RISK-02 两个合法选项中的「改用同链路内执行」——同步入口传请求对象引用，不经 payload 序列化，`includePattern` 自然生效；`headers` 本期结构性不可声明；异步路径的丢键缺陷本期**不修**（本期无使用方受影响） |
| 3 | 容器内脚本执行通道（exitCode / timeoutMs） | @Architect / @BackendDev（F-16） | **本期不改**：行 5 判 `position = container` 不合法 ⇒ 容器脚本通道本期零使用方，F-16 的两个缺口不再阻塞任何条目；登记为「`container` 恢复时的前置」 |
| 4 | **硬判定①** `imageTag` 落位机制 | @Architect | **给得出 ⇒ AC-14 转可验收**。机制 = 保留变量 `PLATFORM_IMAGE_TAG` 经既有 `.env` 生成链注入（compose 原生插值，不新增渲染器），注入点 = `buildDeployConfig` 而非扩展阶段（时点决定，见 14.4）。dnf-tw 本体不受惠（不改 `dnf_tw.yml`），由桩游戏外置元数据承载验收 |
| 5 | **硬判定②** 停实例 + `position = container` 合法性 | @Architect | **判不合法 ⇒ AC-13 移出本期**（自 §11.1 第一行转入第五行），`position` 本期取值只有 `host`，决策 7 的「容器内为步骤级可选项」随之收缩；属需求范围收缩，须回写 PRD（FR-08 / FR-11 / §8.3 / §12 / AC-13 / RISK-05）并记修订记录 |
| 6 | **硬判定③** §8.5 日志呈现契约三项 | @Architect | **定稿并登记**：① 步骤标识 = `LogEntryVO` 新增结构化字段 `stepId`（归组主键）+ `stepIndex`/`stepTotal`/`stepLabel`/`stepType`/`stepEvent`；② 耗时 = 同 VO 的 `elapsedMs`，单位毫秒（界面渲染文本不是核对对象）；③ 归组 = 同 `stepId` 归一步，「齐备」＝恰一 `START` + 恰一终态行且终态行 `elapsedMs != null`。KPI-02 / AC-03 / AC-16 自此脱离「不可测」 |
| 7 | 扩展阶段进度百分比区间 | @Architect | **条件分配**：无扩展步骤的部署**一个数字都不动**（BR-11 / AC-15 零风险）；有步骤时扩展阶段占 `[80, 84]`，`HEALTH_CHECK` 进入行的字面量在该分支内由 80 改报 85，`COMPLETE = 100` 与其余阶段不变 |
| 8 | `configInfo` 覆盖式写入丢键 → BR-16 手段 | @Architect 定手段 / @BackendDev 落地 | **取「合并式写入」**：`updateInstance` 的整表替换改为「取库中既有值 → 逐键合并 → 本次载荷覆盖」。不需要任何「拒绝写入」分支即满足 AC-27 (a)(b)(c)(d)，配置管理入口照常成功 |
| 9 | （S1b 门禁新增）`level → 视觉映射` 失效 | @Architect | **给**：前端归一化（`DeployProgress.vue:88-109` 先 `toLowerCase()` + 补 `warn → warning` 别名 + 补 `success` 分支）。后端 `level` 取值集合是 PRD §8.5 已固定口径，不改后端 |
| 10 | （本设计新发现）AC-22 / §8.4.2 S2 的界面前提不成立 | 交 Leader 裁定 | **登记**：部署向导只创建新实例，既有实例的重部署入口不接受版本改选 ⇒ 「在向导改选默认版本并重新部署 → 删键」这条路径今天不存在。给出 A/B 两方案与推荐（B），见 14.10 |

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
| BR-09 资源约束（并发 3 / 每主机互斥 / 重试 2 / SSH 600s） | 原样继承——全部在 `PatchInstallExecutor` 内（`:50-56`、`:794-801`、`SSH_TIMEOUT_MS = 600_000L` 见 `:54`）。实现者不得在扩展阶段外面再套一层重试 |
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

**为什么不能落在扩展阶段**：compose 模板驱动模式下 `DockerComposeAdapter` 把 `composeTemplate` **原文上传**（`:151-159`，无任何字符串替换），tag 的可选性只能靠 compose 自身的 `.env` 插值；而 `.env` 在 `PRE_DEPLOY` 就生成并上传（`:183-197`），容器在 `DEPLOY` 就起来了。扩展阶段在 `DEPLOY` 之后（决策 3）——在它里面改 tag 已经来不及，除非重跑 `DEPLOY`，那违反决策 3 与 BR-10。**所以注入点必须落在部署配置组装期。**

**机制（全部复用既有链路，不新增渲染器）**：

| 环节 | 约定 |
| --- | --- |
| 游戏元数据侧 | 想让 tag 可变的 deployType，模板里写 `image: <repo>:${PLATFORM_IMAGE_TAG:-<默认 tag>}`（compose 原生 `${VAR:-default}` 语法），并在该 deployType 的 `variables[]` 声明一项 `name = PLATFORM_IMAGE_TAG`、`hidden = true`、`defaultValue = <默认 tag>` |
| 为什么这样就够 | `generateEnvFileContent`（`:1397-1435`）本就遍历 `variables[].name` → 取 `config.get(name)` → 落 `.env`。只要 `config` 里出现同名键，tag 就走完整既有链路进 `.env`、被 compose 插值，**core 的渲染代码一行不改** |
| 框架侧唯一新增 | `InstanceServiceImpl.buildDeployConfig`（`:687-759`）第 5 步之后插入第 5.5 步：若 `configInfo.deployVersion` 命中版本目录条目、该条目声明 `imageTag`、且该 deployType 的 `variables[]` 含保留键 `PLATFORM_IMAGE_TAG` ⇒ `config.put("PLATFORM_IMAGE_TAG", imageTag)`。三条件任一不满足即完全不写 |
| 与第 6 步 `image`+`tag` 拼接的关系 | 无关系。那条只对 `services[].image` 结构化生成模式（`generateComposeFile`，`:905-935`）生效，模板驱动模式不经过它。本机制不借用它，以免把两种 deployType 的镜像语义搅在一起 |
| AC-14 的机械核对物 | ① 远端 `docker-compose.yml` 原文与未声明时逐字节相同；② `.env` 中 `PLATFORM_IMAGE_TAG=` 精确等于声明值；③ 该工作目录 `docker compose config` 渲染出的 `.services.<serviceName>.image` = `<repo>:<声明 tag>`。三项齐备即通过，不以文本比对冒充 |
| dnf-tw 是否受益 | **不受益**：本期不改 `dnf_tw.yml`（D-N15 / BR-02 / AC-02 / AC-15），其模板无占位符，故 dnf-tw 的 `imageTag` 恒不使用（FR-09 已如此规定）。机制由桩游戏承载验收：其元数据经既有外置目录 `game-platform.metadata.external-dir`（默认 `./games`）投放，不改 core resources、不进产品 jar（AC-26 ②） |

**回答 S1b 门禁转来第 2 项（只声明 `imageTag`、无 patches/scripts 的条目是否展示）**：**不隐藏、不加标注，而是判为声明不合法**。若条目声明 `imageTag` 而该 deployType 未声明保留键 `PLATFORM_IMAGE_TAG`，该 tag 必然静默无效——正是 Designer 担心的「看上去可选、实际什么都不做」。按 §8.1 校验内容扩展一条蕴含规则（「`imageTag` 存在 ⇒ 该 deployType 的 `variables[]` 必含 `PLATFORM_IMAGE_TAG`」），走 §8.1 既有处置（无键 → 默认版本 + 一条说明行；有键 → BR-12 拦截）。失败在声明读取期暴露，不在执行期静默吞掉。

**须回写 PRD（交 Leader，随 v0.6）**：§8.4.3 禁止清单增加保留键 `PLATFORM_IMAGE_TAG`（它与 `variables[].name` 同处一个扁平 map，属 BR-07 同类撞键对象）；§8.1 校验内容增加上述蕴含规则。

### 14.5 行 5（硬判定②）：停实例 + `position = container` ⇒ 判不合法，AC-13 移出本期

**判定依据（三条）**：

1. compose 类「停实例」= `docker compose -p … stop`（`DockerComposeAdapter.java:383-399`，超时 `120000`），**容器停止但保留**（不是 `down`）——容器定义仍在、卷与项目名不变，故 RISK-05 关于「`stop` 与 `down` 数据/网络后果不同」的担忧在选定语义下不成立；
2. `docker exec` / `compose exec` 的前置条件是容器 running，对已停止容器必然失败（F-16；`DockerComposeAdapter.executeCommand` 无任何拉起动作）；
3. 唯二绕开方式是「扩展阶段先把容器起起来、执行完再停」或「`docker run --rm` 起临时容器挂同样的卷」——前者直接制造决策 3 明确否掉的「先起错版本再重启」中间态；后者执行对象已不是目标容器，且要重新推导全部挂载与网络，属新造执行模型，超出本期范围。

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
| 停止判定 | 调适配器停止后以 `DeployAdapter.getStatus(instanceId)` 轮询（3 次 × 2s）判定非 RUNNING；成立才算停止完成 |
| 不复用 `DeployService.stop()` | 现有 `stop`（`:418-430`）会把 `run_status` 回写 STOPPED，而 PRD §9 要求扩展阶段期间仍为 `INSTALLING(5)`。故新增私有 `ensureStoppedForExtension()`：只调适配器、不写状态 |
| **停实例失败处置** | **致命**：记 ui-spec 状态 S 的失败行（`实例停止失败：…`，`level = ERROR`），部署判失败、实例 `ERROR`、不执行任何步骤、不进入 `HEALTH_CHECK` / `START`。理由：在未确认停止的实例上替换文件正是决策 3 要消除的中间态。ui-spec 的「预留文案，生效前提是 @Architect 定调」自此生效 |
| 后续启动 | 停止与扩展完成后走既有 `HEALTH_CHECK → UPDATE_STATUS → START`（决策 3 的「再走启动」由既有流程承担，本期不新增启动代码） |

### 14.6 行 6（硬判定③）：日志呈现契约三项 ⇒ 定稿

PRD F-03 的限制是真的：`LogEntryVO{id,level,message,stage,time}`（`DeployService.java:62-71`，映射在 `InstanceController.java:876-897`）既无步骤标识也无耗时字段。§8.5 允许「复用 message 固定前缀 / **扩展 VO 字段** / 其它」三选一——**取扩展 VO 字段**：`message` 前缀方案的归组判据是文本，而 KPI-02 要求「机械判定、不得靠人工文本判读」，用文本当锚点等于把 AC-03 / AC-16 / KPI-02 的核对建立在另一个文本约定上（UI 评审 MF-2 踩的正是这类软锚点）。

`LogEntry` / `LogEntryVO` 新增五个可选字段（既有阶段全部传 `null`，前端不读即零行为变化，AC-15 安全）：

| 字段 | 类型 | 契约项 | 约定 |
| --- | --- | --- | --- |
| `stage` | 既有 String | 阶段标识 | 扩展阶段全部行取常量 `"EXTENSION"`（与既有 `INIT/ENV_CHECK/…/COMPLETE` 均不冲突；该字段此前被前端完全丢弃，见 14.9） |
| `stepId` | String | ① 步骤标识（归组主键） | `E-<序号>`（如 `E-1`），同一次部署内唯一；阶段级行（进入 / 交棒 / 停实例 / BR-12 拦截 / 目录不合法说明）为 `null` |
| `stepIndex` / `stepTotal` | Integer | ① 步骤标识（展示位） | 序号 1 起；`stepTotal` 在阶段入口算步骤集时即得（FR-05 / FR-12），不为此新增接口 |
| `stepLabel` / `stepType` | String | ① 步骤标识（展示位） | `stepType ∈ PATCH / SCRIPT`；`stepLabel` 取声明侧 `label` |
| `stepEvent` | String | ③ 归组判据 | `START` / `SUCCESS` / `FAILURE` / `ROLLBACK` / `NOTE` |
| `elapsedMs` | Long | ② 耗时承载位与单位 | 毫秒整数（权威值）；仅 `SUCCESS` / `FAILURE` 与阶段完成行非空 |

**三条规则，逐条可核对**：

1. **归组**：`stepId` 相同的所有行属于同一步骤；一次部署内 `stepId` 与 `stepIndex` 一一对应。
2. **「三项齐备」判据**：某步骤齐备 ⇔ 该 `stepId` 下恰有一个 `stepEvent = START` 行 + 恰有一个终态行（`SUCCESS` 或 `FAILURE`），且该终态行 `elapsedMs != null`。KPI-02 分子 = 满足此式的步骤数，分母 = `stepId` 去重计数——纯字段判定，不读 `message`。
3. **串行可见**：步骤 N 的终态行之后才允许出现步骤 N+1 的 `START` 行（AC-06 判据，等价于 FR-12 的阻塞语义）。

`message` 文本仍是给人读的：词面由 ui-spec §6.2 定稿（例 `步骤 1/3 〈标签〉 · 补丁替换 · 成功 · 耗时 12秒`），耗时**渲染**沿用既有 `formattedElapsedTime`（`DeployProgress.vue:72-85`：`N秒`/`N分N秒`/`N小时N分`，不引入裸 ms）。**渲染文本不是核对对象，`elapsedMs` 才是**——这条分工写死，避免界面词面改动连带破坏 KPI-02。

`level` 口径不变（§8.5：致命 `ERROR`、非致命 `WARN`、成功 `SUCCESS`、过程 `INFO`），后端取值集合保持大写不变，改动落在前端（14.9）。

### 14.7 行 7：扩展阶段的进度百分比区间

现状逐字面量核对（`DeployService.java`）：`INIT 0` → `ENV_CHECK 5/10` → `PORT_CHECK 10/15` → `RESOURCE_CHECK 15/20` → `PRE_DEPLOY 20` + band `20–40` → `DEPLOY 40` + band `40–80` → `HEALTH_CHECK 80/90` → `UPDATE_STATUS 90` → `START 95/98` → `COMPLETE 100`；band 内插值公式在 `:860`。

**方案：条件分配（gated allocation）——有步骤才改数字。**

| 分支 | 分配 | 理由 |
| --- | --- | --- |
| 无扩展步骤（本期绝大多数游戏） | 完全沿用现有序列，不改任何字面量 | BR-11 / AC-15 / G-04 的回归基线对象就是这批游戏；任何全局重排都会改掉既有 `DEPLOY` band 的插值结果，直接违反 AC-15 |
| 有扩展步骤 | 扩展阶段占 `[80, 84]`：进入行报 80，其后按已完成步骤数 `80 + floor(4 × i / total)`，上限 84；`HEALTH_CHECK` 进入行的字面量在该分支内由 80 改报 85 | ① 不与任何既有阶段共用窗口（§6.2 约束）：`DEPLOY` 仍 `[40,80]`，扩展只吃 80–84，`HEALTH_CHECK` 仍收在 90；② `COMPLETE = 100` 与 `START`/`UPDATE_STATUS` 一字不改（BR-10）；③ 单调不减：80 → 80..84 → 85 → 90 → 95 → 98 → 100；④ 全部改动只有「有步骤时 HEALTH_CHECK 起点」一个数字 |

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
