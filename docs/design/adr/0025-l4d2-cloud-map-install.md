# ADR-0025: L4D2 地图中心云盘转存安装（主机直连优先、平台中转兜底）

- 状态：Accepted
- 日期：2026-09-15
- 关联：[ADR-0024](0024-cloud-drive-host-capability.md)（云盘宿主能力）、[ADR-0006](0006-patch-install-decision-tree.md)（补丁安装决策树，本 ADR 为其扩展 headers 支持）

## Context

L4D2 地图中心需要"一键转存后下载"：把网盘分享链接（夸克/百度/天翼等）转存到平台托管的云盘账号（ADR-0024），再把地图文件下载安装到游戏实例的 `left4dead2/addons/`。云盘直链必须连带请求头（Cookie/UA）才能访问，而主应用 PatchInstall 现有下载链路（主机 curl / Docker 工具镜像 / 平台 hutool）均不支持自定义请求头。

## Decision

1. **总流程（插件 TaskHandler `cloud-install`）**：提交任务（账号 + 分享链接 + 提取码 + 目标实例）→ 探测转存目录 `/maps/{source}-{sourceId}` 是否已有产物（`list` 探测，有则跳过转存）→ 否则 `transfer`（同步，含 passcode）→ `list` 产物目录 → 筛选 `.vpk`（直装）与 `.zip`（解压取 vpk），其余忽略 → 按实例能力选下载通道 → 上传/安装 → 写 `DownloadTaskResource`（taskType=CLOUD）。进度分段：转存 0-40%、下载 40-80%、上传/安装 80-100%。

2. **主机直连优先**：插件先 `patchInstallService.probeHost(hostId)`，主机可自治下载（curl/wget/docker 任一）时走直连——扩展 `PatchInstallRequest` 增加 `headers` 字段，`PatchInstallExecutor.remoteDownload` 以 `curl --header @file` 方式带头：headers 先 SFTP 写主机临时文件（0600），curl 读文件，用完即删；命令行不出现 Cookie（防 ps 泄露）。远程 curl 版本不支持 `--header @file`（<7.55）时**回退平台中转**，不降级为明文 `-H`。压缩包在直连路径由 PatchInstall 既有解压策略处理。

3. **平台中转兜底**：主机不可自治下载（公网无工具等）或直连前置条件不满足时，插件走 `cloudDriveService.download` 流到本地临时文件（SDK 内部解析直链并自动附带必需请求头）→ VPK magic 校验 → `instanceFileService.uploadLocalFile` 推送——复用 Workshop 下载链路的校验/进度/并发设施；压缩包用 `ArchiveExtractUtil.extractVpks` 解出 vpk。主应用 hutool 平台下载策略不加 headers 支持（避免主应用改动扩大）。

4. **直链时效契约**：直链短时效（分钟级）且 URL 与 headers 绑定。直连流程必须"拿链→立即下发"，直链不缓存不复用；curl 失败（直链过期/403）重取一次直链重试，仍失败自动转平台中转，任务不因直链过期而失败。

5. **幂等与覆盖**：转存目录按 `/maps/{source}-{sourceId}`（裸链接用 `share-{hash8}`）组织，SDK Diff 引擎对重复转存自动去重；`MapResource` 不回写云盘状态（是否已转存以 list 探测为准）；同名 vpk 安装直接覆盖，任务日志注明。

6. **接口与前端**：`CloudDriveService.transfer` 签名直接加 `passcode` 参数（null=无提取码）；插件前端直调主应用 `/api/cloud/accounts`（已收紧 ROLE_ADMIN，与插件用户天然对齐）获取账号下拉。入口两处：MapCenter 地图详情"转存并安装"（自动带 sourceId/标题）+ Download 页"云盘分享链接"（裸链接，不依赖爬虫元数据）。

7. **并发**：转存安装任务与 URL/Workshop 下载共用 DownloadService 的 3 并发 Semaphore 与测速窗口，避免双通道叠加打满主机带宽。

## Consequences

- 主应用 `PatchInstallRequest`/`PatchInstallExecutor` 新增 headers 支持（仅远程下载两条路径），`PatchDecisionEngine` 决策树不变。
- 直连模式下地图文件不过平台（省平台带宽）；中转兜底保证任何实例形态都能装。
- 网盘 Cookie 可能短暂存在于远程主机临时文件（0600、用完删），不再出现在命令行。
- DownloadTaskSpec 增加 `accountName`/`cloudPath`/`transferMode(DIRECT|RELAY)` 字段。
- RAR/7z 压缩包在中转路径由插件解（现有 extractVpks），直连路径交 PatchInstall 格式能力。

## Alternatives

- **平台中转单通道**：被否——用户明确优先主机直连（个人场景省平台带宽、大文件更快）。
- **主应用全面支持 headers（含 hutool 平台下载）**：被否——改动面大且平台下载策略场景由插件中转等价覆盖。
- **明文 `curl -H "Cookie: ..."`**：被否——ps 可见，泄露风险不必要的。
- **MapResource 回写转存状态**：被否——list 探测已幂等，不给元数据模型加安装状态。
- **产物手动勾选 UI**：被否——自动筛选（vpk/zip）覆盖绝大多数分享，第一版不做勾选。
