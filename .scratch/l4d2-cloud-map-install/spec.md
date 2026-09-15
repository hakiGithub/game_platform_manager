# L4D2 地图中心云盘转存安装

- 决策依据：[ADR-0025](../../docs/design/adr/0025-l4d2-cloud-map-install.md)
- 术语：CONTEXT.md「云盘地图安装领域（ADR-0025）」
- 状态：已 grilling 定稿（2026-09-15）

## 流程总览

```
分享链接(+提取码) ──► 转存到账号 /maps/{source}-{sourceId}（已存在则跳过，SDK Diff 去重）
                    ──► list 产物，筛选 .vpk（直装）/.zip（解压取 vpk）
                    ──► probeHost 判定通道：
                          DIRECT: PatchInstall + headers（curl --header @file，0600 临时文件，用完删；
                                  curl<7.55 或直链重试失败 → RELAY）
                          RELAY : cloudDriveService.download 流式 → VPK 校验 → uploadLocalFile
                    ──► 写 DownloadTaskResource（taskType=CLOUD，含 accountName/cloudPath/transferMode）
```

## 关键决策（摘要）

1. taskType=`cloud-install` 插件 TaskHandler，进度：转存 0-40 / 下载 40-80 / 上传 80-100。
2. 直连优先、中转兜底（ADR-0025 §2-3）；直链不缓存、失败重取一次再转中转（§4）。
3. MapResource 不回写状态；重复安装覆盖。
4. `CloudDriveService.transfer` 加 passcode 参数（直接改签名）。
5. 前端入口：MapCenter 详情"转存并安装" + Download 页"云盘分享链接"；账号下拉直调主应用 `/api/cloud/accounts`。
6. 与 URL/Workshop 下载共用 3 并发 Semaphore。
7. 主应用改动：`PatchInstallRequest.headers` + `remoteDownload`（含 Docker 工具镜像路径）`--header @file` 支持；决策树/hutool 路径不动。

## Tickets

见 `issues/`。
