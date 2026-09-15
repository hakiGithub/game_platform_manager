# 03: 插件 cloud-install 任务链路（转存→筛选→直连/中转→安装）

Status: ready-for-human

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
