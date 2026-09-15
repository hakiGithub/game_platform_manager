# ADR-0027: L4D2 地图识别与开图命令

- 状态：Accepted
- 日期：2026-09-15
- 关联：[ADR-0026](0026-host-tooling-container.md)（HostToolingService 语义方法扩展路径）、[ADR-0025](0025-l4d2-cloud-map-install.md)（云盘转存安装链路）、[ADR-0018](0018-map-upload-task-and-archives.md)（地图上传异步任务）

## Context

地图列表在 ADR-0026 后刻意退化为纯目录列举（此前逐个 SFTP 下载 VPK 回平台解析，394MB 耗时 20s 级），列表只有文件名，没有战役名/章节/模式。与此同时，地图中心爬虫已抓取作者标注的 `map <章节码>` 命令（领域定名**开图命令**，源站叫"建图命令"），但安装地图后这些命令无处落地：用户要开新图只能手动翻详情页复制命令到控制台。

`platform-tools` 镜像（ADR-0021/0026）无 VPK 分析工具；按 ADR-0026 信任模型，插件不持有裸容器执行权，主机侧深度解析必须以语义化方法扩展 `HostToolingService`。

## Decision

1. **HostToolingService 新增语义方法 `analyzeVpk`**：`analyzeVpk(hostId, remoteVpkPath) → {title, chapters:[{code, title?, modes?}]}`（结构对齐现有 `MissionInfoVO`，复用前端展示组件）。实现：`platform-tools` 镜像增补 VPK 分析脚本，`docker run --rm` 临时容器只读挂载文件执行；主机无 Docker 时回退平台侧解析（现有 `VpkParserService`，文件拉回平台，慢但可用）。脚本实现与依赖归主应用维护。

2. **识别结果摘要键控、插件级共享**：识别记录表（插件 ext 表，如 `ext_plugin_l4d2_map_recognition`）以 **VPK sha-256 摘要**为主键，字段含 title / chapters / status（`OK | FAILED | INVALID`）/ analyzedAt / errorMessage / launchCommands。同一文件全平台只识别一次，跨实例复用；覆盖安装内容变化产生新摘要、自然失效旧记录；不校验重复、不清理孤儿记录（个人规模）。

3. **实例侧只存"文件→摘要"索引**：实例维度轻量索引表（vpkName → digest + PENDING/READY）。`/maps/refresh` 只对目录新增文件记 PENDING（零内容读取），并对已消失文件清索引条目（共享识别记录保留）。**摘要计算收拢在识别流程内**：识别任务先以主机原生 `sha256sum` 算摘要，命中共享记录即直接复用、跳过容器分析。列表渲染 = 目录列举 + join 索引 + join 共享表，永远零现场解析。

4. **识别触发（四路）**：① 云盘转存/URL/Workshop 安装任务完成后追加识别步骤——cloud-install 进度段调整为 转存 0-40% / 下载 40-80% / 安装 80-95% / 识别 95-100%，**识别失败不连坐**（安装成功态不变，失败落在识别记录、列表可见可重试）；② map-upload 平台中转路径文件本来过平台，直接用平台侧解析落记录（不走容器）；③ 列表页手动"批量识别"——异步任务中心任务（taskType=`map-recognize`，与 map-upload 同款按实例互斥），重试 PENDING/FAILED、不动 OK；④ refresh 发现新文件后自动触发一次 map-recognize。INVALID（非有效 VPK/解析不出章节）允许手动重识别。

5. **开图命令流**：安装任务命中地图中心元数据时（sourceId 精确匹配优先、任务产物 vpk 文件名对爬虫 vpkFileName 兜底），把开图命令存入识别记录 `launchCommands`。入口三处，执行走现有 RCON 语义层通道（自带调用方审计）：① 下载管理/安装任务详情（完成后展示）；② 地图中心地图条目（命中元数据时）——①② 弹实例选择器（单实例自动选中，沿用转存对话框交互）；③ 地图列表识别章节行内（绑定本实例，不弹）。实例非运行中按钮置灰；**绝不静默自动执行**（服务器可能有真人在线）。开图命令仅云盘转存链路提供（唯一携带地图中心元数据的安装路径）。

## Consequences

- `platform-tools` 镜像增补 VPK 分析脚本与依赖，随平台发版；存量主机需按 ADR-0021 约定重新 pull 镜像（文档提示）。
- `HostToolingService` 能力面 +1（ADR-0026 约定的逐方法 ADR 扩展路径）。
- 插件新增两张 ext 表（共享识别表 + 实例文件索引）与 `map-recognize` 任务类型；前端地图列表增加识别状态与章节展开、三处开图入口、批量识别按钮。
- 首次 refresh 后、识别任务完成前，新图显示"未识别"（异步可见性权衡，不做同步解析）。
- 上传路径零容器依赖；Native 实例（无 Docker）识别自动回退平台侧解析。

## Alternatives

- **实例维度存识别结果**：被否——同一 VPK 跨实例、重复安装会重复分析；按摘要共享天然去重。
- **列表/refresh 现场算摘要或解析**：被否——大文件秒级 × N 会拖垮列表接口，正是 ADR-0026 纯目录列举要避免的坑。
- **插件自建 Docker 客户端跑 VPK 工具**：被否——裸容器执行权不开放（ADR-0026 信任模型）。
- **安装完成后自动执行开图命令**：被否——静默换图可能中断在线对局，属事故行为。
- **开图命令仅安装任务内展示（不入识别记录）**：被否——地图列表识别出章节后行内开图是主入口，命令必须随识别记录持久化。
