# L4D2 Phase 5: 数据采集模块实施计划

> **创建日期**: 2026-07-20
> **范围**: spec §4 模块 11（玩家统计）+ 模块 12（Steam 游玩时长）+ 模块 13（监控重构）
> **目标**: 全量移植 `l4d2-server-next-master/backend` 的数据采集模块至 Java，对齐源项目功能
> **依赖**: Phase 1-4 已完成（RconService、SteamApiClient、ExtensionClient、ExternalHttpClient 等基础设施就绪）

---

## 0. 调研结论

### 0.1 plugin-l4d2 现状

| 资产 | 路径 | 现状 |
|------|------|------|
| MonitorController | [controller/MonitorController.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/MonitorController.java) | 6 端点（status/history/realtime/cpu-trend/memory-trend/network-trend），**被动采集**，无 `@Scheduled` |
| GeoIpService | [service/GeoIpService.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/GeoIpService.java) | 已实现（ip2region xdb 全内存查询），**未被任何业务代码注入使用** |
| SteamIdUtil | [util/SteamIdUtil.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/SteamIdUtil.java) | 3 方法（toSteam64/toSteamId2/isValid），**仅测试使用** |
| RconService | [service/RconService.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RconService.java) | 8 方法（executeCommand/getStatus/changeMap/kick/ban/difficulty/gamemode/maxPlayers），含 `ServerStatus` 和 `PlayerInfo` 内部类；超时已生效，重试配置已就绪但**未实现** |
| SteamApiClient | [util/SteamApiClient.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/util/SteamApiClient.java) | `getPublishedFileDetails` 已实现（Phase 4），`getOwnedGames` 抛 `UnsupportedOperationException`，**`getUserStatsForGame` 不存在** |
| SystemMetricResource | [extension/SystemMetricResource.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/extension/SystemMetricResource.java) | MODEL_ISOLATED 表 `ext_plugin_l4d2_systemmetricresource`，Spec 字段齐全（11 字段） |
| L4D2Config | [config/L4D2Config.java](file:///d:/program/ai/game_platform_manger/backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java) | 已有 Monitor/PlayerStats/GeoIp 配置块，**无 Playtime 配置块** |
| @EnableScheduling | 主应用 GamePlatformApplication 已启用 | plugin-l4d2 中已有 3 处 `@Scheduled` 清理任务（ChunkUpload/Download/PluginExport） |
| PlayerStats 系列 | — | **全部不存在**，需新建 Controller/Service/Resource/Spec/VO |
| Playtime 系列 | — | **全部不存在**，需新建 |

### 0.2 源项目关键参考

| 源文件 | 关键内容 |
|--------|---------|
| [player_stats.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/player_stats.go) | 10 分钟采集间隔，30 天保留，RCON `status`+`z_difficulty`+`sm_cvar mp_gamemode`，小时/天聚合，玩家搜索，estimated_minutes = samples × 10 |
| [playtime.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/playtime.go) | 并发两个 Steam API（GetOwnedGames + GetUserStatsForGame），任一成功即返回，SteamID2 → Steam64 |
| [monitor.go](file:///D:/program/open_source/l4d2-server-next-master/backend/controller/monitor.go) | 1 秒采集，gopsutil，网络接口过滤（docker/veth/br-/lo），降采样 720 点 |
| [rcon_status.go](file:///D:/program/open_source/l4d2-server-next-master/backend/logic/rcon_status.go) | ParseStatus/ParseUser/ParseDifficulty/ParseGameMode + TranslateGameMode（19 突变 + 6 社区模式） |
| [model/player_stats.go](file:///D:/program/open_source/l4d2-server-next-master/backend/model/player_stats.go) | PlayerStatSnapshot（11 字段）+ PlayerStatPlayer（12 字段） |
| [model/metric.go](file:///D:/program/open_source/l4d2-server-next-master/backend/model/metric.go) | SystemMetric（9 字段，GB/KB 单位） |

### 0.3 关键设计决策

1. **数据存储**：复用 plugin-l4d2 的 ExtensionClient（AbstractExtension 宽表），不引入独立 SQLite 库；与 plugin-l4d2 现有规范一致。
2. **采集方式**：新增 `@Scheduled` 主动采集任务，读取 `L4D2Config.monitor.collectIntervalMs`（1s）和 `playerStats.collectIntervalMs`（10min）。
3. **Standalone 模式**：监控采集通过 `HostQueryService.getHostResourceInfo(hostId)` 拉取主机资源（PF4J 模式）或 oshi-core 本地采集（Standalone 模式）。PlayerStats 采集通过 RconService.executeCommand 远程拉取，与运行模式无关。
4. **审计功能**：根据用户要求**去掉审计**（不实现 LogOp 相关功能）。
5. **GeoIpService 注入**：PlayerStats 采集时调用 `geoIpService.queryProvince(stripPort(ip))` 填充 location 字段。
6. **SteamId 归一**：PlayerStats 存储 `STEAM_X:Y:Z` 原文（与 RCON 输出一致），Playtime 查询时调用 `SteamIdUtil.toSteam64` 转换。
7. **降采样策略**：监控历史查询超过 2000 点时降至 720 点（对齐源项目）；PlayerStats 直接按小时/天聚合。

---

## Task 5.1: PlayerStats 后端模块

### 目标
实现玩家在线统计的采集、存储、聚合查询、玩家搜索功能。

### 实施步骤

#### 5.1.1 新建 Resource/Spec（扩展存储）

**文件**: `extension/PlayerStatSnapshotResource.java`
- 继承 `AbstractExtension<PlayerStatSnapshotSpec>`
- `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)`
- 物理表：`ext_plugin_l4d2_playerstatsnapshot`

**文件**: `extension/PlayerStatSnapshotSpec.java`
- 字段（对齐源项目 model.PlayerStatSnapshot）：
  - `instanceId: Long`
  - `timestamp: Long`（Unix 秒）
  - `serverOnline: Boolean`
  - `collectOk: Boolean`
  - `playerCount: Integer`
  - `maxPlayers: Integer`
  - `map: String`
  - `hostname: String`
  - `difficulty: String`（中文：简单/普通/高级/专家/未知）
  - `gameMode: String`（中文：合作/写实/对抗/突变模式N 等）
  - `errorMessage: String`

**文件**: `extension/PlayerStatPlayerResource.java`
- 继承 `AbstractExtension<PlayerStatPlayerSpec>`
- 物理表：`ext_plugin_l4d2_playerstatplayer`

**文件**: `extension/PlayerStatPlayerSpec.java`
- 字段（对齐源项目 model.PlayerStatPlayer）：
  - `instanceId: Long`
  - `snapshotId: String`（关联 PlayerStatSnapshotResource 的 id）
  - `timestamp: Long`
  - `steamId: String`（STEAM_X:Y:Z 格式）
  - `name: String`
  - `ip: String`（IP:port 格式）
  - `location: String`（GeoIpService 查询结果，省份）
  - `status: String`
  - `delay: Integer`
  - `loss: Integer`
  - `duration: String`
  - `linkRate: Integer`

#### 5.1.2 新建 StatusParser 工具类

**文件**: `util/StatusParser.java`
- 对齐源项目 `logic/rcon_status.go`
- 方法：
  - `Status parse(String statusText)`：解析 RCON `status` 输出
  - `User parseUser(String line)`：解析玩家行（10 字段正则）
  - `String parseDifficulty(String difficultyText)`：映射为中文
  - `String parseGameMode(String gameModeText)`：映射为中文（含 TranslateGameMode）
  - `String translateGameMode(String mode)`：coop→合作、realism→写实、survival→生存、versus→对抗、scavenge→拾荒、holdout→坚守、mutation1~20→突变模式N、community1~6→社区模式N
- 内部 `Status` 和 `User` record（或直接复用 RconService.ServerStatus / PlayerInfo）

#### 5.1.3 新建 PlayerStatsService

**文件**: `service/PlayerStatsService.java`
- `@Service`，注入：`RconService`、`GeoIpService`、`ExtensionClient`、`InstanceQueryService`、`L4D2Config`
- 状态字段：
  - `volatile boolean enabled`：开关（持久化到 L4D2Config.PlayerStats.enabled，运行时缓存）
- 关键方法：
  - `@Scheduled(fixedRateString = "${plugin.l4d2.player-stats.collect-interval-ms:600000}") collectPlayerStats()`：定时采集
    - 读取所有需要采集的实例（暂定：所有 L4D2 实例，从 InstanceQueryService 拉取）
    - 对每个实例执行 `rconService.getStatus(host, port, password)` 三命令
    - 用 StatusParser 解析
    - 调用 `geoIpService.queryProvince(stripPort(ip))` 填充每个玩家的 location
    - 持久化 snapshot + players 到 ExtensionClient
    - 失败时持久化 serverOnline=false/collectOk=false/errorMessage 的 snapshot
  - `@Scheduled(fixedRate = 86400_000) cleanupExpired()`：每日清理过期数据（> retentionMs）
  - `setEnabled(boolean)` / `isEnabled()`：运行时开关
  - `getConfig()`：返回 PlayerStatsConfigVO（enabled/intervalMinutes/retentionDays/lastSnapshot）
  - `getHourlyTrend(Long instanceId, Long start, Long end)`：小时聚合查询
    - 通过 `extensionClient.list(PlayerStatSnapshotResource.class, opts)` 拉取范围
    - 内存按小时桶（timestamp / 3600 * 3600）聚合：avg_players / peak_players / offline_samples / sample_count
    - 通过 `extensionClient.list(PlayerStatPlayerResource.class, opts)` 查 unique_players（distinct steam_id）
    - 合并返回 `List<PlayerStatsTrendVO>`
  - `getDailyTrend(Long instanceId, Long start, Long end)`：天聚合（按 localDayStart 桶）
  - `searchPlayers(Long instanceId, String keyword, Long start)`：玩家搜索
    - 按 steam_id / name 模糊匹配
    - 返回 samples / last_seen / estimated_minutes（= samples × 10） / rank
  - `getPlayerDays(Long instanceId, String steamId, Long start)`：玩家按日统计
    - 返回 DayResult[]（date/onlineMinutes/samples/firstSeen/lastSeen）
  - `getPlayerAliases(Long instanceId, String steamId, Long start)`：玩家别名聚合
    - 返回 NameAliasResult[]（name/samples/estimatedMinutes/firstSeen/lastSeen）
  - `private String stripPort(String ipPort)`：`ip:port` → `ip`

#### 5.1.4 新建 Controller

**文件**: `controller/PlayerStatsController.java`
- `@RestController`，`@RequestMapping("/api/plugin/l4d2/player-stats")`
- 端点（全部需 admin 鉴权，除 getConfig）：
  - `GET /config` → PlayerStatsConfigVO
  - `POST /config` `{enable: boolean}` → 200
  - `GET /hourly?instanceId&start&end&bucket=hour|day` → `List<PlayerStatsTrendVO>`
  - `GET /players/search?instanceId&keyword&start` → `List<PlayerStatsPlayerVO>`
  - `GET /players/{steamId}/days?instanceId&start` → `List<PlayerStatsDayVO>`
  - `GET /players/{steamId}/aliases?instanceId&start` → `List<PlayerStatsAliasVO>`

#### 5.1.5 新建 VO/DTO

- `vo/PlayerStatsConfigVO.java`：enabled/intervalMinutes/retentionDays/lastSnapshot
- `vo/PlayerStatsTrendVO.java`：timestamp/avgPlayers/peakPlayers/uniquePlayers/offlineSamples/sampleCount
- `vo/PlayerStatsPlayerVO.java`：steamId/name/location/ip/lastSeen/estimatedMinutes/rank
- `vo/PlayerStatsDayVO.java`：date/onlineMinutes/samples/firstSeen/lastSeen
- `vo/PlayerStatsAliasVO.java`：name/samples/estimatedMinutes/firstSeen/lastSeen
- `dto/PlayerStatsConfigDTO.java`：enable

#### 5.1.6 配置扩展

**修改**: `config/L4D2Config.java`
- `PlayerStats` 内部类新增字段：
  - `boolean enabled = true`（默认开启）
  - `boolean adminOnly = true`（查询接口仅管理员可见，默认 true）

#### 5.1.7 测试

**文件**: `test/.../service/PlayerStatsServiceTest.java`
- 测试用例（≥12）：
  - `collect_success_persists_snapshot_and_players`
  - `collect_rcon_failure_persists_error_snapshot`
  - `collect_disabled_skips`
  - `cleanup_removes_expired_snapshots_and_players`
  - `hourly_trend_aggregates_correctly`
  - `daily_trend_aggregates_by_local_day`
  - `hourly_trend_includes_unique_players`
  - `search_players_by_steamid`
  - `search_players_by_name`
  - `search_players_empty_keyword_returns_all`
  - `player_days_estimates_minutes_correctly`
  - `player_aliases_aggregates_names`

**文件**: `test/.../util/StatusParserTest.java`
- 测试用例（≥8）：
  - `parse_status_extracts_hostname_map_players`
  - `parse_status_extracts_users_correctly`
  - `parse_status_handles_empty_output`
  - `parse_difficulty_maps_easy_to_chinese`
  - `parse_difficulty_returns_unknown_on_no_match`
  - `parse_gamemode_maps_coop`
  - `parse_gamemode_maps_mutation15`
  - `parse_gamemode_returns_unknown_on_no_match`

### 验收标准
- 18+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- 采集任务能被 Spring Scheduler 调度（启动时不抛异常）

---

## Task 5.2: Playtime 后端模块

### 目标
实现 Steam Web API 查询玩家 L4D2 游玩时长（总时长 + 实战时长），并发请求两个 API。

### 实施步骤

#### 5.2.1 扩展 SteamApiClient

**修改**: `util/SteamApiClient.java`
- 实现 `getOwnedGames(String steamId, int appid)` → `OwnedGamesResult`
  - API: `GET https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/`
  - Query: `key={apiKey}&steamid={steamId64}&format=json&include_appinfo=false`
  - 解析 `response.games[]`，匹配 `appid == 550`，取 `playtime_forever`（分钟）
  - 返回 `OwnedGamesResult(totalPlaytimeMinutes, found)`
- 新增 `getUserStatsForGame(String steamId, int appid)` → `UserStatsResult`
  - API: `GET https://api.steampowered.com/ISteamUserStats/GetUserStatsForGame/v2/`
  - Query: `key={apiKey}&steamid={steamId64}&appid={appid}&format=json`
  - 解析 `playerstats.stats[]`，匹配 `name == "Stat.TotalPlayTime.Total"`，取 `value`（秒）
  - 返回 `UserStatsResult(totalPlayTimeSeconds, found)`
- 新增内部 record：
  - `OwnedGamesResult(boolean found, long playtimeForeverMinutes)`
  - `UserStatsResult(boolean found, long totalPlayTimeSeconds)`
- 删除原 `getOwnedGames` 抛 `UnsupportedOperationException` 的占位实现
- 更新 `SteamApiClientTest`：删除 `get_owned_games_throws`，新增 `get_owned_games_returns_playtime` / `get_owned_games_not_in_library` / `get_user_stats_returns_total_playtime` / `get_user_stats_profile_not_public`

#### 5.2.2 新建 PlaytimeService

**文件**: `service/PlaytimeService.java`
- `@Service`，注入：`SteamApiClient`、`L4D2Config`
- 关键方法：
  - `PlaytimeVO getPlaytime(String steamId)`：
    - 调用 `SteamIdUtil.toSteam64(steamId)` 转换为 Steam64
    - 并发请求两个 API（`CompletableFuture.supplyAsync`）
    - 任一成功即返回，都失败抛异常
    - 计算总时长（小时 = minutes / 60）和实战时长（小时 = seconds / 3600）
    - 返回 `PlaytimeVO(totalPlaytimeHours, realPlaytimeHours, steamId, steamId64)`

#### 5.2.3 新建 Controller

**文件**: `controller/PlaytimeController.java`
- `@RestController`，`@RequestMapping("/api/plugin/l4d2/playtime")`
- 端点：
  - `POST /query` `{steamId: "STEAM_1:0:12345"}` → `PlaytimeVO`
  - `GET /query?steamId=STEAM_1:0:12345` → `PlaytimeVO`（便捷 GET 版本）

#### 5.2.4 新建 VO/DTO

- `vo/PlaytimeVO.java`：steamId/steamId64/totalPlaytimeHours/realPlaytimeHours/source
- `dto/PlaytimeQueryDTO.java`：steamId（@NotBlank）

#### 5.2.5 配置扩展

**修改**: `config/L4D2Config.java`
- 新增 `Playtime` 内部类：
  - `long requestTimeoutMs = 10_000L`
  - `boolean allowPartialResult = true`（任一成功即返回）

#### 5.2.6 测试

**文件**: `test/.../service/PlaytimeServiceTest.java`
- 测试用例（≥6）：
  - `get_playtime_both_apis_succeed`
  - `get_playtime_only_owned_games_succeeds`
  - `get_playtime_only_user_stats_succeeds`
  - `get_playtime_both_fail_throws_exception`
  - `get_playtime_invalid_steamid_throws`
  - `get_playtime_concurrent_timeout_handled`

**文件**: `test/.../util/SteamApiClientTest.java`（更新）
- 删除 `get_owned_games_throws`
- 新增 4 个用例（见 5.2.1）

### 验收标准
- 10+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- 并发请求逻辑正确（CompletableFuture.allOf 等待两个 future）

---

## Task 5.3: MonitorController 重构 + 定时采集

### 目标
为现有 MonitorController 新增 `@Scheduled` 主动采集任务，实现 1 秒采集间隔；优化历史查询降采样逻辑。

### 实施步骤

#### 5.3.1 新建 MonitorCollectorService

**文件**: `service/MonitorCollectorService.java`
- `@Service`，注入：`InstanceQueryService`、`HostQueryService`、`ExtensionClient`、`L4D2Config`
- 关键方法：
  - `@Scheduled(fixedRateString = "${plugin.l4d2.monitor.collect-interval-ms:1000}") collectMetrics()`：
    - 拉取所有 L4D2 实例
    - 对每个实例调用 `hostQueryService.getHostResourceInfo(hostId)` 获取 CPU/内存/磁盘/网络
    - 转换为 `SystemMetricResource` 持久化到 ExtensionClient
    - 异常时跳过该实例（log.warn，不中断循环）
  - `@Scheduled(fixedRate = 3600_000) cleanupExpired()`：每小时清理过期数据（> retentionMs）
  - `getLatestMetric(Long instanceId)`：返回最近一条 SystemMetricResource（从内存缓存或 DB 查询）
- 内存状态：`ConcurrentHashMap<Long, SystemMetricResource>` 缓存最新指标（避免每次 GET /status 都查询 DB）

#### 5.3.2 重构 MonitorController

**修改**: `controller/MonitorController.java`
- `GET /status`：优先从 MonitorCollectorService 内存缓存读取，缓存未命中时再调用 `hostQueryService.getHostResourceInfo`（兼容被动模式）
- `GET /history`：增强降采样逻辑
  - 拉取范围内所有点
  - 如果 ≤ 2000 点，直接返回
  - 如果 > 2000 点，按 `bucketSize = duration / 720` 降采样，每桶取 MAX
  - 返回 `List<SystemMetricVO>`
- 新增 `GET /config`：返回 `{historyEnabled: true, collectIntervalMs: 1000, retentionMs: ...}`
- 新增 `POST /config`：`{enable: boolean}`（管理员鉴权）控制 `@Scheduled` 是否实际采集

#### 5.3.3 新增 VO

**文件**: `vo/SystemMetricVO.java`
- 字段：timestamp/cpuPercent/cpuMaxCore/memUsed/memTotal/swapUsed/netUpSpeed/netDownSpeed/diskUsed/diskTotal
- 含格式化方法 `getMemUsedGB()` / `getNetUpSpeedKBps()` 等（前端可直接用）

**文件**: `vo/MonitorConfigVO.java`：historyEnabled/collectIntervalMs/retentionMs/maxPoints/downsampleTo

#### 5.3.4 配置扩展

**修改**: `config/L4D2Config.java`
- `Monitor` 内部类新增：
  - `boolean historyEnabled = true`
  - `boolean collectEnabled = true`

#### 5.3.5 测试

**文件**: `test/.../service/MonitorCollectorServiceTest.java`
- 测试用例（≥6）：
  - `collect_metrics_persists_for_each_instance`
  - `collect_metrics_skips_failed_instance`
  - `collect_metrics_disabled_skips_all`
  - `cleanup_removes_expired_metrics`
  - `get_latest_metric_returns_from_cache`
  - `get_latest_metric_falls_back_to_db`

**文件**: `test/.../controller/MonitorControllerTest.java`（更新）
- 新增 `history_downsamples_when_over_2000_points`
- 新增 `history_returns_raw_when_under_2000_points`
- 新增 `get_config_returns_current_settings`
- 新增 `set_config_updates_enabled_flag`

### 验收标准
- 10+ 测试全部通过
- `mvn test -pl plugin-l4d2/plugin-l4d2-core` BUILD SUCCESS
- 1 秒采集任务能被调度（启动时不抛异常）

---

## Task 5.4: 前端 PlayerStats + Playtime + Monitor 重构

### 目标
实现三个数据采集模块的前端页面，对齐源项目 UI。

### 实施步骤

#### 5.4.1 API 扩展

**修改**: `frontend/src/api/index.ts`
- 新增 `playerStatsApi`：
  - `getConfig()` / `setConfig({enable})`
  - `getHourly({instanceId, start, end, bucket})`
  - `searchPlayers({instanceId, keyword, start})`
  - `getPlayerDays({instanceId, steamId, start})`
  - `getPlayerAliases({instanceId, steamId, start})`
- 新增 `playtimeApi`：
  - `query(steamId)` (POST)
- 扩展 `monitorApi`：
  - `getConfig()` / `setConfig({enable})`
- 类型定义：`PlayerStatsConfigVO` / `PlayerStatsTrendVO` / `PlayerStatsPlayerVO` / `PlayerStatsDayVO` / `PlayerStatsAliasVO` / `PlaytimeVO`

#### 5.4.2 PlayerStats.vue

**文件**: `frontend/src/pages/PlayerStats.vue`
- `<script setup lang="ts">` + Element Plus
- 顶部：开关（el-switch）+ 配置展示（间隔/保留天数/最近采集时间）
- el-tabs 切换："趋势图" / "玩家列表"
- 趋势图 Tab：
  - 时间范围选择（el-date-picker type="datetimerange"）
  - 桶类型选择（小时/天）
  - ECharts 折线图：avg_players / peak_players / unique_players（双 Y 轴）
  - 离线采样数提示
- 玩家列表 Tab：
  - 搜索框（steamId / name）
  - el-table：rank / steamId / name / location / ip / lastSeen / estimatedMinutes / 操作（查看详情）
  - 详情弹窗：el-tabs 切换"按日统计" / "别名记录"
  - 按日统计：el-table（date/onlineMinutes/samples/firstSeen/lastSeen）
  - 别名记录：el-table（name/samples/estimatedMinutes/firstSeen/lastSeen）

#### 5.4.3 Playtime.vue

**文件**: `frontend/src/pages/Playtime.vue`
- 输入框：SteamID（支持 `STEAM_1:0:12345` 格式）
- 查询按钮 → POST /api/plugin/l4d2/playtime/query
- 结果卡片：
  - SteamID（原始 + Steam64）
  - 总时长（小时）
  - 实战时长（小时）
  - 数据来源（Steam Web API）
- 错误提示（API key 未配置 / 资料未公开等）

#### 5.4.4 Monitor.vue 重构

**修改**: `frontend/src/pages/Monitor.vue`（如存在则重构，否则新建）
- 顶部：开关（el-switch）+ 配置展示
- 实时状态卡片：CPU / 内存 / 交换 / 磁盘 / 网络上行 / 网络下行（实时刷新，1s 轮询）
- 历史趋势图：
  - 时间范围选择
  - ECharts 折线图：cpuPercent / cpuMaxCore / memUsed / netUpSpeed / netDownSpeed / diskUsed
  - 自动降采样（后端处理）

#### 5.4.5 路由配置

**修改**: `frontend/src/router/index.ts`
- 新增：`{ path: '/player-stats', name: 'PlayerStats', meta: { title: '玩家统计', icon: 'User' } }`
- 新增：`{ path: '/playtime', name: 'Playtime', meta: { title: '游玩时长', icon: 'Clock' } }`
- 保留/更新：`{ path: '/monitor', name: 'Monitor', meta: { title: '系统监控', icon: 'Monitor' } }`

### 验收标准
- 前端 `npm run build` 通过
- 三个页面可正常访问
- ECharts 图表渲染正确
- el-switch 开关可控制后端采集

---

## Task 5.5: Phase 5 集成验证

### 目标
全模块集成测试 + 端到端验证 + 提交。

### 实施步骤

#### 5.5.1 全量测试

```bash
cd backend
mvn test -pl plugin-l4d2/plugin-l4d2-core
# 期望：Phase 4 (226) + Phase 5 新增 (~50) = 276+ tests, 0 failures

cd frontend
npm run build
# 期望：构建成功
```

#### 5.5.2 集成验证

- 启动应用，确认 `@Scheduled` 任务被正确调度
- 调用 `/api/plugin/l4d2/player-stats/config` 返回正确配置
- 调用 `/api/plugin/l4d2/playtime/query` 验证 Steam API 调用（需配置 API key）
- 调用 `/api/plugin/l4d2/monitor/status` 验证缓存命中

#### 5.5.3 提交

- plugin-l4d2 仓库提交（按 Task 分组）：
  - `feat(l4d2): player stats collector with rcon & geoip (phase 5.1)`
  - `feat(l4d2): playtime service with steam api (phase 5.2)`
  - `refactor(l4d2): monitor collector with scheduled task (phase 5.3)`
  - `feat(l4d2-fe): player stats & playtime & monitor pages (phase 5.4)`
- 主仓库提交：
  - `feat(l4d2): phase 5 data collection (player stats + playtime + monitor)`

### 验收标准
- 全部测试通过
- 前端构建成功
- 主仓库和 plugin-l4d2 子模块均已提交

---

## 执行顺序与依赖

```
Task 5.1 (PlayerStats 后端) ─┐
                             ├─→ Task 5.4 (前端) ─→ Task 5.5 (集成验证)
Task 5.2 (Playtime 后端) ────┤
                             │
Task 5.3 (Monitor 重构) ─────┘
```

- Task 5.1 / 5.2 / 5.3 可**并行**派发（互不依赖）
- Task 5.4 依赖 5.1+5.2+5.3 的 API 定义
- Task 5.5 依赖全部完成

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| `@Scheduled` 1s 采集压力过大 | 配置开关 + 实例数量限制（采集时跳过未运行实例） |
| Steam API 频率限制 | 单次查询两个 API，无定时任务；前端防抖（5s） |
| ExtensionClient 大量写入性能 | 监控数据保留 3 天 + 每小时清理；PlayerStats 保留 30 天 + 每日清理 |
| RCON 连接失败 | 已有超时配置（5s），失败时持久化 error snapshot 不中断采集循环 |
| ip2region xdb 加载失败 | GeoIpService 已有降级（返回 "unknown"），不影响采集主流程 |
| 并发 CompletableFuture 异常 | PlaytimeService 使用 `CompletableFuture.supplyAsync` + try-catch，任一成功即返回 |

---

*最后更新: 2026-07-20*
