# 云盘能力上提为主应用宿主服务（clp-sdk 嵌入）

- 决策依据：[ADR-0024](../../docs/design/adr/0024-cloud-drive-host-capability.md)
- 术语：CONTEXT.md「云盘能力领域（ADR-0024）」
- 状态：已 grilling 定稿（2026-09-15）

## 目标

主应用 core 进程内嵌入本地项目 `cloud_list_platform` 的 `clp-sdk`，提供多云盘（baidu/quark/aliyun/cloud189/xunlei/openlist）宿主能力：列目录、直链、流式下载、同步转存。为后续 l4d2 地图中心"一键转存后下载"提供地基（该编排不在本 spec 范围）。

## 关键决策（摘要）

1. 传输层归 core，语义层归插件（复刻 ADR-0016 RCON 模式）；plugin SDK 暴露 `CloudDriveService`。
2. 不引入 clp-storage-jdbc（索引/搜索/订阅排除）；不用 SDK 异步 TransferJob。
3. ExtensionClient 宿主化：core 以保留 pluginId `"platform"` 使用既有 `extensions` 共享表，云盘账号 `@ExtensionModel(strategy=SHARED, group="platform", kind="cloud_account")`，**零新增表**。
4. 账号 REST 三件套（CRUD / verify / quota），管理员权限；凭证用主应用现有 AES 加密落 spec，接口不回显明文（仅脱敏尾号）。
5. 主前端新页 `/system/accounts`，菜单"云盘账号"，挂系统设置组；Header"账号与安全"占位不动。
6. 默认挂载点 `/{providerType}/{accountName}/` 由宿主隐式派生，调用方以账号 name + 相对路径寻址。
7. 同步转存：`transfer(account, shareUrl, targetPath, timeout)`，默认超时 10 分钟（服务参数），超时尽力取消底层 Job，完成返回目标路径。
8. 构建前置：`cd cloud_list_platform && mvn -pl clp-sdk -am install`（clp-sdk 0.1.0 未发布仓库，需写入构建脚本说明）。

## Tickets

见 `issues/`，依赖关系以 `Blocked by` 行表达。
