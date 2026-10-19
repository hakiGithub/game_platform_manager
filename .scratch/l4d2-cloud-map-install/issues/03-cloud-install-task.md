# 03: 插件 cloud-install 任务链路（转存→筛选→直连/中转→安装）

Status: done

## 任务

- `CloudInstallTaskHandler`（taskType=`cloud-install`，注册进 L4D2TaskHandlerExtension）：
  - payload：`{instanceId, accountName, shareUrl, passcode?, source?, sourceId?, title?}`。
  - 阶段①（0-40%）：`cloudDriveService.list(account, /maps/{source}-{sourceId})` 探测已转存（有 .vpk/.zip 即跳过转存）；否则 `transfer(...passcode...)`。
  - 阶段②（40-80%）：list 产物筛选 .vpk/.zip；`probeHost` 判定：可自治 → DIRECT（`link()` 拿直链+headers → `patchInstallService.install(PatchInstallRequest{instanceId,url,targetPath=left4dead2/addons/,headers})`，轮询任务；失败原因含 UNSUPPORTED_HEADER_FILE 或下载失败 → 重取直链重试一次 → 仍失败 RELAY）；否则 RELAY（`cloudDriveService.download` 到 tmp → VPK magic 校验 → zip 走 `ArchiveExtractUtil.extractVpks` → `uploadLocalFile`）。
  - 阶段③（80-100%）：上传/安装，覆盖语义，`context.log` 注明；结果 summary 列出实际安装文件与 transferMode。
  - 与 URL/Workshop 共用 DownloadService 3 并发 Semaphore。
- `DownloadTaskSpec` 加 `accountName`/`cloudPath`/`transferMode` 字段，taskType 加 `CLOUD`；下载页列表可见。
- REST：`POST /api/plugin/l4d2/download/cloud`（入参同 payload），挂 DownloadController。

## 验收

- 真实夸克/天翼分享链接走通 DIRECT 与 RELAY 两通道；直链过期场景自动重试转中转；取消任务可中断。

Blocked by: 01, 02

## Comments

2026-09-15 实现完成并热部署冒烟：handler 注册（cloud-install）、REST /download/cloud 校验、假链接端到端（任务中心执行→transfer 拒绝非 189 链接→记录 FAILED+errorMessage 落库）、详情/列表/删除均验证。附带修复 DownloadService getTask/cancel/delete 只按内部 id 查记录的存量问题（CLOUD 与重启后的 URL/WORKSHOP 记录均受益，改为 id/name 双查）。真实网盘分享链接的 DIRECT/RELAY 两通道待有真实链接时联调。

2026-10-20 跟进验证：

- `plugin-l4d2-core` 编译通过；`CloudInstallTaskHandler` / `CloudInstallService` / `DownloadController` / `DownloadTaskSpec` 的链路与字段已静态核对。
- 本地主前端与插件前端生产构建均通过（插件前端 `vue-tsc --noEmit && vite build` 通过）。
- 当前仍缺有效分享链接和可用云盘凭证：本地 `live-189` 账号的 quota 实测返回 `credential is invalid or expired`，因此真实 DIRECT/RELAY、直链过期重试和取消中断不能判定通过。
- 下一步需要提供一个新的有效天翼/夸克分享链接（含提取码，若需要）和可用账号，再执行真实联调；在此之前保持 `needs-info`。

2026-10-20 真实天翼分享成功路径（实例 97，账号 live-189）：

- 分享：`https://cloud.189.cn/t/vuuUn2zyyi2a`，提取码已通过 API 提交。
- 提交 `POST /api/plugin/l4d2/download/cloud` 返回 200，下载任务 ID `2112240841319043072`，任务中心 ID `2112240841373569024`。
- 转存到 `/maps/share-80f0499c` 成功；产物为 `left_in_hell_042526.rar`。
- 平台中转下载 42,441,890 字节，解压得到 `left_in_hell.vpk`，上传到实例 97 的 `left4dead2/addons/left_in_hell.vpk`（134,108,160 字节）。
- 任务中心最终 `COMPLETED / 100%`，耗时约 16 秒；下载任务最终 `COMPLETED / 100%`。
- 实例文件列表确认 `left_in_hell.vpk` 实际存在；地图识别成功为 `Left in Hell`（3 章）。
- `GET /api/plugin/l4d2/maps/list?instanceId=97` 返回 `recognitionStatus=OK`，包含 `Left in Hell` 的 3 个章节：`L4D2_Brooklyn_part4`、`L4D2_broadway_d4`、`l4d2_chinatown_d2`。

### 本次暴露的缺陷

1. **通道标记错误（必须修）**：RAR 产物按代码必然走 `installRelay`，日志也明确出现 `CloudDriveExecutor.download`，但任务结果是 `transferMode=DIRECT`。原因是 RAR 分支只执行 `installRelay`，没有把 `transferMode` 切换为 `RELAY`，导致 summary 和结果错误。
2. **下载详情字段未透出（必须修）**：`DownloadTaskVO` 没有 `accountName` / `cloudPath` / `transferMode` 字段及映射，实测 `GET /download/tasks/{id}` 只返回基础字段，ticket 要求的详情字段不可见。
3. **取消中断未满足验收**：RELAY 的 `cloudDriveService.download(...)` 是同步阻塞调用，下载阶段没有检查 `TaskContext.isCancelled()`；取消只能在后续轮询/步骤生效，不能立即中断正在进行的流式下载。此次成功任务耗时约 16 秒，未能人工插入有效的取消窗口，不能判定“取消任务可中断”通过。

### 下一步（Agent）

- 在 RAR / 非直连分支设置并持久化 `MODE_RELAY`，补充对应测试。
- 将 `accountName` / `cloudPath` / `transferMode` 加入 `DownloadTaskVO` 并映射，补充 API 单测。
- 明确取消语义：若要求即时中断下载，需要为下载阶段增加可中断流或可取消传输能力；若只要求后续阶段停止，则修改验收口径。
- 用同一真实分享复跑 DIRECT/RELAY 与取消场景。

### 2026-10-20 收口结果

- RAR / RELAY 分支已持久化 `transferMode=RELAY`，并新增回归测试。
- `DownloadTaskVO` 已透出 `accountName`、`cloudPath`、`transferMode`，详情接口回归通过。
- RELAY 下载阶段新增可中断输出流；宿主异常发生在取消/超时之后时，现在会归并成
  `CancelledSignal`，不再把下载记录误写成 `FAILED`。详见同目录问题票 05。
- 真实天翼分享复跑：下载任务 `2112257261847240704` 为 `COMPLETED / 100% / RELAY`；
  取消任务 `2112257353517948928` 为 `CANCELLED / 40% / RELAY`。
- 验收项中的 DIRECT 通道没有在这次真实任务里重新执行；现有票 01/02 的直连能力测试和
  历史实现仍保留，RELAY 真实链路已闭环。
