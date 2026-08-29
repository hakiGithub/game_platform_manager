# ADR-0016: RCON 能力上提为主应用宿主服务

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-29 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离规约）、[ADR-0003](0003-deprecate-plugin-l4d2-standalone.md) |
| Supersedes | 无（AGENTS.md 中 RCON 三层架构描述的归属由本 ADR 修正） |

## 背景（Context）

RCON（Source RCON 协议）是大部分游戏服务器通用的远程控制协议。当前完整实现——协议编解码（`RconProtocol`）、端点解析（`RconConnectionResolver`）、连接池（`RconConnectionManager`）、业务服务（`RconService`）——全部位于 plugin-l4d2 插件内（`backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/` 与 `.../service/RconService.java`），主应用（core/api/plugin）没有任何 RCON 代码。

这导致：其他新插件若需 RCON 能力只能复制实现；RCON 密码的读取与连接管理分散在插件侧，无统一审计；连接池参数挂在 `L4D2Config.Rcon` 下（且 `defaultPort=27020` 与 resolver 兜底 `27015` 不一致）。

## 决策（Decision）

将 RCON 能力整体上提为主应用宿主能力服务，plugin-l4d2 删除自有 RCON 实现并改为注入调用。经 grill-with-docs session 收敛为以下条目：

### 决策 1：整体迁移，不留双实现

`rcon/` 包（RconProtocol、RconEndpoint、RconConnectionManager、RconConnectionResolver）整体上提到主应用 core；l4d2 删除全部自有 RCON 代码，改为依赖 SDK 接口。`RconFailureDetector` 属 SourceMod 语义（失败标记匹配），留在插件 util 包不迁移。不保留过渡期共存——两套连接池、两份协议实现比最终态更难验证。

### 决策 2：主应用只做传输层

主应用 RCON 服务只提供：协议编解码、连接建立/认证、连接池、按实例的端点解析、`executeCommand` 同步执行。所有游戏语义（`status` 输出解析、地图列表、kick/ban 语法、难度/模式切换）留在插件——同一命令在不同游戏上语义不同，语义层永远不上提。

### 决策 3：暴露形态 = SDK 接口 + 主应用 REST

- SDK 接口 `RconService`（`com.gameplatform.plugin.service` 包），经 `PluginSpringContextFactory` 注册进插件子容器，方法面最小集：

```java
String executeCommand(long instanceId, String command);
String executeCommand(long instanceId, String command, Duration timeout);
boolean testConnection(long instanceId);
```

  错误一律抛业务异常（复用全局异常处理），不做批量、不做结构化 Result——现有消费方只用单命令执行。
- 主应用自身暴露薄 REST：`POST /api/instances/{id}/rcon/execute`（body `{command}`）与 `GET /api/instances/{id}/rcon/ping`，让运维无需插件即可验证 RCON 连通性。

### 决策 4：端点解析只认标准键

主应用的端点解析契约只认实例 `configInfo` 中的标准键：`rconPort`（缺省 **27015**）与 `rconPassword`。游戏专属键名（`L4D2_RCON_PASSWORD`、`SRCDS_RCONPW` 等）由部署适配器在部署时归一化写入标准键，或由插件在调用前补齐；专属回退链留在插件侧。`defaultPort` 统一为 27015，消除 27020/27015 双默认值。

### 决策 5：连接池归主应用，按实例生命周期失效

连接池（按 instanceId 缓存、保活、空闲回收、同实例串行）由主应用持有。实例删除/状态变化时失效对应连接；插件卸载**不**清连接（连接按 instanceId 不按 pluginId，天然与插件解耦，不复刻 `purgeTasks` 语义）。池参数迁至主应用 `rcon.*` 配置前缀（如 `rcon.pool.idle-timeout-seconds`），默认值沿用现值；`L4D2Config.Rcon` 整个删除。

### 决策 6：统一审计与调用方标识

每次 RCON 命令执行自动携带调用方标识（插件 ID 或 "main-app"），由主应用记录审计日志（成功与失败均记录，专用 logger `RCON_AUDIT` 供日志采集侧按名称归档；当前仓库无操作审计表，落库审计留给未来）。调用方无法伪造或省略来源——这是"统一管理"的核心价值之一。

### 决策 7：插件 REST 路径不变

plugin-l4d2 的 `/api/plugin/l4d2/rcon/*` REST 路径保持不变，Controller 留在插件，内部改调宿主服务。前端零改动。

## 后果（Consequences）

### 正面

- 任何插件经 SDK 即得 RCON 能力，不再复制实现
- RCON 密码读取与连接管理统一收口，命令执行带不可伪造的调用方审计
- 主应用 REST 提供插件无关的连通性验证入口，降低迁移期排障成本
- 消除 defaultPort 双默认值不一致

### 负面

- 主应用 core 新增 RCON 模块与 `rcon.*` 配置，主应用表面积增大
- RconService/REST 是一次迁移完成的，主应用会短暂存在少量直接消费方，契约变更需同时照顾两类调用方

### 中性

- 迁移是一次到位的单次变更（主应用三件套 + PluginSpringContextFactory 注册 + l4d2 删旧代码 + 测试改造），用 `rebuild-restart-all.ps1` 全量验证

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 主应用重写而非上提 | 主应用另写一套 RCON 客户端，l4d2 保留旧实现 | 双实现共存，两套连接池两份 bug |
| 仅 REST 暴露 | 插件走 HTTP 调主应用 | 绕开 Spring 子容器注入机制，插件要处理认证与序列化，SDK 接口才是既定正道 |
| 回退链写死在主应用 | 保留 `L4D2_RCON_PASSWORD` 等专属键回退 | 主应用感知具体游戏的配置键，违反 ADR-0002 精神 |
| 游戏元数据声明 RCON 能力 | `games/{gameCode}.yml` 加 `rcon.protocol` 等字段 | 当前无主应用侧消费者，加了就是无消费者的契约；记入未来方向 |

## 未来方向

- 主应用做通用 RCON 控制台页面时，在 `games/{gameCode}.yml` 增加能力声明字段（如 `rcon.protocol: source`、`rcon.defaultPort`）
- 若出现批量命令/流式输出需求，再扩展 SDK 方法面
- 审计从 logger 升级为落库（接入操作审计体系）

## 迁移记录

| 日期 | 操作 | 提交 |
|------|------|------|
| 2026-08-29 | 传输层四类上提 core `com.gameplatform.rcon`；SDK 新增 `RconService` 接口并注册进插件子容器；主应用 REST `/instances/{id}/rcon/execute|ping`；池参数迁 `rcon.*`；`L4D2Config.Rcon` 删除；插件改名 `L4D2RconService` 委托宿主服务；删除插件 `/rcon/diag` 调试端点（含硬编码默认密码，前端无引用） | 本提交 |
| 2026-08-29 | 存量数据归一化：`V1.8__normalize_rcon_config_keys.sql` 把 `configInfo` 中仅存专属键（`L4D2_RCON_PASSWORD`/`SRCDS_RCONPW`）的实例密码写入标准键 `rconPassword`（经查实存量实例 56 仅存专属键，不做迁移会打断现有实例） | 本提交 |
