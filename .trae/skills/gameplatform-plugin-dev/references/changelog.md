# 版本与维护约定 / Changelog

> 对齐主应用: `backend/` @ 2026-08-16
> 关联 ADR: [ADR-0001 插件菜单归属与 getMenus() 扩展点](../../../../docs/design/adr/0001-plugin-menu-ownership.md)、[ADR-0006 补丁安装决策树](../../../../docs/design/adr/0006-patch-install-decision-tree.md)、[ADR-0007 插件前端 Night Operations token 隔离](../../../../docs/design/adr/0007-plugin-frontend-nightops-token-isolation.md)

## 1. 版本与维护约定

| 项 | 规则 |
|---|---|
| 现行版本 | 本 SKILL 目录始终为最新版，顶部维护版本号与"对齐主应用版本/commit"标注 |
| 物理快照 | 每逢**主版本**变更（插件 API 破坏性改动或重大重构），将旧版另存归档保留 |
| Changelog | 本文件维护；主应用新增/变更 API 时追加条目并升版本号 |
| 升版规则 | minor 变更只更 changelog 与版本号；major 变更才产出新快照文件 |
| 权威来源 | 接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记 |

## 2. 历史快照

- v2.2.0 及更早版本：原 `docs/PLUGIN_DEV_GUIDE_V2.md`（如存在）
- v3.0.0 / v3.1.0 / v3.2.0 / v3.3.0：本 SKILL 目录（拆分整合后的分主题文件）

## 3. Changelog

| 版本 | 日期 | 变更 |
|---|---|---|
| 3.6.0 | 2026-08-16 | 部署方式配置扩展（ADR-0008）：`GameEnhancementExtension.getDeployConfigs()` 返回 `DeployConfigDeclaration`（deployType + 与 yml deployConfig 同构的配置节）；主应用读取时合并——部署选项 = yml supportedDeployTypes ∪ 插件声明类型（仅主应用支持的 code，未知忽略告警）、同一类型插件节整节替换（插件优先）；执行仍走 DeployAdapter 体系；插件热部署即生效，不落库。另：plugin-dst 实战经验回填（独立仓库构建插件四坑——先 `mvn -pl api,plugin install`、独立 pom 必须显式 `-parameters`（Spring 6.1 无 LVT 回退 + 子容器/主容器双候选 bean 靠参数名消歧）、lombok 自带、改 pom 后须 `clean package`；子容器 bean 创建失败静默继续的症状与排查（STARTED+manifest 正常但控制器未注册 → "No static resource plugin/..."）；子容器 `@Scheduled` 疑似不生效（未 @EnableScheduling，自管 ScheduledExecutorService 规避）；无 RCON 游戏控制台通道模式（tmux send-keys + 日志标记截取 + 长命令绕过适配器 60s 超时）；mygame 前端示例缺失文件补齐（tsconfig.json / env.d.ts）；deploy-plugin.sh 部署外部仓库 jar 用法） |
| 3.5.0 | 2026-08-16 | 插件热部署工作流：新增 `scripts/deploy-plugin.sh`（构建 → unload 释放 Windows jar 锁 → 覆盖 → load，后端免重启）与宿主 `POST /api/pf4j/plugins/load?jarName=` 端点（限定插件目录防穿越）；卸载接口新增 `purgeTasks` 参数（热部署传 false 保留任务中心历史）；修复热加载扩展点丢失 bug（PF4J per-plugin 扩展查找仅对 STARTED 生效 → loadPlugin 先启动再发现）；ADR-0007 插件前端 Night Operations token 隔离（复制 variables.scss 副本、暗色单主题、sass `$--` 私有成员陷阱、html.dark 特异性）；新增 Wujie popper 定位漂移陷阱与 wujiePopperFix 运行时修正方案 |
| 3.4.0 | 2026-08-16 | Docker 文件路由语义落地：InstanceFileService 补 docker 分支实现细节（containerWorkDir 解析链 + workingDir 元数据回退、ContainerIdResolver 解析链（compose 动态查询→容器名→containerId、docker 默认名 `game-instance-{id}`）、downloadFileToMemory 宿主临时路径注意点、writeTextFile SFTP+docker cp）；主应用文件管理端点统一走 InstanceFileService（复用 buildRoute 路由，ADR-0006 决策 4）；同步对账容器重建自愈（compose projectName 前缀匹配 + IMAGE_REPO/IMAGE_TAG 识别 + containerId 写回）；实例控制台 docker 分支 PTY 修复（SSH exec channel 无 TTY → 宿主 script(1) 包装）；部署命令 shell 级 timeout 兜底（SshUtil timeoutMs 仅作用于建连） |
| 3.3.0 | 2026-08-03 | ADR-0003 废弃 `plugin-l4d2-standalone`：物理删除后端模块（28 文件）+ 前端 standalone 代码（3 文件删除 + 8 文件简化）；运行模式从 3 种简化为 2 种（wujie + dev）；`plugin-l4d2/pom.xml` 移除子模块声明；新增插件不应实现 standalone 模式 |
| 3.2.0 | 2026-08-03 | ADR-0002 范围隔离规约：主应用 `core/` 不得包含插件业务配置（`plugin.{gameCode}`）和插件专属表（`{gameCode}_*`）；插件配置由 `@ConfigurationProperties` 字段默认值自负，插件表由 ExtensionClient 自管；游戏元数据 `games/{gameCode}.yml` 为例外；删除主应用 `application.yml` 的 `plugin.l4d2` 块和废弃的 `V1.4__L4D2_plugin_tables.sql` |
| 3.1.0 | 2026-08-02 | ADR-0001 菜单归属权迁移：新增 `getMenus()` 扩展点 + `PluginMenuDeclaration` 强类型声明；废弃 `getManifest()` 的 `features` 字段；宿主 `buildDefaultMenus()` 删除，新增 `buildMenusFromDeclarations()` 仅做校验与序列化（path 唯一性 / `requireInstance` 默认值补全）；SKILL 文档拆分整合：原 `docs/PLUGIN_DEV_GUIDE.md` 单一长文档拆解为 SKILL 目录下多个分主题文件（getting_started / extension_and_menus / extension_client / host_services / task_handler / frontend / exceptions / walkthrough_l4d2 / checklist / sdk_reference / gotchas / changelog） |
| 3.0.0 | 2026-08-02 | 重大重写：新增宿主服务面章（HostQueryService/InstanceQueryService/InstanceFileService/FileAccessService）；新增 PluginManifestVO 契约；修正菜单机制（features→buildDefaultMenus，纠正 v2.2.0 manifest.json 控菜单的误导）；新增前端三形态章（含 standalone 后端与 Wujie 通信）；补全生命周期钩子（onInstanceCreate/Stop/Delete）；ExtensionClient 补全 updateStatus/deleteById/getById/listAll/count/getManagedTables；修正不存在的 PluginDataAccessException；新增基于真实异常类的 FAQ；新增 plugin-l4d2 walkthrough 章；新增版本与维护约定（物理快照 V1/V2 + changelog）；新增验收标准 |
| 2.2.0 | 2026-08-02 | 任务处理器规范（ADR-009/010/014/023/025/026）；ExtensionModel 存储策略；PluginContextHolder |
