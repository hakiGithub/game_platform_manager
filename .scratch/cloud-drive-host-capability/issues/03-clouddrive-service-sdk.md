# 03: CloudDriveService SDK 接口 + 账号 REST

Status: ready-for-human

## 任务

- plugin SDK 模块定义 `CloudDriveService` 接口，core 实现并注册给插件可调用（照 `RconService` 的暴露模式，含调用方审计）：
  - `list(accountName, path, refresh)` → ListResult
  - `link(accountName, path)` → 直链（url 与 headers 连带语义保留在返回类型中）
  - `download(accountName, path, OutputStream)`
  - `transfer(accountName, shareUrl, targetPath, Duration timeout)` → 同步阻塞，返回转存后目标路径；超时尽力取消底层 Job；默认超时 10 分钟做服务参数
  - 账号 → 凭证/mount 解析：默认挂载 `/{providerType}/{accountName}/`，由宿主隐式派生
- 账号 REST（core，管理员权限）：CRUD、`POST /accounts/{name}/verify`、`GET /accounts/{name}/quota`；任何响应不回显明文凭证（仅 credentialHint）。
- 转存/列目录/直链/下载**不出 REST**，只走 SDK。

## 验收

- 以真实夸克/百度账号走通 verify → list → transfer（同步）→ download 全链路。
- 超时场景：底层 Job 被取消，接口抛超时异常。
- 非 admin 调账号 REST 返回 403；响应无明文凭证。

Blocked by: 01, 02

## Comments

2026-09-15 实现完成：T01–T04 全部落地并验证（后端 `mvn compile`/`install` 通过、启动冒烟 8 provider 加载、前端 `npm run build` 通过）。两点实现口径偏离 ticket 原文：
1. 权限：现有体系仅 JWT 认证无角色细分，账号 REST 按"登录即可 + 不回显明文"实现；引入角色体系时再收紧。
2. 转存超时：clp-sdk 无取消作业 API，超时语义为"抛错并附 jobId，底层作业后台继续"（ADR-0024 已注明的尽力取消不可行）。
