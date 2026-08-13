# ADR-0006: 补丁安装（PatchInstallService）决策树与执行模型

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-13 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0004](0004-host-lan-identification.md)（isLanHost 门控，本 ADR 落地其未来工作并细化容器分支）、[ADR-0002](0002-main-app-plugin-scope-isolation.md)（插件经宿主服务面消费） |
| Supersedes | 无（细化 ADR-0004「未来工作」中容器场景的自治分支，见决策 4） |

## 背景（Context）

插件安装补丁的诉求：给定一个资源 URL，把资源推送到目标实例（宿主机目录或容器内路径）的指定位置；压缩包解压后推送，非压缩包直接推送。ADR-0004 已冻结 isLanHost 作为「平台代劳」的硬开关并列出了决策树骨架，但探测、校验、备份回滚、批量并发重试等基础设施未定义。当前已有可复用设施：部署接入深模块 `DeploymentAccess`（凭据/建连/分类）、SFTP 深模块 `FileService`、任务中心（`TaskService`/`TaskHandlerRegistry`，MAIN 与插件来源均可注册）、`AbstractInstanceFileService.buildRoute`（native/docker 路由 + containerId/containerWorkDir 解析）、`InstanceFileServiceImpl` 的双向 docker cp 机制。

## 决策（Decision）

### 决策 1：接口归属与执行模型

`PatchInstallService` 接口放 plugin SDK 模块（`backend/plugin`，与 `InstanceFileService` 同层），实现放 core，随插件 Spring 子容器注入。执行模型为异步：`install(PatchInstallRequest)` 提交任务到任务中心（source=MAIN、taskType=PATCH_INSTALL），返回 taskId；进度写 TaskLog，插件经 `TaskService.getTask/getTaskLogs` 轮询、`cancelMyOwn` 取消。理由：批量并发、超时守护、进度、取消复用任务中心这一健康深模块，不为补丁安装另造执行设施。

### 决策 2：请求形状

`PatchInstallRequest`：`instanceId`、`url`、`targetPath`（safeRel 相对路径，相对实例安装路径/容器工作目录，复用现有路径安全校验与路由）、`format`（可选，缺省按 URL 扩展名推断）、`sha256`（可选校验）。`probeHost(hostId)` 作为独立接口暴露，供 UI 安装前预检主机能力。

### 决策 3：能力探测只在宿主机执行

探测脚本通过 SFTP 推送到宿主机临时目录并执行，**推送不区分局域网**（isLanHost 只门控补丁代劳，不门控探测）。返回格式契约（JSON）：`osType/hostname/arch/currentUser/tools{curl,wget,tar,gzip,bzip2,xz,unzip,bsdtar,sha256sum,shasum,rsync}/tmpFreeKb`。默认脚本为 core classpath 资源，允许自定义脚本（以返回格式契约为准）。探测结果按批内主机缓存，不跨任务持久化。**不在容器内执行探测**（见决策 4）。

### 决策 4：取消容器内自治分支，容器统一由宿主机代劳

ADR-0004 未来工作中的「容器内 curl/wget 自治（docker exec）」分支取消：容器目标一律由宿主机代劳——宿主机下载/解压，补丁目标为容器挂载目录时写入宿主机挂载源目录，非挂载目录时 docker cp 进容器。容器内不要求任何工具、不跑探测脚本。挂载目录判定：`docker inspect` 解析容器 Mounts（Source=宿主路径/Destination=容器路径），目标路径命中某挂载 Destination 前缀 → 写宿主机 Source 对应位置；未命中 → docker cp；inspect 失败或容器未运行 → 任务失败（无法可靠判定写入路径时不猜测）。实例路由（native/docker、containerId、containerWorkDir）复用 `AbstractInstanceFileService.buildRoute`。

### 决策 5：宿主机自治矩阵（LAN/WAN 门控）

按探测结果（curl/wget 判定下载能力；tar/unzip/bsdtar 与补丁格式匹配判定解压能力）分四支：

| 能下载 | 能解压 | 执行方式 |
|--------|--------|---------|
| 是 | 是 | SSH 远程下载脚本 + 远程解压（完全自治） |
| 是 | 否 | isLanHost=true：平台下载 + 平台解压 + SFTP 推散文件；false：报错 |
| 否 | 是 | isLanHost=true：平台下载 + SFTP 推压缩包 + 远程解压；false：报错 |
| 否 | 否 | isLanHost=true：平台下载 + 平台解压 + SFTP 推散文件；false：报错 |

（目标不能解压时「远程解压」不可行，两条不能解压的分支统一为平台解压推散文件。）

下载能力判定为「工具存在即尝试」，实际下载失败回退平台代劳（LAN）/报错（WAN），不做 URL 可达性预检。

### 决策 6：格式全集

按扩展名判定：tar.gz/tgz、tar.bz2/tbz2、tar.xz/txz、zip、gz/bz2/xz（单文件压缩）。「补丁包尽量统一 tar.gz」作为补丁源规范建议，不强制平台重打包（避免每个补丁多一次全量解压+重压缩）。

### 决策 7：校验、备份、回滚

校验：请求带 sha256 则下载后校验（失败中止）；无 sha256 则以解压成功（对应工具列出内容成功）为完整性验收。备份：解压/覆盖前在宿主机打包将被覆盖的顶层条目至 `<installPath>/.patch_backup/<时间戳>/`，保留最近 5 份。回滚：安装任一环节失败时自动恢复备份（尽力而为）；本期无手动回滚 API。临时目录（宿主机 /tmp 下）任务结束清理。

### 决策 8：批量执行

批量 = 每实例一个任务，由调用方循环提交，核心不提供 installBatch。并发控制：同一主机同时只跑一个补丁任务（handler 内按 hostId 互斥）+ 全局并发上限 3（信号量，超出排队）。失败重试：仅可重试错误（下载失败/SSH 瞬断）自动重试最多 2 次（指数退避 5s/20s）；校验失败不重试。

## 后果（Consequences）

### 正面

- 决策树全部由探测结果与 isLanHost 机械推导，无人工猜测分支
- 任务中心承载批量/进度/取消，插件侧只需一个 TaskService 查询方式
- 容器场景无需容器内任何工具，宿主机工具集即是全部前置条件
- 备份/自动回滚消除覆盖式安装的半覆盖风险

### 负面

- 平台代劳（下载+解压+推送）对 LAN 主机是平台侧流量与磁盘开销（已在 ADR-0004 接受）
- 挂载判定依赖 docker inspect，容器未运行时任务失败（不猜测路径的代价）
- 探测脚本经 SFTP 推送执行，恶意主机不可信时探测输出需按契约容错解析

## 备选方案（Alternatives）

### 备选 1：install 同步阻塞

**否决理由**：大补丁分钟级耗时卡死插件 HTTP 请求，无进度回报，批量并发需另建设施；任务中心已成熟且插件已接入。

### 备选 2：installBatch 单任务多目标

**否决理由**：失败粒度粗（一个实例失败难以独立展示/取消），与任务中心单任务单目标模型不符。

### 备选 3：保留容器内自治分支

**否决理由**：容器内探测与「探测只在宿主机」冲突；宿主机代劳（docker cp/挂载写）已覆盖全部容器场景，容器自治分支无增量收益且增加一套探测路径。

### 备选 4：平台代劳时强制重打包 tar.gz

**否决理由**：每个补丁多一次全量解压+压缩，大补丁耗时翻倍；统一格式收益仅在于目标机解压命令单一，而目标机解压能力已由探测得知。

## 相关文档

- [ADR 索引](README.md)
- [ADR-0004 主机局域网标识](0004-host-lan-identification.md)
- [术语表 PatchInstallService / HostCapabilities](glossary.md)
