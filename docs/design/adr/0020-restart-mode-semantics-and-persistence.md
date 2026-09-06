# ADR-0020: 重启模式语义收敛与重启偏好持久化

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-09-06 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（插件扩展资源自管）、[ADR-0016](0016-rcon-host-capability.md)（RCON 宿主能力） |
| Supersedes | 无 |

## 背景（Context）

L4D2 插件重启能力存在三个叠加缺陷（2026-09-06 用户报告"已更改重启方式但重启仍用 AUTO"）：

1. **配置不持久化**：重启配置（`L4D2Config.Restart.byRcon` 等）是纯内存 `@ConfigurationProperties` Bean，无任何落盘。插件热重载或主应用重启后回退代码默认值（`byRcon=false`），用户"保存成功"是假象。
2. **按钮语义错位**：重启页「重启服务器」按钮硬编码发 `mode:'AUTO'`，用户改的「重启方式」单选实际只是 AUTO 内部分流依据（`byRcon`）。请求层面永远是 AUTO，行为不可预期；且 UI 词「重启方式」同时指代 byRcon 布尔与 AUTO/RCON/COMMAND 枚举两个概念。
3. **入口不一致**：插件 Dashboard 快捷重启与主应用实例页重启走 `DockerAdapter.restart`，完全绕过该配置；保存链路还有 `commandTimeoutMs`/`enabled` 字段被 DTO 静默丢弃的缺陷。

## 决策（Decision）

### 决策 1：重启模式语义收敛（术语见 CONTEXT.md「实例重启领域」）

- **重启模式**三值：RCON（经游戏协议通道重启）、COMMAND（主机命令/容器重启）、AUTO（不显式指定，按重启偏好分流）。
- **用户显式发起重启时，按钮直接发 RCON 或 COMMAND**（按「重启偏好」单选推导），AUTO 从主按钮退场；AUTO 仅保留为 API 兼容与"按配置执行"类入口（Dashboard）的语义。
- **RCON 重启失败不降级**为命令重启：直接报错并返回失败原因，提示可切换命令模式。自动降级会掩盖 RCON 配置问题，且容器重启是更重的动作，应由用户显式选择。

### 决策 2：重启偏好经 ExtensionClient 持久化（PLUGIN_ISOLATED）

- 存储级别选 `Strategy.PLUGIN_ISOLATED`（`ext_{pluginId}` 插件级隔离表），模型：单条全局记录（name 固定业务标识）。
- **插件全局一份**，非每实例：现状语义即全局（byRcon 是插件级 Bean），个人运维场景下不同实例共用一份偏好可接受；改为每实例需连带动容器解析、UI 均动，等真实需求出现再做。
- 启动时加载：扩展存储无记录则沿用 `@ConfigurationProperties` 默认值；保存即落库并同步内存 Bean。

### 决策 3：保存链路字段补全

`commandTimeoutMs` 与 `enabled` 纳入 `RestartConfigUpdateDTO`/`RestartConfigVO`/`setConfig`，修复静默丢弃（页面每次加载超时被重置 30000 的问题随之消除）。

### 决策 4：重启入口归一

- 插件 Dashboard 快捷重启改走插件重启接口（`mode:'AUTO'`，后端按持久化偏好分流）——插件域内入口行为一致。
- **主应用实例页重启保持宿主通用 `docker restart` 不动**：那是宿主对任意游戏的通用语义，不应感知单个插件的重启模式；差异在文档说明。

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 持久化到实例 configInfo | 每实例存偏好键 | 违反 ADR-0002 边界（插件键不进主应用标准配置）；偏好本身是全局语义 |
| 持久化到插件配置文件 | 运行期写 yml | 与 @ConfigurationProperties 只读惯例冲突，热重载写文件复杂易腐 |
| 维持内存 Bean | 不持久化 | 本次问题根源，直接否决 |
| RCON 失败自动降级命令重启 | 可用性优先 | 掩盖 RCON 配置缺陷；容器重启副作用大，须显式选择 |
| 主按钮三选一（含 AUTO） | 模式全暴露 | 多一个无信息量选项；显式 RCON/COMMAND 已覆盖全部用户意图 |
