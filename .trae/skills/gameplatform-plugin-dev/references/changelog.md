# 版本与维护约定 / Changelog

> 对齐主应用: `backend/` @ 2026-09-07（HEAD 1ce8f28）
> 关联 ADR: ADR-0001 插件菜单归属与 getMenus() 扩展点、ADR-0006 补丁安装决策树、ADR-0007 插件前端 Night Operations token 隔离、ADR-0009 平台侧能力需求、ADR-0011 定时任务管理、ADR-0014 插件开发文档三副本分工、ADR-0016 RCON 能力上提、ADR-0017 实例信息 Provider 扩展点（全文见平台仓库 `docs/design/adr/`）

## 1. 破坏性 / 高影响变更速查

使用版本敏感的插件 API、升级平台依赖或做插件兼容性判断前，先对照本表；完整语义见"详见"列与下方 Changelog 详情。

| 平台版本 | 变更 | 详见 |
|---|---|---|
| 3.10.0 | 新增 `RconService` 宿主服务（ADR-0016，插件不再自建 RCON 连接）；新增 `InstanceInfoProvider` 扩展点 + `InstanceDynamicInfo`（ADR-0017，玩家数插件化实时查询）；`InstanceFileService` / `FileAccessService` 上传/下载新增 `FileTransferProgressCallback` 进度回调（无回调旧签名保留为 default 方法，源兼容） | [host-services.md](host-services.md) §9、[extension-and-menus.md](extension-and-menus.md) §10 |
| 3.8.0 | 新增定时任务体系：`ScheduledTaskHandler` / `ScheduledTaskDeclarationExtension` / `ScheduleService`（独立于任务中心，无重试无互斥，重叠 SKIPPED） | [scheduled-tasks.md](scheduled-tasks.md) |
| 3.7.0 | 新增 `SshTunnelService` SPI、`configInfo.database` 组装、`onInstanceUpdate` 钩子（ADR-0009） | [host-services.md](host-services.md) §5-6 |
| 3.6.0 | 新增 `getDeployConfigs()` 部署配置声明（ADR-0008）；独立仓库构建插件四个坑（`-parameters` / lombok / clean package / 先 install provided） | [extension-and-menus.md](extension-and-menus.md) §9、[getting-started.md](getting-started.md) §6.1 |
| 3.5.0 | 热部署工作流（`deploy-plugin.sh` + `/api/pf4j/plugins/load` 端点）；卸载接口新增 `purgeTasks` 参数；宿主 loadPlugin 修复"先 start 再发现扩展点" | [gotchas.md](gotchas.md) §13 |
| 3.4.0 | `InstanceFileService` docker 分支语义落地（容器重建对账自愈、下载临时路径、writeTextFile 流式） | [host-services.md](host-services.md) §3 |
| 3.3.0 | **废弃** standalone 运行模式（ADR-0003），前端只剩 wujie + dev 两模式 | [frontend.md](frontend.md) §1 |
| 3.2.0 | 范围隔离规约（ADR-0002）：主应用不得含插件配置 / 插件表 / 插件 import | [gotchas.md](gotchas.md) §12 |
| 3.1.0 | **破坏性**：菜单改由 `getMenus()` 声明（ADR-0001），`getManifest().features` 废弃，宿主不预置任何默认菜单 | [extension-and-menus.md](extension-and-menus.md) §6/§8 |
| 3.0.0 | **破坏性**：`PluginContext` 不再持数据访问，持久化统一走 `ExtensionClient`；新增宿主服务面与 PluginManifestVO | [persistence.md](persistence.md)、[host-services.md](host-services.md) |

## 2. 版本与维护约定

| 项 | 规则 |
|---|---|
| 现行版本 | 本 SKILL 目录始终为最新版，顶部维护版本号与"对齐主应用版本/commit"标注 |
| 物理快照 | 每逢**主版本**变更（插件 API 破坏性改动或重大重构），将旧版另存归档保留 |
| Changelog | 本文件维护；主应用新增/变更 API 时追加条目并升版本号 |
| 升版规则 | minor 变更只更 changelog 与版本号；major 变更才产出新快照文件 |
| 权威来源 | 接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记 |

## 3. 历史快照

- v2.2.0 及更早版本：原 `docs/PLUGIN_DEV_GUIDE_V2.md`（如存在）
- v3.0.0 / v3.1.0 / v3.2.0 / v3.3.0：本 SKILL 目录（拆分整合后的分主题文件）

## 4. Changelog

| 版本 | 日期 | 变更 |
|---|---|---|
| 3.10.0 | 2026-09-07 | 插件 API 面三项扩展（均已实施）：① `RconService` 宿主服务（ADR-0016）：RCON 传输层上提为主应用宿主能力（`com.gameplatform.rcon` 归 core），SDK 暴露 `RconService`，插件按 instanceId 执行命令，端点解析（标准键 `configInfo.rconPort` 缺省 27015 / `rconPassword`）、连接池、认证全部宿主负责，密码不可由插件指定；每次执行自动携带调用方插件 ID 写统一审计日志；仅传输能力，命令语义（status 解析、kick/ban 等）插件自理。含 `testConnection(instanceId)` 连通性测试。② `InstanceInfoProvider` 扩展点（ADR-0017）：插件以 `@Component` 实现（子容器扫描注册，非 PF4J ExtensionPoint），按实例提供 `InstanceDynamicInfo`（playerCount / maxPlayerCount 类型化字段 + extras 开放扩展袋透传到实例详情 VO）；未实现自动降级（RUNNING 用库中存量值，非 RUNNING 为 0）；并发调度、15s TTL 缓存、列表场景 3s 整体查询预算均由主应用负责，返回 null 表示本次不可知（降级不落库）。前端玩家数上限优先展示 Provider 实时值。③ 文件传输进度：`InstanceFileService` / `FileAccessService` 的 `uploadLocalFile` / `downloadFile` 新增 `FileTransferProgressCallback` 重载（onStart/onProgress/onComplete/onError；同步回调勿做耗时操作；频率不保证但 onComplete/onError 各至多一次；回调抛异常即中止传输可作取消；totalBytes 未知为 -1；Docker 类部署进度仅覆盖 SFTP 段，docker cp 段无反馈）；全链路流式不载入内存；无回调旧签名保留为 default 方法。另：游戏元数据归一化——l4d2 compose 变量统一用标准键 `rconPassword`，端点解析只认标准键，插件自定义变量名不再被识别 |
| 3.9.1 | 2026-08-29 | 删除 skill 内 `examples/`（plugin-mygame 示例已过时）：骨架代码以 `getting-started.md` §2-6 为准，前端易缺失文件清单见 `frontend.md` §10，独立仓库 pom 规范见 `getting-started.md` §6.1；清除全部指向平台仓库的相对路径链接（ADR 仅保留编号引用，跨项目不产生死链；网络 URL 不受影响）。插件 API 面无任何变更 |
| 3.9.0 | 2026-08-29 | 文档重组（对齐 halo-plugin-dev skill 范式，ADR-0014）：SKILL.md 瘦身为路由器（概述 + Quick Start + 开发工作流 + 关键不变量 + 触发式 references 索引 + 排查速查），密集事实下沉分主题文件；references 文件名与 `docs/plugin-development/` 对齐（getting_started→getting-started、extension_and_menus→extension-and-menus、extension_client→persistence、host_services→host-services、task_handler→async-tasks、scheduled_task→scheduled-tasks、walkthrough_l4d2→walkthrough-l4d2、sdk_reference→sdk-reference）；changelog 增"破坏性 / 高影响变更速查表"；sdk-reference 按"指路而非复制"修剪（宿主校验规则、隧道生命周期、路径常量表下沉到各主题文件）；文档定位澄清（ADR-0014）：`backend/plugin/` 源码为 API 权威，`docs/plugin-development/` 为面向人的权威文档，本 skill 为 AI 自包含副本（工作区 `.trae` 与用户级 `~/.agents` 两处逐字同步，概念与 docs 对齐不逐字一致）。插件 API 面无任何变更 |
| 3.8.0 | 2026-08-22 | ADR-0011 定时任务体系（独立于任务中心，已实施并提交）：插件新增三个接入点——`ScheduledTaskHandler`（`@Component` 一个 Handler 一个 key）、`ScheduledTaskDeclarationExtension`（声明式默认计划，宿主按 pluginId:key upsert）、`ScheduleService`（编程式 CRUD 服务，注入子容器自动绑定本插件 source，强制来源隔离）。关键语义：无自动重试（下一轮 cron 即天然重试）、不互斥、创建时不校验 handlerKey 触发才解析（未注册记 FAILED）、同一计划同时刻只允许一个 run（重叠记 SKIPPED）、停机不补跑、payload 快照传 Handler、run 日志 500 条上限 + 30 天清理。插件生命周期联动：停用/热重载（purgeTasks=false）暂停、重载恢复、卸载移除（purgeTasks=true）物理清理。主界面「任务中心 → 定时计划」统一管理。新增陷阱：声明式计划被用户改过（userModified）会被 upsert 跳过、删过不复活；热部署勿用 purgeTasks=true（会删计划）。详见 `references/scheduled-tasks.md` 与 ADR-0011 |
| 3.7.0 | 2026-08-18 | ADR-0009 平台能力三项扩展（已实施并重启验证）：新增 `SshTunnelService` SPI（SSH 本地端口转发：openByHost 平台凭据复用会话池+钉住 / openWithCredentials 插件凭据专用会话不落库；去重键含 ownerPluginId 跨插件不共享；引用计数 + 三层兜底关闭；本地端口仅绑 127.0.0.1）；新增 `configInfo.database` 组装（yml dockerCompose.database 变量名引用式声明，部署与更新同路径组装，用户输入 > 默认值 > 字面量）；新增 `onInstanceUpdate` 钩子（update 后完整新 configInfo，每次更新都触发，diff 插件自理）；新增陷阱：onInstanceCreate 时 configInfo 尚无 database 节（懒建）、老实例裸变量回退、隧道连接地址为 127.0.0.1:localPort |
| 3.6.0 | 2026-08-16 | 部署方式配置扩展（ADR-0008）：`GameEnhancementExtension.getDeployConfigs()` 返回 `DeployConfigDeclaration`（deployType + 与 yml deployConfig 同构的配置节）；主应用读取时合并——部署选项 = yml supportedDeployTypes ∪ 插件声明类型（仅主应用支持的 code，未知忽略告警）、同一类型插件节整节替换（插件优先）；执行仍走 DeployAdapter 体系；插件热部署即生效，不落库。另：plugin-dst 实战经验回填（独立仓库构建插件四坑——先 `mvn -pl api,plugin install`、独立 pom 必须显式 `-parameters`（Spring 6.1 无 LVT 回退 + 子容器/主容器双候选 bean 靠参数名消歧）、lombok 自带、改 pom 后须 `clean package`；子容器 bean 创建失败静默继续的症状与排查（STARTED+manifest 正常但控制器未注册 → "No static resource plugin/..."）；子容器 `@Scheduled` 疑似不生效（未 @EnableScheduling，自管 ScheduledExecutorService 规避）；无 RCON 游戏控制台通道模式（tmux send-keys + 日志标记截取 + 长命令绕过适配器 60s 超时）；mygame 前端示例缺失文件补齐（tsconfig.json / env.d.ts）；deploy-plugin.sh 部署外部仓库 jar 用法） |
| 3.5.0 | 2026-08-16 | 插件热部署工作流：新增 `scripts/deploy-plugin.sh`（构建 → unload 释放 Windows jar 锁 → 覆盖 → load，后端免重启）与宿主 `POST /api/pf4j/plugins/load?jarName=` 端点（限定插件目录防穿越）；卸载接口新增 `purgeTasks` 参数（热部署传 false 保留任务中心历史）；修复热加载扩展点丢失 bug（PF4J per-plugin 扩展查找仅对 STARTED 生效 → loadPlugin 先启动再发现）；ADR-0007 插件前端 Night Operations token 隔离（复制 variables.scss 副本、暗色单主题、sass `$--` 私有成员陷阱、html.dark 特异性）；新增 Wujie popper 定位漂移陷阱与 wujiePopperFix 运行时修正方案 |
| 3.4.0 | 2026-08-16 | Docker 文件路由语义落地：InstanceFileService 补 docker 分支实现细节（containerWorkDir 解析链 + workingDir 元数据回退、ContainerIdResolver 解析链（compose 动态查询→容器名→containerId、docker 默认名 `game-instance-{id}`）、downloadFileToMemory 宿主临时路径注意点、writeTextFile SFTP+docker cp）；主应用文件管理端点统一走 InstanceFileService（复用 buildRoute 路由，ADR-0006 决策 4）；同步对账容器重建自愈（compose projectName 前缀匹配 + IMAGE_REPO/IMAGE_TAG 识别 + containerId 写回）；实例控制台 docker 分支 PTY 修复（SSH exec channel 无 TTY → 宿主 script(1) 包装）；部署命令 shell 级 timeout 兜底（SshUtil timeoutMs 仅作用于建连） |
| 3.3.0 | 2026-08-03 | ADR-0003 废弃 `plugin-l4d2-standalone`：物理删除后端模块（28 文件）+ 前端 standalone 代码（3 文件删除 + 8 文件简化）；运行模式从 3 种简化为 2 种（wujie + dev）；`plugin-l4d2/pom.xml` 移除子模块声明；新增插件不应实现 standalone 模式 |
| 3.2.0 | 2026-08-03 | ADR-0002 范围隔离规约：主应用 `core/` 不得包含插件业务配置（`plugin.{gameCode}`）和插件专属表（`{gameCode}_*`）；插件配置由 `@ConfigurationProperties` 字段默认值自负，插件表由 ExtensionClient 自管；游戏元数据 `games/{gameCode}.yml` 为例外；删除主应用 `application.yml` 的 `plugin.l4d2` 块和废弃的 `V1.4__L4D2_plugin_tables.sql` |
| 3.1.0 | 2026-08-02 | ADR-0001 菜单归属权迁移：新增 `getMenus()` 扩展点 + `PluginMenuDeclaration` 强类型声明；废弃 `getManifest()` 的 `features` 字段；宿主 `buildDefaultMenus()` 删除，新增 `buildMenusFromDeclarations()` 仅做校验与序列化（path 唯一性 / `requireInstance` 默认值补全）；SKILL 文档拆分整合：原 `docs/PLUGIN_DEV_GUIDE.md` 单一长文档拆解为 SKILL 目录下多个分主题文件（getting_started / extension_and_menus / extension_client / host_services / task_handler / frontend / exceptions / walkthrough_l4d2 / checklist / sdk_reference / gotchas / changelog） |
| 3.0.0 | 2026-08-02 | 重大重写：新增宿主服务面章（HostQueryService/InstanceQueryService/InstanceFileService/FileAccessService）；新增 PluginManifestVO 契约；修正菜单机制（features→buildDefaultMenus，纠正 v2.2.0 manifest.json 控菜单的误导）；新增前端三形态章（含 standalone 后端与 Wujie 通信）；补全生命周期钩子（onInstanceCreate/Stop/Delete）；ExtensionClient 补全 updateStatus/deleteById/getById/listAll/count/getManagedTables；修正不存在的 PluginDataAccessException；新增基于真实异常类的 FAQ；新增 plugin-l4d2 walkthrough 章；新增版本与维护约定（物理快照 V1/V2 + changelog）；新增验收标准 |
| 2.2.0 | 2026-08-02 | 任务处理器规范（ADR-009/010/014/023/025/026）；ExtensionModel 存储策略；PluginContextHolder |
