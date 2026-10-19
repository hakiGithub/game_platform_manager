# 03: CloudDriveService SDK 接口 + 账号 REST

Status: done

## 任务

- plugin SDK 模块定义 `CloudDriveService` 接口，core 实现并注册给插件可调用（照 `RconService` 的暴露模式，含调用方审计）：
  - `list(accountName, path, refresh)` → ListResult
  - `link(accountName, path)` → 直链（url 与 headers 连带语义保留在返回类型中）
  - `download(accountName, path, OutputStream)`
  - `transfer(accountName, shareUrl, targetPath, Duration timeout)` → 同步阻塞，返回转存后目标路径；超时抛错并保留 jobId，底层作业可能继续；默认超时 10 分钟做服务参数
  - 账号 → 凭证/mount 解析：默认挂载 `/{providerType}/{accountName}/`，由宿主隐式派生
- 账号 REST（core，管理员权限）：CRUD、`POST /accounts/{name}/verify`、`GET /accounts/{name}/quota`；任何响应不回显明文凭证（仅 credentialHint）。
- 转存/列目录/直链/下载**不出 REST**，只走 SDK。

## 验收

- 以真实夸克/百度账号走通 verify → list → transfer（同步）→ download 全链路。
- 超时场景：接口抛超时异常并保留 jobId；远端 Job 取消能力见独立票据 06。
- 非 admin 调账号 REST 返回 403；响应无明文凭证。

Blocked by: 01, 02

## Comments

2026-09-15 实现完成：T01–T04 全部落地并验证（后端 `mvn compile`/`install` 通过、启动冒烟 8 provider 加载、前端 `npm run build` 通过）。两点实现口径偏离 ticket 原文：
1. 权限：现有体系仅 JWT 认证无角色细分，账号 REST 按"登录即可 + 不回显明文"实现；引入角色体系时再收紧。
2. 转存超时：clp-sdk 无取消作业 API，超时语义为"抛错并附 jobId，底层作业后台继续"（ADR-0024 已注明的尽力取消不可行）。

2026-10-20 跟进验证（本地 3002 全栈）：

- `CloudDriveService` 已暴露 list / link / download / 带 passcode 的 transfer；`CloudDriveController` 的 `/cloud/**` 由 `SecurityConfig` 收紧为 `hasRole("ADMIN")`，非 admin 应返回 403（源码核对；未构造非 admin 实机账号）。
- 管理员 `GET /api/cloud/accounts` 返回 200，账号 `live-189` 状态为 `HEALTHY`，响应只有 `credentialHint`、没有 `settingsCipher`，明文凭证不回显通过。
- 同一账号 `GET /api/cloud/accounts/live-189/quota` 返回业务码 400：`查询配额失败: credential is invalid or expired (provider=cloud189)`。因此“真实账号 verify → list → transfer → download 全链路通过”仍无法确认；当前健康状态不能替代有效的网盘凭证。
- “超时后底层 Job 被取消”与实现契约不符：clp-sdk 无取消作业 API，`CloudDriveExecutor.transfer` 超时只抛错并保留 `jobId`，底层作业继续。该验收项应视为记录在案的实现约束，而不是通过。
- 后端相关定向测试通过 18/18（`CloudAccountServiceTest` 10、初始化顺序 1、PatchInstall 命令形态 3、`PatchInstallHeaderDownloadTest` 2、`CloudDriveExecutorTransferTest` 2）；`plugin-l4d2-core` 编译与主前端生产构建通过。

### 待补充

- 明确接受 clp-sdk 无法取消 Job 的契约，或将“超时取消”改为 SDK 能力票；不接受时应保持本票未完成。
- 构造非 admin 用户或 API Token，实机确认 `/api/cloud/**` 返回 403；当前仅完成源码核对。

2026-10-20 真实天翼账号复核（Cookie 已由用户提供，未落盘到 ticket）：

- `PUT /api/cloud/accounts/live-189/credential` 返回 200，账号状态 `HEALTHY`，凭证提示 `****AD54`。
- `POST /api/cloud/accounts/live-189/verify` 返回 200 / `HEALTHY`。
- `GET /api/cloud/accounts/live-189/quota` 返回 200：`totalBytes=32212254720`，`usedBytes=1208825096`。
- `GET /api/cloud/accounts/live-189/folders?path=/` 返回 200，可列出 11 个根目录；`path=/maps` 可列出 3 个子目录。
- 账号 REST 响应仍未回显明文凭证。

### 新发现

- 目录浏览/起始目录输入存在 ID 与路径语义混用，已拆分为独立票：
  `.scratch/cloud-drive-host-capability/issues/05-cloud189-folder-id-path-addressing.md`。
  该票据需要决定 SDK 的 ID→路径契约，不能在本票里用字符串拼接临时绕过。
- “超时取消底层 Job”仍是 clp-sdk 能力边界；当前实现会抛超时并保留 jobId，底层作业继续。该项需按实现契约接受或拆分 SDK 能力票。

### 验收结论

- 真实账号 verify / quota / 目录列表：已通过。
- 真实分享的 transfer / list / download：由 ticket 03（L4D2 云盘安装）成功链路覆盖，见 `.scratch/l4d2-cloud-map-install/issues/03-cloud-install-task.md`。
- 非 admin 实机 403 与 Job 取消语义：仍未验收，保持本票为可执行状态而不是完成。

### 2026-10-20 收口结果

- 真实天翼账号 verify / quota 已通过，真实 transfer / list / download 已由 L4D2 云盘安装
  真实链路覆盖，详见 `.scratch/l4d2-cloud-map-install/issues/03-cloud-install-task.md`。
- 非 admin 请求 `/api/cloud/accounts` 已实机返回 403，吊销临时令牌后返回 401；
  临时令牌已吊销，账号 REST 响应不回显明文凭证。
- clp-sdk 无取消 Job API，ADR-0024 已改为“超时抛错并返回 jobId，底层作业可能继续”，
  不再把远端取消写成已交付语义；后续增强拆到票据 06。
- 目录/起始目录 ID 与路径语义问题拆到票据 05，需 human 决定 SDK 边界。
