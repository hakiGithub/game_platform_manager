# CONTEXT - 领域术语表

> 本文件只做术语表（ubiquitous language），不含实现细节。实现决策见 `docs/design/adr/`。

---

## RCON 领域

### RCON（远程控制台）
游戏服务器的远程命令协议通道。本平台指 **Source RCON 协议**（Valve 制定的 TCP 文本协议，被 Source 引擎及大量第三方游戏服务器采用）。协议实现归**主应用**，是通用传输能力。

### 传输层（Transport Layer）
主应用提供的 RCON 通用能力：协议编解码、连接建立/认证、连接池、按实例的端点解析。只认字节，不理解任何游戏命令语义。

### 语义层（Semantic Layer）
游戏命令的业务含义：`status` 输出解析、地图列表、kick/ban 语法、难度/模式切换等。归**插件**（如 plugin-l4d2）。同一命令在不同游戏上语义不同，因此永远不上提到主应用。

### RconEndpoint（RCON 端点）
一次 RCON 连接所需的三元组：主机地址、端口、密码。由端点解析器从实例与主机数据推导，不可由插件直接指定密码。

### 端点解析回退链（Endpoint Resolution Fallback）
从实例数据推导 RconEndpoint 的规则。主应用只认**标准键**：`configInfo.rconPort`（缺省 27015）与 `configInfo.rconPassword`；游戏专属键名（如 `L4D2_RCON_PASSWORD`、`SRCDS_RCONPW`）由部署适配器在部署时归一化写入标准键，或由插件在调用前补齐。专属回退链不属于主应用契约。

### 连接池借用（Connection Borrow）
调用方通过主应用 RCON 服务借用一个已认证连接执行命令，用毕归还。同实例的借用是串行的；池参数（空闲回收、保活、借用超时）由主应用统一配置。

### 调用方标识（Caller Identity）
每次 RCON 命令执行自动携带的来源信息（插件 ID 或 "main-app"），用于统一审计。调用方无法伪造或省略。

---

## 平台通用

### 宿主能力服务（Host Capability Service）
主应用通过插件 Spring 子容器注册给插件的单例服务接口（如 InstanceQueryService、HostQueryService、RconService）。插件只依赖 SDK 接口，不依赖主应用实现。

### 实例标准配置键（Instance Standard Config Keys）
实例表 `configInfo` JSON 中由主应用契约约定的键。RCON 相关为 `rconPort`、`rconPassword`。游戏专属键不算标准键。
