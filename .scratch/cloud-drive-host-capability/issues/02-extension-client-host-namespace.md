# 02: ExtensionClient 宿主化（保留命名空间 platform）

Status: resolved

## 任务

- core 主容器注册宿主 `ExtensionClient`：`new ExtensionClientImpl(jdbcTemplate, extensionRouter, "platform", ...)`（参照 `PluginSpringContextFactory` 的插件侧构造方式）。
- 定义 `CloudAccountResource` 模型：`@ExtensionModel(strategy = SHARED, group = "platform", kind = "cloud_account")`，落既有 `extensions` 表。spec 字段建议：`providerType`、`displayName`、`credential`（AES 密文）、`credentialHint`（脱敏尾号）、`status`（HEALTHY/UNHEALTHY/DISABLED）、`remark`；`name` 为唯一业务标识。
- 凭证加解密复用主应用现有 AES 工具/密钥体系（core 内公开 Bean 或工具类）。
- 在 ADR-0002 正文中补一行指向 ADR-0024（宿主共享表边界扩展）。
- 防冲突：文档/校验说明保留名 `platform`，真实插件不得取该 ID（可加启动期告警日志即可，不做强拦截）。

## 验收

- 宿主 ExtensionClient 可对 `CloudAccountResource` 做 CRUD，数据落 `extensions` 表（group_name='platform'）。
- 插件侧 ExtensionClient 查不到 group='platform' 的数据（隔离验证）。
- spec 中 credential 为密文。

Blocked by: （无）

## Answer

- `CloudDriveConfiguration.hostExtensionClient()` 以保留命名空间 `platform` 构造主容器 `ExtensionClient`；`CloudAccountResource` 使用 `@ExtensionModel(strategy = SHARED, group = "platform", kind = "cloud_account")`，落既有 `extensions` 表。
- `CloudAccountService` 通过 `AesUtil.encrypt` 保存 `settingsCipher`，列表 VO 只暴露 `credentialHint`；2026-10-20 管理员实机 `GET /api/cloud/accounts` 确认无 `settingsCipher`。
- `CloudAccountServiceTest.createStoresCipherAndHintAndMountsDefaultPath` 覆盖密文不等于明文、脱敏尾号与 `/{providerType}/{name}` 挂载；`CloudAccountServiceInitializationOrderTest` 覆盖启动顺序，14 项相关核心测试全部通过。
- 插件侧隔离由 `ExtensionClientImpl` 的 pluginId 绑定保证；本轮未构造独立测试插件做双向 CRUD，保留为后续回归项。
