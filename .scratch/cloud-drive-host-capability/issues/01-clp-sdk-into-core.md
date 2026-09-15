# 01: clp-sdk 及全 Provider 依赖进 core

Status: ready-for-human

## 任务

- core `pom.xml` 引入 `com.haki.clouddrive:clp-sdk:0.1.0` 及全部 provider 模块（clp-provider-aliyun / cloud189 / xunlei；baidu/quark 随 sdk 传递）。
- OkHttp 为新依赖，Jackson 版本与主应用对齐（必要时 dependencyManagement 收口）。
- 在 core 定义 `CloudDriveClient` Bean：进程单例、`AutoCloseable`（`@Bean(destroyMethod="close")`），不配置 clp-storage-jdbc / 索引。
- 构建说明：`backend` README 或构建脚本注释中写明前置 `cd cloud_list_platform && mvn -pl clp-sdk -am install`。

## 验收

- `mvn clean compile` 通过（本地仓库已装 clp-sdk 前提下）。
- 应用启动无 provider ServiceLoader 缺失告警，`client.getProviders()` 返回 6 个 providerType。

Blocked by: （无）
