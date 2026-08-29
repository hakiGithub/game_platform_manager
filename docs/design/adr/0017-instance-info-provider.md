# ADR-0017: 实例信息 Provider 扩展点（玩家数插件化查询）

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-29 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0016](0016-rcon-host-capability.md)（RCON 宿主能力，Provider 实现的基础设施）、[ADR-0002](0002-main-app-plugin-scope-isolation.md) |
| Supersedes | 无 |

## 背景（Context）

实例列表与详情都展示玩家数（`game_instance.online_players`），但该列没有定时刷新：只有被动调用 `GET /instances/{id}/status` 时才经部署适配器更新，列表页显示的几乎是陈旧值。各游戏插件实际掌握实时数据（如 plugin-l4d2 的 PlayerStatsService 每 10 分钟经 RCON 查 `status` 解析人数），但只写插件专属表，从不回写主应用。部署适配器统计口径（Docker/LGSM）各游戏不一，不适合作为主应用的通用玩家数来源。

## 决策（Decision）

新增通用实例信息扩展点，插件可按实例提供动态信息；主应用实时并发查询 + 短 TTL 缓存。经 grill-with-docs session 收敛为以下条目：

### 决策 1：通用实例信息扩展点（类型化 + 扩展袋）

SDK 新增独立扩展点接口（仿 `TaskHandlerExtension` 的扫描注册模式）：

```java
public interface InstanceInfoProvider {
    /** 查询实例动态信息；返回 null 表示本次不可知（降级默认值） */
    InstanceDynamicInfo getInstanceInfo(long instanceId);
}
public record InstanceDynamicInfo(Integer playerCount, Map<String, Object> extras) {}
```

- 插件 `@Component` 实现，主应用 `getBeansOfType` 扫描，按 gameCode(source) 注册进 Registry；插件卸载/热重载时 `unregisterBySource` 清理（复用 `TaskHandlerRegistry` 模式）
- `playerCount` 与 `maxPlayerCount` 是主应用消费的类型化契约字段（maxPlayerCount 允许 null 表示无法提供，不落库、降级不覆盖）；`extras` 开放扩展袋，主应用不解释、透传到实例详情 VO
- 字段演进路径：extras 透传先行，主应用按需升级为类型化字段——`maxPlayerCount` 已于 2026-08-29 按此路径升级（L4D2 status 的 "N max"），未来候选：`mapName`、`version`
- 不提供批量查询签名——并发调度是主应用的事，插件只管单实例

### 决策 2：实时查询 + 15 秒实例维度缓存

列表/详情接口实时触发查询，先过主应用持有的实例维度 TTL 缓存（**Guava Cache**，expireAfterWrite 15s），命中即复用、未命中才真正调 Provider。缓存窗口内不提供强制穿透入口。**Provider 返回 null（不可知）同样入缓存**（负缓存），避免窗口内对故障游戏服重复施压；插件卸载、实例停止/删除时失效对应条目（插件卸载/热重载时以 `invalidateAll()` 超集失效替代——source 无法精确反查实例集合，TTL 有界无害）。否决定时轮询回写方案（用户要求数据实时性优先于接口延迟恒定）。

### 决策 3：降级语义

三种情况降级：游戏无 Provider 实现、Provider 返回 null、查询超时/超预算。规则：RUNNING 实例用 `game_instance.online_players` 存量值，非 RUNNING 实例固定 0；降级**不覆盖**已落库的上次真实值。API 不引入 null 语义，前端零改动。

### 决策 4：并发与预算

- 专用小线程池（core 4 / max 8），不与 `taskExecutor`（任务中心）争抢
- 单实例查询超时 5s；**列表整体预算 3s**（`CompletableFuture` 收口）：预算内未完成的实例本次用降级默认值，已发起查询的结果仍会落库并进缓存（下一个请求命中）
- Provider 实现内部若走 RCON 自受 `RconConnectionManager` 同实例串行约束

### 决策 5：落库与唯一事实源

Provider 查询成功且 playerCount 与现值不同才写 `game_instance.online_players`（复用 `updateOnlinePlayers`，值变化才写避免无谓 UPDATE）。`extras` 不落库。该列是玩家数唯一事实源：`GET /instances/{id}/metrics` 不再用部署适配器的 `onlinePlayers` 覆盖玩家数。

### 决策 6：首个实现

plugin-l4d2 实装 `InstanceInfoProvider`（内部经宿主 `RconService` 执行 `status` 并复用现有人数正则），作为扩展点首个消费者与验证载体；其余插件（sdtd/dst/dnf-tw）不实现，自动走降级路径。

## 后果（Consequences）

### 正面

- 列表/详情玩家数实时（≤15s 窗口），修掉"列表永远陈旧"的现状
- 游戏数据归属插件（ADR-0002 精神），主应用不再依赖适配器统计口径
- extras 扩展袋让"实例动态信息"可增量演进，不必每加一个字段就动扩展点契约
- 无 Provider 的游戏行为不变（DB 存量值），渐进采纳

### 负面

- 列表接口最坏延迟 ≈ 查询预算 3s（缓存全失效且 Provider 全慢时）；缓解：15s 缓存 + 超预算结果由异步回调补缓存/落库（仅当请求线程已放弃等待时补写，正常路径由请求线程处理）
- core 新增 Guava Cache 依赖（当前 pom 无 guava，需引入）
- extras 契约松散：主应用不校验 extras 内容，字段质量由插件自负

### 中性

- 缓存 TTL（15s）、预算（3s）、单实例超时（5s）以配置暴露（`instance-info.*` 前缀），默认值即上述数值

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 后台定时轮询回写 | 每 60s 并发轮询 RUNNING 实例写回 DB，接口读 DB | 用户要求实时性，接受接口延迟换查询压力恒定——被实时+缓存方案替代 |
| 完全开放签名 | `Map<String,Object> getInstanceInfo(long)` | 主应用无从辨认字段，每个消费方都要约定魔法键；playerCount 是核心契约应类型化 |
| 手写 ConcurrentHashMap 缓存 | 自维护 value+expireAt | 用户指定 Guava Cache；过期/并发语义成熟 |
| 批量 Provider 签名 | `Map<Long, InstanceDynamicInfo> getInstanceInfo(List<Long>)` | 把并发调度推给每个插件实现，复杂度错位；单实例粒度 + 主应用并发足够 |
| metrics 继续用适配器口径 | 详情页玩家数维持 `adapter.getDetails` | 各游戏统计口径不一，正是要插件化的原因 |

## 未来方向

- extras 出现稳定消费需求后升级为类型化字段（附契约测试，参考 maxPlayerCount 先例）
- 前端列表页对"降级值"做可视化标记（如灰色/上标"缓存"），需先在 VO 中暴露数据新鲜度
