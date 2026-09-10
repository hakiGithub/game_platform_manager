# 01 — 备份异步执行器不推进，备份功能不可用

**Status:** wontfix（裁决：备份功能临时下线，不修复——2026-09-10）

> **处置记录**：备份还原 tab 已从实例详情页摘除（`detail.vue` tabs 数组与 el-tab-pane 块），
> 对应 E2E 用例随之移除；脚本/后端代码保持 dormant，恢复功能时还原 tab 块并按下方根因修复。

## 现象

创建备份接口成功（记录落库、前端提示"备份任务已创建"），但备份记录状态**永远停在 0（备份中）**，10 分钟无进展、无终态、无 error_message。备份/还原功能实质不可用。

## 根因（两层叠加）

1. **`@Async` 不生效**：`BackupServiceImpl.performFileBackupAsync` 为 `@Async protected` 且由同类 `createFileBackup` 直接调用——Spring AOP 代理不拦截 protected 方法，且自调用完全绕过代理，异步执行路径从未启动。
2. **Docker 实例源路径不存在**：`createFileBackup` 兜底使用元数据 `installPath`，而 Docker 部署的服务端文件在容器/卷内，宿主机路径 `~/games/...` 为空目录——即使执行也会失败。

## 复现证据

- E2E：`frontend/e2e/main-app/instance/lifecycle.spec.js` 备份用例，创建成功后状态轮询 10 分钟卡 0（票 06 / 票 09 整轮）。
- 代码：`backend/core/src/main/java/com/gameplatform/service/impl/BackupServiceImpl.java`。

## 修复建议

1. 异步执行拆到独立 Bean（`BackupExecutor`），方法 public + `@Async`，经代理调用；或改用任务中心队列（TaskHandler 模式，与 map-upload 一致）。
2. 执行路径按部署类型解析：Docker 实例经 `docker exec` 或容器卷路径取文件；失败时 `handleBackupFailure` 落终态（status=2 + error_message），**任何路径不得无终态**。

## E2E 断言转正

`lifecycle.spec.js`：备份用例的"状态卡 0 → SKIP"块改回轮询 `status===1`（成功）；
"还原备份任务启动"用例解除 `record.status !== 1` 前置。跑 `npm run e2e:round` 验证。
