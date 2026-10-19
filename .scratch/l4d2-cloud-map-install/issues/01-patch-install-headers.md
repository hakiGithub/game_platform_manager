# 01: 主应用 PatchInstall 支持 headers（主机直连下载）

Status: resolved

## 任务

- `PatchInstallRequest` 增加 `Map<String,String> headers` 字段（可空；非空时仅远程下载路径生效）。
- `PatchInstallExecutor.remoteDownload`：
  - 物理机路径：headers 非空时先 SFTP 写临时文件（如 `/tmp/.gp-hdr-{taskId}`，权限 0600），curl 用 `--header @file`（wget 不支持等价物时仅走 curl；无 curl 有 wget 时视为不支持 headers → 任务失败并带明确原因，由插件转中转）；下载完成 finally 删除临时文件。
  - Docker 工具镜像路径：同样 `--header @file`（文件先落到主机再挂载进容器，或 `docker run -v` 挂载临时文件）。
  - 远程 curl 版本探测（`curl --version` ≥7.55）不支持 @file → 失败原因带 `UNSUPPORTED_HEADER_FILE`。
- sha256 校验、决策树、解压逻辑不动。
- 单测：headers 拼装、临时文件清理、不支持的 curl 版本报错。

Blocked by: （无）

## Answer

- `PatchInstallRequest` 已增加可选 `headers`；平台下载路径携带 headers 时会以 `UNSUPPORTED_HEADER_FILE` 明确失败，不静默丢失请求头。
- 物理机和 Docker 工具镜像均通过临时 header 文件配合 `curl --header @file` 下载；上传后执行 `chmod 600`，下载结束清理远端与平台侧临时文件。主机 curl 低于 7.55 或不支持 `@file` 时返回 `UNSUPPORTED_HEADER_FILE`，由插件回退 RELAY。
- 2026-10-20 定向测试通过：`PatchInstallHeaderDownloadTest` 2/2、`PatchInstallExecutorCommandShapeTest` 3/3；headers 下载与相关 cloud-drive 用例合计 18/18 通过。
- 新增测试验证本地 HTTP 服务收到 header 内容、上传的临时文件在下载结束后删除、`chmod 600` 命令实际发出、旧 curl 版本被拒绝。Docker 路径的现有命令形状测试通过；本轮没有连接真实 Docker 主机做端到端下载。
