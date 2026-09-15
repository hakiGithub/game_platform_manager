# ADR-0024: 云盘能力上提为主应用宿主服务（clp-sdk 嵌入）

- 状态：Accepted
- 日期：2026-09-15
- 关联：[ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离，本 ADR 扩展其 Extension 存储边界）、[ADR-0016](0016-rcon-host-capability.md)（同类宿主能力先例）

## Context

L4D2 地图中心需要"一键转存后下载"能力：从网盘分享链接（夸克/百度/阿里/天翼/迅雷/OpenList）把地图资源转存到平台托管的网盘账号，再下载到游戏主机。本地姊妹项目 `cloud_list_platform` 提供零 Spring 的 `clp-sdk`（`com.haki.clouddrive:clp-sdk:0.1.0`，本地 `mvn install` 依赖），封装多 Provider 凭证、分享转存、列目录、直链与流式下载。

设计约束：

- 云盘账号（含 Cookie 等敏感凭证）是平台级资产，非 L4D2 专属；账号管理页放主前端，不出插件。
- 不新增数据库表。
- 转存不使用 SDK 的异步 TransferJob 体系，由业务方（插件）以同步语义实现自己的转存+下载编排。
- clp-sdk 索引/搜索/订阅能力不引入（避免 `clp-storage-jdbc` 建索引表）。

## Decision

1. **云盘传输层归主应用 core**：core 嵌入 `clp-sdk`（进程内一实例，`AutoCloseable` 生命周期随应用），依赖 clp-sdk 及全部 provider 模块（baidu/quark/aliyun/cloud189/xunlei/openlist）。plugin SDK 暴露 `CloudDriveService` 编程接口，形态复刻 RCON 的传输层/语义层切分（ADR-0016）：宿主只认"凭证 + 路径 + 操作"，不理解任何游戏/地图语义。不引入索引、搜索、订阅。

2. **ExtensionClient 宿主化（保留命名空间 `platform`）**：core 以保留 pluginId `"platform"` 实例化 `ExtensionClientImpl`，云盘账号模型声明 `@ExtensionModel(strategy = SHARED, group = "platform", kind = "cloud_account")`，数据落主应用启动时自动创建的既有 `extensions` 共享表——**不新增任何表**。插件绑定的 pluginId 不同于 `platform`，按 group_name 过滤天然互相不可见，隔离双向成立。ADR-0002 的"core 不含插件表"不受影响：`extensions` 是宿主共享表，插件专属表规则不变。`platform` 保留名记入本 ADR 为约定，真实插件不得取该 ID。

3. **账号管理 REST + 主前端页面**：core 出管理员权限的账号三件套 REST（CRUD / `verify` 凭证验证 / `quota` 配额查询），凭证字段以主应用现有 AES 体系加密后落 spec，任何接口不回显明文（仅脱敏尾号）。页面挂 `/system/accounts`，菜单名"云盘账号"；Header 的"账号与安全"占位跳转本次不动（平台账号语义与云盘账号无关）。

4. **转存/列目录/直链/下载只走 SDK 编程接口，不出 REST**：这些是业务语义，归调用方插件。`CloudDriveService` 以账号 name + 相对路径寻址，账号默认挂载点为 `/{providerType}/{accountName}/`，由宿主隐式派生，不做独立挂载管理。

5. **同步转存契约**：`transfer(account, shareUrl, targetPath, timeout)` 阻塞至完成并返回转存后的目标路径，供业务方（如 l4d2 地图中心的转存后下载编排）直接续接下载；超时则尽力取消底层 Job（避免"调用方以为失败、网盘侧却成功"的脏状态），默认超时 10 分钟、做成服务参数。任务状态机、重试、进度展示一律由业务方实现（主应用任务中心扩展点照旧归插件 `TaskHandler`）。

6. **构建前置**：clp-sdk 0.1.0 未发布公共仓库，构建前需 `cd cloud_list_platform && mvn -pl clp-sdk -am install`；该步骤写入构建脚本说明。

## 增补（2026-09-15）：起始目录下拉选择

- **两段式交互**：SDK 目录浏览要求账号已存在（浏览即探活），新增账号时起始目录手填（默认整盘）；保存后在账号列表对该账号点「起始目录」→ 级联懒加载浏览（el-cascader + checkStrictly，任意层级可选，失败降级手输）→ 选中即生效。
- **取值语义由服务端决定**：新增 `GET /api/cloud/accounts/{name}/folders`（管理员），返回目录项带 `value`——rootPath 类 provider（quark/baidu/uc）= 路径，rootFolderId 类（cloud189/aliyun/xunlei）= 文件夹 ID（clp-web 同类端点丢 id 导致路径/ID 错位，不复刻）。root 字段按 provider schema 字段名识别（`rootPath`/`rootFolderId`）。
- **应用语义**：`POST /api/cloud/accounts/{name}/root-dir` = 更新 settings root 字段 + 重建默认挂载（rootPath=选中值）。路径命名空间随之切换，已转存产物需重新寻址——前端弹明确警告。
- **ID 兼容**：clp `createMount` 会把 rootPath 规范化加前导斜杠（"-13"→"/-13"），cloud189 client 对斜杠+纯数字串剥斜杠还原真实 ID（上游同日修复）。

## Consequences

- 主应用新增 OkHttp 依赖，Jackson 版本需与 clp-sdk 对齐；全 provider 依赖进入 core（parentFirst classloader），插件无需重复打包。
- 后续第二个插件需要云盘能力时直接复用 `CloudDriveService`，无需再上提。
- `extensions` 表首次承载宿主数据，ExtensionClient 语义从"插件唯一持久化入口"扩展为"插件与宿主共享的扩展存储入口（按 group 隔离）"。
- SDK 的订阅追更、跨盘搜索能力被明确排除，未来引入需重议存储（索引表与"不新增表"冲突）。
- l4d2 地图中心的一键转存后下载编排（TaskHandler、下载到主机路径等）不在本 ADR 范围，另行设计。

## Alternatives

- **落在 plugin-l4d2（fat jar 内嵌 SDK）**：被否——云盘账号是平台级资产、账号页要放主前端，且将来多插件复用。
- **独立部署 clp-web + REST 对接**：被否——个人运维场景多跑一个 :9090 进程过重，凭证/Job 状态还要跨进程对账。
- **引入 clp-storage-jdbc 索引**：被否——建索引表违反"不新增表"，当前无跨盘搜索需求。
- **使用 SDK 异步 TransferJob 体系**：被否——主应用已有任务中心语义，插件重启/热部署会取消任务，异步 Job 双轨增加状态对账负担；同步 + 超时取消 + 业务方自编排更简单。
