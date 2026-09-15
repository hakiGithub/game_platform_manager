# 01: 主应用 PatchInstall 支持 headers（主机直连下载）

Status: ready-for-human

## 任务

- `PatchInstallRequest` 增加 `Map<String,String> headers` 字段（可空；非空时仅远程下载路径生效）。
- `PatchInstallExecutor.remoteDownload`：
  - 物理机路径：headers 非空时先 SFTP 写临时文件（如 `/tmp/.gp-hdr-{taskId}`，权限 0600），curl 用 `--header @file`（wget 不支持等价物时仅走 curl；无 curl 有 wget 时视为不支持 headers → 任务失败并带明确原因，由插件转中转）；下载完成 finally 删除临时文件。
  - Docker 工具镜像路径：同样 `--header @file`（文件先落到主机再挂载进容器，或 `docker run -v` 挂载临时文件）。
  - 远程 curl 版本探测（`curl --version` ≥7.55）不支持 @file → 失败原因带 `UNSUPPORTED_HEADER_FILE`。
- sha256 校验、决策树、解压逻辑不动。
- 单测：headers 拼装、临时文件清理、不支持的 curl 版本报错。

Blocked by: （无）
