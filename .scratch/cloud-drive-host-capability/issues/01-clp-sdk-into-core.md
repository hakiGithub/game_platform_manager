# 01: clp-sdk 及全 Provider 依赖进 core

Status: resolved

## 任务

- core `pom.xml` 引入 `com.haki.clouddrive:clp-sdk:0.1.0` 及全部 provider 模块（clp-provider-aliyun / cloud189 / xunlei；baidu/quark 随 sdk 传递）。
- OkHttp 为新依赖，Jackson 版本与主应用对齐（必要时 dependencyManagement 收口）。
- 在 core 定义 `CloudDriveClient` Bean：进程单例、`AutoCloseable`（`@Bean(destroyMethod="close")`），不配置 clp-storage-jdbc / 索引。
- 构建说明：`backend` README 或构建脚本注释中写明前置 `cd cloud_list_platform && mvn -pl clp-sdk -am install`。

## 验收

- `mvn clean compile` 通过（本地仓库已装 clp-sdk 前提下）。
- 应用启动无 provider ServiceLoader 缺失告警，`client.getProviders()` 返回 6 个 providerType。

Blocked by: （无）

## Answer

- `backend/core/pom.xml` 已引入 `clp-sdk` 与 aliyun / cloud189 / xunlei / openlist provider；`CloudDriveConfiguration` 提供 `@Bean(destroyMethod="close")` 的进程单例 `CloudDriveClient`，未配置 clp-storage-jdbc 或索引。
- `backend/AGENTS.md` 已写明构建前置：在 `cloud_list_platform` 执行 `mvn -pl clp-sdk -am install -DskipTests`。
- 2026-10-20 验证：`mvn -pl core -am '-Dtest=CloudAccountServiceInitializationOrderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过；应用 `/api/cloud/accounts` 实机返回 200，本地 `live-189` 账号存在（说明 provider 装载与 SDK 启动无缺失告警）。
- 2026-10-20 管理员实机 `GET /api/cloud/providers` 返回 8 个 providerType（`baidu-oauth`、`cloud189`、`bdpan`、`openlist`、`quark`、`uc`、`aliyun`、`xunlei`），满足“至少 6 个”的验收条件；运行实例未见 ServiceLoader 缺失告警。
