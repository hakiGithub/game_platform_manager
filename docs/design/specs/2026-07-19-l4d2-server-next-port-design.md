# L4D2 Server Next 功能移植设计

> 将 `D:\program\open_source\l4d2-server-next-master`（Go + Vue 3 项目）的全部 L4D2 服务器管理功能照搬到现有 Java 实现的 `plugin-l4d2` 插件，后端用 Java 实现并通过 PF4J 插件框架提供给主应用使用。

- **创建日期**：2026-07-19
- **源项目**：`l4d2-server-next-master`（Go 1.25.5 + Gin + GORM + Vue 3 + Ant Design Vue）
- **目标项目**：`game_platform_manger` 的 `plugin-l4d2` 插件（Java 17 + Spring Boot 3.2.5 + PF4J + Vue 3 + Element Plus）
- **方法**：垂直切片（A 方案）—— 按功能模块逐个完整移植
- **范围**：全量移植（除明确跳过项）

---

## §1 整体架构与移植范围

### 1.1 移植目标

将源项目的全部 21 个功能模块对等移植到现有 Java L4D2 插件，重构现有 6 个 Controller 与源项目对齐，新增 12 个 Controller，同步双模式打包（PF4J + standalone）和前端 Vue 页面（新增 9 个、重构 8 个）。

### 1.2 架构定位

移植后的 L4D2 插件继续遵循现有架构约束：

- `plugin-l4d2-core`：业务代码 + PF4J 插件 JAR，**禁止依赖 core**，仅通过 `InstanceQueryService` / `HostQueryService` / `FileAccessService` / `ExtensionClient` 获取宿主能力
- `plugin-l4d2-standalone`：独立 Spring Boot fat JAR，自实现 4 个基础设施服务
- `frontend`：Vue 3 + Element Plus，三模式路由（Wujie/Standalone/Dev），构建产物打入 core JAR 的 `ui/`

### 1.3 后端模块组织

在现有 `com.gameplatform.plugin.l4d2` 包下按业务域组织：

```
com.gameplatform.plugin.l4d2/
├── L4D2Plugin.java                       # PF4J 主类（不变）
├── L4D2Extension.java                    # 扩展点实现（manifest 扩展 features 列表）
├── config/L4D2Config.java                # 新增配置项（workshop API、GitHub repo 等）
├── controller/                           # 18 个 Controller（重构 6 + 新增 12）
│   ├── RconController                    # [重构] 加最大玩家数端点对齐
│   ├── AdminController                   # [保留] 已对齐
│   ├── MapController                     # [重构] 接入热重载/裁剪/上传
│   ├── MonitorController                 # [重构] 1s 采集+降采样+3天清理
│   ├── PluginManageController            # [重构] 加 fileRefs/商店/预设/备份/导出
│   ├── ServerConfigController            # [重构] 加多 tick 同步
│   ├── ServerInfoController              # [新增] hostname/motd/host
│   ├── PluginConfigController            # [新增] SourceMod cfg 读写
│   ├── PluginStoreController             # [新增] GitHub 插件商店
│   ├── DownloadController                # [新增] URL/Workshop 下载器
│   ├── ChunkUploadController             # [新增] 分片上传
│   ├── PlayerStatsController             # [新增] 玩家统计采集与查询
│   ├── LogsController                    # [新增] SourceMod 日志 SSE 流
│   ├── BackupController                  # [新增] 备份还原
│   ├── PresetController                  # [新增] 预设系统
│   ├── PlaytimeController                # [新增] Steam API 游玩时长
│   ├── RestartController                 # [新增] 服务器重启
│   └── VersionController                 # [新增] 版本信息
├── service/                              # 业务服务
│   ├── RconService                       # [保留] 已实现 Source RCON
│   ├── VpkParserService                  # [保留]
│   ├── VpkTrimService                    # [新增] VPK v1 二进制裁剪
│   ├── SourceModCfgService               # [新增] cfg 解析与回写
│   ├── AdminsIniService                  # [新增] admins_simple.ini 解析
│   ├── PluginInstallService              # [重构] ZIP 解压+fileRefs+启用/禁用+RCON 加载
│   ├── PluginStoreService                # [新增] GitHub 插件商店 + Git LFS
│   ├── PluginExportService               # [新增] 插件导出任务
│   ├── BackupService                     # [新增] backups 管理
│   ├── PresetService                     # [新增] preset.yaml 应用
│   ├── DownloadService                   # [新增] URL 下载
│   ├── WorkshopDownloadService           # [新增] Workshop API 下载
│   ├── ChunkUploadService                # [新增] 分片合并
│   ├── PlayerStatsService                # [新增] 玩家统计采集与查询
│   ├── SourceModLogService               # [新增] SSE 日志流
│   ├── MonitorService                    # [重构] 1s 采集+降采样+清理
│   ├── PlaytimeService                   # [新增] Steam API
│   ├── RestartService                    # [新增] RCON/Shell 重启
│   └── GeoIpService                      # [新增] ip2region 查询
├── extension/                            # 扩展资源（@ExtensionModel MODEL_ISOLATED）
│   ├── AdminResource                     # [保留]
│   ├── SystemMetricResource              # [保留] 补充降采样字段
│   ├── PluginConfigResource              # [保留]
│   ├── DownloadTaskResource              # [保留]
│   ├── PlayerStatSnapshotResource        # [新增]
│   ├── PlayerStatPlayerResource          # [新增]
│   ├── PluginBackupResource              # [新增] 备份条目
│   └── ChunkUploadResource               # [新增] 分片上传元数据
├── util/                                 # 工具类
│   ├── VpkParser                         # [保留]
│   ├── GbkCodecUtil                      # [新增] GBK↔UTF-8
│   ├── ZipExtractUtil                    # [新增] GBK 文件名 ZIP 解压
│   ├── ArchiveExtractUtil                # [新增] RAR/7z 解压（Apache Commons Compress）
│   ├── FilenameSanitizeUtil              # [新增] 文件名清洗
│   ├── SteamIdUtil                       # [新增] SteamID 格式转换
│   └── SourceRconPacketUtil              # [保留] 内部使用
├── dto/                                  # 请求/响应 DTO
└── vo/                                   # 视图对象
```

### 1.4 前端页面规划

`frontend/src/pages/` 下页面布局：

| 页面 | 状态 | 主要内容 |
|------|------|---------|
| `Dashboard.vue` | 重构 | 服务器状态总览 + 快捷操作 |
| `InstanceSelect.vue` | 保留 | standalone 实例选择 |
| `Rcon.vue` | 增强 | + 任意命令、最大玩家数、玩家封禁 |
| `Maps.vue` | 重构 | + VPK 上传/裁剪/热重载 |
| `Plugins.vue` | 大重构 | + 商店/预设/备份/导出/fileRefs 提示 |
| `PluginStore.vue` | 新增 | GitHub 商店浏览与下载 |
| `PluginConfig.vue` | 新增 | SourceMod cfg 编辑器 |
| `Admins.vue` | 保留 | 已对齐 |
| `Monitor.vue` | 重构 | + ECharts 时序图表 + 历史查询 |
| `PlayerStats.vue` | 新增 | 玩家统计快照 + 搜索 |
| `Logs.vue` | 新增 | SSE 实时日志流 |
| `ServerConfig.vue` | 增强 | + 多 tick 同步 + 自定义配置块 |
| `ServerInfo.vue` | 新增 | hostname/motd/host 编辑 |
| `Backup.vue` | 新增 | 备份列表/创建/还原/导入导出 |
| `Preset.vue` | 新增 | 预设场景应用 |
| `Download.vue` | 新增 | 下载器（URL/Workshop）+ 任务列表 |
| `Playtime.vue` | 新增 | Steam 游玩时长查询 |

### 1.5 移植范围一览表

| 序号 | 模块 | 后端 Controller | 后端 Service | 扩展资源 | 前端页面 | 外部依赖 |
|------|------|----------------|-------------|---------|---------|---------|
| 1 | 服务器信息管理 | ServerInfoController | (复用 FileAccessService) | - | ServerInfo.vue | - |
| 2 | SourceMod 日志 SSE 流 | LogsController | SourceModLogService | - | Logs.vue | Spring SseEmitter |
| 3 | 备份还原 | BackupController | BackupService | PluginBackupResource | Backup.vue | - |
| 4 | 插件配置 cfg | PluginConfigController | SourceModCfgService | - | PluginConfig.vue | - |
| 5 | 插件商店 | PluginStoreController | PluginStoreService | - | PluginStore.vue | GitHub API + Git LFS |
| 6 | 预设系统 | PresetController | PresetService | - | Preset.vue | preset.yaml |
| 7 | 插件管理重构 | PluginManageController | PluginInstallService+PluginExportService | - | Plugins.vue | fileRefs |
| 8 | VPK 裁剪 | MapController | VpkTrimService | - | Maps.vue | - |
| 9 | 地图热重载 | MapController | (RconService) | - | Maps.vue | - |
| 10 | 分片上传 | ChunkUploadController | ChunkUploadService | ChunkUploadResource | Maps/Plugins | - |
| 11 | URL 下载器 | DownloadController | DownloadService | DownloadTaskResource | Download.vue | - |
| 12 | Workshop 下载器 | DownloadController | WorkshopDownloadService | DownloadTaskResource | Download.vue | Steam Web API（official） |
| 13 | 玩家统计 | PlayerStatsController | PlayerStatsService | PlayerStat*Resource | PlayerStats.vue | GeoIP xdb |
| 14 | Steam API 游玩时长 | PlaytimeController | PlaytimeService | - | Playtime.vue | Steam Web API |
| 15 | 监控采集重构 | MonitorController | MonitorService | SystemMetricResource | Monitor.vue | oshi-core |
| 16 | 服务器重启 | RestartController | RestartService | - | Dashboard.vue | - |
| 17 | 多 tick ServerConfig | ServerConfigController | (复用 FileAccessService) | - | ServerConfig.vue | - |
| 18 | 版本号 | VersionController | - | - | Dashboard.vue | - |
| 19 | RCON 增强 | RconController | RconService | - | Rcon.vue | - |
| 20 | GeoIP | (内部服务) | GeoIpService | - | PlayerStats | ip2region xdb |

### 1.6 外部 API 处理决策（部分复用）

- ✅ Steam Web API（官方）：复用，`STEAM_API_KEY` 配置
- ✅ Workshop：用官方 `IPublishedFileService/GetDetails` API（替代源项目的 `l4d2-workshop-parse.laoyutang.cn`）
- ✅ GitHub 插件商店：复用源项目仓库 `LaoYutang/l4d2-plugins-store` + Git LFS BatchAPI
- ❌ QQ 闪传：跳过（小众且 API 易变动）
- ✅ GeoIP：用 `org.lionsoul:ip2region` Java 版，xdb 文件随插件 JAR 打包

### 1.7 配置项扩展

`L4D2Config`（`@ConfigurationProperties(prefix = "plugin.l4d2")`）新增：

```yaml
plugin:
  l4d2:
    rcon:
      default-port: 27020
    steam:
      api-key: ${STEAM_API_KEY:}
      l4d2-appid: 550
    workshop:
      download-dir: addons/
      max-concurrent: 3
      proxy-url:        # 第三方下载代理（Steam API 不返回 file_url 时降级使用）
    plugin-store:
      repo: LaoYutang/l4d2-plugins-store
      branch: main
      cache-ttl: 10m
      max-concurrent: 3
    monitor:
      collect-interval: 1s
      retention: 3d
      max-points: 2000
      downsample-to: 720
      network-ignore-pattern: "docker|veth|br-|lo"
    player-stats:
      collect-interval: 10m
      retention: 30d
    chunk-upload:
      chunk-size: 5MB
      max-total-size: 2GB
      expire: 6h
      disk-usage-threshold: 0.9
    vpk-trim:
      enabled: true
    map-hot-reload:
      command: "update_addon_paths; mission_reload"
    geoip:
      xdb-path: ip2region.xdb
    restart:
      by-rcon: false
      container-name: l4d2
      custom-cmd: ""
```

### 1.8 关键架构决策

1. **SSE 实现**：用 Spring `SseEmitter`（同步 Servlet 栈兼容），不用 WebFlux。每个 SSE 连接独立线程，结合 `FileAccessService.tailFile` 远程 tail。
2. **监控采集**：用 `oshi-core`（跨平台系统信息库），替代 Go 的 `gopsutil`。容器内场景通过 `HostQueryService.getHostResourceInfo` 委托给宿主。
3. **预设/备份存储**：预设 YAML 内嵌 `plugin-l4d2-core/src/main/resources/preset.yaml`；备份通过 `PluginBackupResource` 扩展资源持久化（不再用文件）。
4. **VPK 二进制裁剪**：纯 Java `ByteBuffer`（小端）实现，与源项目逻辑对等。
5. **分片上传临时目录**：使用 `java.nio.file.Files.createTempDirectory("l4d2-chunk-")`，6 小时过期清理。
6. **Workshop 下载策略**：使用 Steam Web API `IPublishedFileService/GetDetails` 获取元数据；若 `file_url` 为空（多数情况），返回 `pending_manual` 状态，提示用户配置第三方代理 URL。
7. **GitHub 插件商店**：通过 GitHub REST API `GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1` 获取目录树（缓存 10 分钟），检测 LFS pointer，通过 `POST {repo}/info/lfs/objects/batch` 获取真实下载链接，3 并发下载。
8. **文件路径处理**：所有 L4D2 游戏路径通过 `InstanceVO.installPath` + 相对路径拼接，由 `FileAccessService` 通过 SFTP 操作远程主机。**不直接读写本地文件**。
9. **GBK 编码**：所有 L4D2 相关文本文件统一通过 `GbkCodecUtil` 处理，避免乱码。

### 1.9 明确跳过的功能

| 源项目模块 | 跳过原因 |
|----------|---------|
| QQ 闪传下载 | 小众且 API 易变动 |
| GeoIP 白名单中间件 | 主应用已有 JWT 鉴权，无需 IP 白名单 |
| 自服务码 | 主应用已有完整认证体系 |
| ManagerConfig 管理器配置 | 配置项通过 `L4D2Config` + `application.yml` 管理 |
| JWT 令牌管理 | 主应用已有完整 JWT 体系 |
| IP 锁定 | 同上 |
| `auth.go` 全部 | 主应用统一鉴权 |
| 审计日志 | 用户决定移除 |

---

## §2 数据模型与扩展资源

### 2.1 扩展资源设计原则

所有持久化数据通过 `AbstractExtension<T>` + `@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)` 存储，物理表名 `ext_plugin_l4d2_{kind_lower}`，遵循现有约定：
- `id` (String, 雪花ID, PRIMARY KEY) — 框架自动生成
- `name` (String, UNIQUE) — 业务标识，规范 `{instanceId}-{业务键}`
- `groupName` = `plugin-l4d2`（框架填充）
- `kind` = 类名（框架填充）
- `version` (Integer) — 乐观锁
- `metadata` (ExtensionMetadata) — labels/annotations/时间戳
- `spec` (T, POJO) — Jackson 序列化的业务数据
- `status` (String) — 高频过滤字段

### 2.2 扩展资源清单

#### 2.2.1 AdminResource（保留，已实现）

```java
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class AdminResource extends AbstractExtension<AdminSpec> { }
```

```java
@Data
public class AdminSpec {
    private Long instanceId;
    private Long hostId;
    private String steamId;        // STEAM_0:1:xxx
    private String flags;          // 99:z (root) / abc:...
    private String remark;
    private boolean active;
    private LocalDateTime lastSyncedAt;
}
```
- name: `{instanceId}-{steamId}`
- status: `active` / `inactive`

#### 2.2.2 SystemMetricResource（保留，重构）

```java
@Data
public class SystemMetricSpec {
    private Long instanceId;
    private Long hostId;
    private LocalDateTime timestamp;
    private Double cpuPercent;       // 总 CPU 使用率
    private Double cpuMaxCore;       // 单核最大使用率
    private Double memUsedGb;
    private Double swapUsedGb;
    private Double netUpSpeedKbps;
    private Double netDownSpeedKbps;
    private Double diskUsedGb;
    private String source;           // HOST_AGENT / CONTAINER_LOCAL
}
```
- name: `{instanceId}-{epochSeconds}`
- labels: `{instanceId, hostId}` 用于查询过滤
- 保留期：3 天，由 `ScheduledExecutorService` 每小时清理
- 降采样：单实例超过 2000 条时降至 720 点（按时间均匀采样）

#### 2.2.3 PluginConfigResource（保留，扩展）

```java
@Data
public class PluginConfigSpec {
    private Long instanceId;
    private Long hostId;
    private String pluginName;       // .smx 文件名（不含扩展名）
    private String configName;       // cfg 文件名
    private String configPath;       // 相对 installPath 的路径
    private List<ConfigItem> items;  // 解析后的配置项
    private String rawContent;       // 原始文件内容（GBK 解码后）
    private LocalDateTime lastSyncedAt;
}

@Data
public class ConfigItem {
    private String key;
    private String value;
    private String defaultValue;     // 来自注释 // Default: xxx
    private Double min;              // 来自 // Min: xxx
    private Double max;
    private String description;      // 来自 // 描述
    private int lineNumber;
}
```
- name: `{instanceId}-{pluginName}`
- status: `synced` / `dirty`

#### 2.2.4 DownloadTaskResource（保留，扩展）

```java
@Data
public class DownloadTaskSpec {
    private Long instanceId;
    private Long hostId;
    private String source;           // URL / WORKSHOP / STORE
    private String sourceUrl;        // 原始链接
    private String downloadUrl;      // 实际下载 URL（解析后）
    private String filename;
    private String targetPath;       // 相对 installPath 的目标路径
    private long totalBytes;
    private long downloadedBytes;
    private int progress;            // 0-100
    private String status;           // PENDING / DOWNLOADING / COMPLETED / FAILED / CANCELLED / PENDING_MANUAL
    private String errorMessage;
    private String referer;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Map<String, String> metadata;  // Workshop 元信息（标题/作者/订阅数）
}
```
- name: `{instanceId}-{epochMillis}`
- status: `pending` / `downloading` / `completed` / `failed` / `cancelled` / `pending_manual`

#### 2.2.5 PlayerStatSnapshotResource（新增）

```java
@Data
public class PlayerStatSnapshotSpec {
    private Long instanceId;
    private LocalDateTime timestamp;
    private boolean serverOnline;
    private boolean collectOk;
    private int playerCount;
    private int maxPlayers;
    private String currentMap;
    private String hostname;
    private String difficulty;
    private String gameMode;
    private String errorMessage;
}
```
- name: `{instanceId}-{epochMinutes}`
- status: `ok` / `error`
- 保留期：30 天

#### 2.2.6 PlayerStatPlayerResource（新增）

```java
@Data
public class PlayerStatPlayerSpec {
    private Long instanceId;
    private String snapshotId;      // 关联 PlayerStatSnapshotResource.id
    private LocalDateTime snapshotTimestamp;
    private String steamId;
    private String name;
    private String ip;
    private String location;        // 省份/城市（GeoIP 查询）
    private String status;          // 在线状态
    private int delay;              // ping
    private int loss;               // 丢包率
    private String duration;        // 时长 HH:MM:SS
    private Double linkRate;        // 连接率
}
```
- name: `{instanceId}-{snapshotId}-{steamId}`
- labels: `{instanceId, steamId, name}` 用于搜索

#### 2.2.7 PluginBackupResource（新增）

```java
@Data
public class PluginBackupSpec {
    private Long instanceId;
    private Long hostId;
    private String name;            // 备份名称（用户可读）
    private String description;
    private LocalDateTime createdAt;
    private BackupContent content;
    private String owner;           // 创建者
}

@Data
public class BackupContent {
    private List<String> enabledPlugins;     // 启用插件列表
    private String adminsIniContent;         // admins_simple.ini 原文
    private ServerInfoSnapshot serverInfo;   // hostname/motd/host
    private ServerConfigSnapshot serverConfig; // sv_tags 等关键字段
}

@Data
public class ServerInfoSnapshot {
    private String hostname;
    private String motd;
    private String host;
}

@Data
public class ServerConfigSnapshot {
    private String svTags;
    private String svAllowLobbyConnectOnly;
    private String svSteamgroup;
    private String customConfig;
}
```
- name: `{instanceId}-{backupName-slugified}`
- status: `active` / `restored`

#### 2.2.8 ChunkUploadResource（新增）

```java
@Data
public class ChunkUploadSpec {
    private String uploadId;        // UUIDv4
    private Long instanceId;
    private Long hostId;
    private String originalFilename;
    private long totalSize;
    private int totalChunks;
    private int receivedChunks;
    private String tempDir;         // 临时目录绝对路径
    private String targetPath;      // 完成后目标路径（相对 installPath）
    private String status;          // UPLOADING / COMPLETED / EXPIRED / FAILED
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Set<Integer> receivedIndexes;  // 已接收分片索引
}
```
- name: `{uploadId}`
- status: `uploading` / `completed` / `expired` / `failed`
- 过期清理：6 小时未完成自动删除

### 2.3 不建模为扩展资源的数据

| 数据 | 存储方式 | 原因 |
|------|---------|------|
| SourceMod 插件状态 | 实时从文件系统扫描 | 状态由文件系统决定 |
| VPK 地图列表 | 实时从 `addons/` 扫描 | 同上 |
| SourceMod 日志 | 直接从远程日志文件 SSE 推送 | 实时流 |
| 服务器信息（hostname/motd/host） | 直接读写远程文件 | 文件即真相 |
| 服务器配置（server.cfg） | 直接读写远程文件 | 同上 |
| 预设配置 | 插件 classpath `preset.yaml` | 静态配置 |
| 管理员列表（运行时） | 实时从 `admins_simple.ini` 读取 | 同 SourceMod 插件 |
| 玩家实时状态 | 实时 RCON `status` | 实时数据 |
| 监控实时指标 | 通过 `HostQueryService.getHostResourceInfo` | 宿主已具备 |
| 版本号 | 代码常量 | 静态 |

### 2.4 物理表清单

移植完成后，L4D2 插件的扩展资源表（全部 `MODEL_ISOLATED`，前缀 `ext_plugin_l4d2_`）：

| 表名 | kind | 用途 | 保留期 |
|------|------|------|--------|
| `ext_plugin_l4d2_adminresource` | AdminResource | SourceMod 管理员 | 永久 |
| `ext_plugin_l4d2_systemmetricresource` | SystemMetricResource | 监控指标 | 3 天 |
| `ext_plugin_l4d2_pluginconfigresource` | PluginConfigResource | 插件 cfg 缓存 | 永久 |
| `ext_plugin_l4d2_downloadtaskresource` | DownloadTaskResource | 下载任务 | 永久 |
| `ext_plugin_l4d2_playerstatsnapshotresource` | PlayerStatSnapshotResource | 玩家统计快照 | 30 天 |
| `ext_plugin_l4d2_playerstatplayerresource` | PlayerStatPlayerResource | 玩家统计详情 | 30 天 |
| `ext_plugin_l4d2_pluginbackupresource` | PluginBackupResource | 备份条目 | 永久 |
| `ext_plugin_l4d2_chunkuploadresource` | ChunkUploadResource | 分片上传 | 6 小时 |

### 2.5 数据迁移

- 现有 4 个扩展资源表（Admin/SystemMetric/PluginConfig/DownloadTask）**结构变更**需通过 `PluginSchemaManager` 自动 DDL 演进（已支持 `ALTER TABLE ADD COLUMN`）
- 新增 4 个表（PlayerStatSnapshot/PlayerStatPlayer/PluginBackup/ChunkUpload）首次启动时自动创建
- 现有数据保留，spec 字段以 Jackson 序列化兼容新增字段（向后兼容）

### 2.6 列表查询与过滤

通过 `ListOptions` Builder 构建查询：

```java
ListOptions opts = ListOptions.builder()
    .label("instanceId", instanceId.toString())
    .status("completed")
    .page(1).size(20)
    .sort("metadata.creationTimestamp", false)  // desc
    .build();
List<DownloadTaskResource> tasks = extensionClient.list(DownloadTaskResource.class, opts);
```

主要查询场景：
- 监控历史：`label=instanceId` + 时间范围 + sort by timestamp asc
- 玩家统计：`label=instanceId` + 时间范围 + 分页
- 下载任务：`label=instanceId` + status 过滤
- 备份列表：`label=instanceId` + 分页

---

## §3 核心服务与公共能力

被多个功能模块共用的基础能力，是垂直切片实施时第一批抽取的代码。

### 3.1 RCON 能力增强

#### 现状

`RconService` 已实现 Source RCON 协议（Socket + 二进制包）+ status 解析 + 难度/游戏模式翻译。

#### 增强项

```java
@Service
public class RconService {
    // 现有：executeCommand / parseStatus / parseDifficulty / parseGameMode

    // 新增：执行多条命令（顺序执行，返回结果列表）
    public List<RconResult> executeCommands(Long instanceId, List<String> commands);

    // 新增：换图（封装 changelevel）
    public RconResult changeMap(Long instanceId, String mapName);

    // 新增：踢人（封装 kickid）
    public RconResult kickPlayer(Long instanceId, int userid);

    // 新增：封禁（banid + writeid）
    public RconResult banPlayer(Long instanceId, String steamId);

    // 新增：设置最大玩家数（sv_visiblemaxplayers + sv_maxplayers）
    public RconResult setMaxPlayers(Long instanceId, int max);

    // 新增：sm plugins load/unload（被 PluginInstallService 调用）
    public RconResult loadPlugin(Long instanceId, String pluginId);
    public RconResult unloadPlugin(Long instanceId, String pluginId);

    // 新增：sm_reloadadmins
    public RconResult reloadAdmins(Long instanceId);

    // 新增：地图热重载（命令可配置）
    public RconResult hotReloadMaps(Long instanceId);

    // 新增：_restart（仅当 restart.by-rcon=true 时使用）
    public RconResult restartServer(Long instanceId);
}
```

#### 连接管理

RCON 连接通过 `InstanceVO` 获取：
- `instance.hostId` → `HostQueryService.getHostById(hostId)` 获取主机 IP
- `instance.configInfo.rconPassword` → RCON 密码
- `instance.configInfo.rconPort` 或默认 27020 → RCON 端口

保持现状，不引入连接池（RCON 协议本身无连接复用语义）。

### 3.2 文件操作封装

#### 路径解析

所有 L4D2 文件操作通过 `FileAccessService`（已存在），路径基于 `InstanceVO.installPath`：

```java
@Component
public class L4D2PathResolver {
    private static final String LEFT_4_DEAD_2 = "left4dead2";

    public String getGamePath(InstanceVO instance) {
        return instance.getInstallPath() + "/" + LEFT_4_DEAD_2;
    }
    public String getAddonsPath(InstanceVO instance) { return getGamePath(instance) + "/addons"; }
    public String getSourceModPluginsPath(InstanceVO instance) { return getAddonsPath(instance) + "/sourcemod/plugins"; }
    public String getSourceModConfigsPath(InstanceVO instance) { return getAddonsPath(instance) + "/sourcemod/configs"; }
    public String getSourceModLogsPath(InstanceVO instance) { return getAddonsPath(instance) + "/sourcemod/logs"; }
    public String getCfgPath(InstanceVO instance) { return getGamePath(instance) + "/cfg"; }
    public String getSourceModCfgPath(InstanceVO instance) { return getCfgPath(instance) + "/sourcemod"; }
    public String getServerCfgPath(InstanceVO instance) { return getCfgPath(instance) + "/server.cfg"; }
    public String getMaplistPath(InstanceVO instance) { return getAddonsPath(instance) + "/maplist.txt"; }
    public String getMotdPath(InstanceVO instance) { return getGamePath(instance) + "/motd.txt"; }
    public String getHostInfoPath(InstanceVO instance) { return getGamePath(instance) + "/host.txt"; }
    public String getHostnameConfigPath(InstanceVO instance) { return getSourceModConfigsPath(instance) + "/l4d2_hostname.txt"; }
    public String getAdminsIniPath(InstanceVO instance) { return getSourceModConfigsPath(instance) + "/admins_simple.ini"; }
}
```

#### GBK 编码工具

```java
public class GbkCodecUtil {
    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    public static String gbkToUtf8(byte[] bytes) { return new String(bytes, GBK); }
    public static byte[] utf8ToGbk(String text) { return text.getBytes(GBK); }

    /** 自动检测 BOM 与编码 */
    public static String decodeAuto(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, UTF_8);
        }
        return new String(bytes, GBK);
    }
}
```

`FileAccessService` 接口扩展（需同步 core 与 standalone 实现）：
- `readTextFile(hostId, path, Charset)` — 指定编码读取
- `tailFile(hostId, path, long offset, Consumer<String>)` — 远程 tail
- `getFileBytes(hostId, path, long offset, long length)` — 范围读取

### 3.3 压缩包解压

#### 依赖

```xml
<!-- plugin-l4d2-core/pom.xml 新增 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-compress</artifactId>
    <version>1.26.1</version>
</dependency>
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.9</version>
</dependency>
```

#### 统一入口

```java
@Component
public class ArchiveExtractService {
    public List<File> extract(File archiveFile, String originalFilename) {
        String ext = FilenameUtils.getExtension(originalFilename).toLowerCase();
        switch (ext) {
            case "zip": return extractZip(archiveFile);
            case "rar": return extractRar(archiveFile);
            case "7z":  return extract7z(archiveFile);
            default: throw new IllegalArgumentException("不支持的压缩格式: " + ext);
        }
    }
}
```

#### ZIP（GBK 文件名）

```java
private List<File> extractZip(File zipFile) {
    try (ZipFile zip = new ZipFile(zipFile, Charset.forName("GBK"))) {
        // 解压并返回根目录列表（可能是单 left4dead2/ 或多个 PluginName/left4dead2/）
    }
}
```

RAR/7z 通过 `commons-compress` 的 `RarArchiveInputStream` 和 `SevenZFile` 实现。

#### VPK 检测

VPK 不是压缩格式，是 L4D2 自有的二进制包格式。检测 magic `0x55aa1234`（小端 `34 12 AA 55`）：

```java
public static boolean isVpkFile(byte[] header) {
    return header.length >= 4
        && (header[0] & 0xFF) == 0x34
        && (header[1] & 0xFF) == 0x12
        && (header[2] & 0xFF) == 0xAA
        && (header[3] & 0xFF) == 0x55;
}
```

### 3.4 文件名清洗

```java
public class FilenameSanitizeUtil {
    private static final Pattern INVALID_CHARS = Pattern.compile("[^\\p{L}\\p{N}\\-_]+");
    private static final Set<String> RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9",
        "LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9"
    );

    public static String sanitize(String filename) {
        String name = INVALID_CHARS.matcher(filename).replaceAll("_");
        String base = FilenameUtils.getBaseName(name);
        if (RESERVED.contains(base.toUpperCase())) name = "_" + name;
        if (name.length() > 200) name = name.substring(0, 200);
        return name;
    }
}
```

### 3.5 SourceMod cfg 解析器

```java
@Component
public class SourceModCfgParser {
    private static final Pattern KV_PATTERN =
        Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]+)\"\\s*(?://\\s*(.*))?");

    /** 解析 cfg 文件内容，返回配置项列表 */
    public List<ConfigItem> parse(String content);

    /** 将配置项列表写回 cfg 格式，保留原始注释和元数据 */
    public String serialize(List<ConfigItem> items, String originalContent);

    private void parseMetadata(String comment, ConfigItem item);
}
```

支持元数据注释：`// Default: 1.0`、`// Min: 0`、`// Max: 10`、`// 描述文本`

### 3.6 admins_simple.ini 解析器

```java
@Component
public class AdminsIniParser {
    private static final Pattern LINE_PATTERN =
        Pattern.compile("^\"([^\"]+)\"\\s+\"([^\"]+)\"(?:\\s*//\\s*(.*))?$");

    public List<AdminEntry> parse(String content);
    public String serialize(List<AdminEntry> entries);
    public String addEntry(String content, AdminEntry entry);
    public String removeEntry(String content, String steamId);
    public String updateEntry(String content, AdminEntry entry);
}
```

### 3.7 VPK 二进制裁剪服务

```java
@Service
public class VpkTrimService {
    private static final int VPK_MAGIC = 0x55AA1234;
    private static final int VPK_VERSION = 1;

    /**
     * 裁剪 VPK 文件（原地或备份后裁剪）
     * @return 裁剪前后大小差异
     */
    public TrimResult trim(File vpkFile, boolean backup);

    public MissionInfo parseMission(File vpkFile);
}
```

**实现要点**：
- 用 `FileChannel` + `ByteBuffer`（`ByteOrder.LITTLE_ENDIAN`）
- Tree 区结构：每条目 = `ext \\0 path \\0 name \\0 suffix \\0 crc(4) preBytes(4) archiveIdx(2) offset(4) length(4) preload(prepBytes)`
- Tree 末尾以 16 字节的 `\0` 终止
- Chunk 区紧跟 Tree，文件物理位置 = `headerSize + treeSize + offset`
- 裁剪策略：在 Tree 中删除条目 + 调整后续条目的 offset + 重写 chunk 区
- 需移除的文件模式：`.vmf`/`.vmx`、`materials/*.vtf`、`sound|sounds/*.mp3/*.wav`、`models/*.vvd/*.vtx`

### 3.8 外部 HTTP 客户端封装

```java
@Component
public class ExternalHttpClient {
    private final RestClient restClient;

    public File download(String url, String filename, String referer,
                         ProgressCallback callback, CancelToken cancelToken);

    public <T> T getForObject(String url, Class<T> type, Map<String, ?> params);
    public <T> T postForObject(String url, Object body, Class<T> type);
}
```

**并发控制**：用 `Semaphore` 限制全局并发下载数（默认 3，配置可调）。

### 3.9 Steam ID 工具

```java
public class SteamIdUtil {
    private static final long STEAM64_BASE = 76561197960265728L;

    public static long toSteam64(String steamId2);  // STEAM_0:1:xxx → steam64
    public static String toSteamId2(long steam64);  // steam64 → STEAM_0:1:xxx
    public static boolean isValid(String steamId);
}
```

### 3.10 GeoIP 服务

```java
@Service
public class GeoIpService {
    private Searcher searcher;

    @PostConstruct
    public void init();  // 从 classpath 加载 ip2region.xdb

    public String query(String ip);          // 国家|区域|省份|城市|ISP
    public String queryProvinceCode(String ip);
}
```

依赖：
```xml
<dependency>
    <groupId>org.lionsoul</groupId>
    <artifactId>ip2region</artifactId>
    <version>2.7.0</version>
</dependency>
```

xdb 文件放置：`plugin-l4d2-core/src/main/resources/geoip/ip2region.xdb`（随插件 JAR 打包）。

### 3.11 PluginContext 扩展

`L4D2Config` 通过 `@ConfigurationProperties` 注入：
- **PF4J 模式**：主应用 `application.yml` 中配置 `plugin.l4d2.*`，`PluginSpringContextFactory` 将 `Environment` 暴露给子容器，`@ConfigurationProperties` 自动绑定
- **Standalone 模式**：standalone `application.yml` 直接配置

`L4D2Extension.onLoad(context)` 时将关键配置写入 `PluginContext.getCustomProperties()`，便于运行时查询。

### 3.12 依赖总结

`plugin-l4d2-core/pom.xml` 新增依赖（全部 provided，standalone 模式 compile 传递）：

```xml
<!-- 压缩解压 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-compress</artifactId>
    <version>1.26.1</version>
</dependency>
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.9</version>
</dependency>

<!-- GeoIP -->
<dependency>
    <groupId>org.lionsoul</groupId>
    <artifactId>ip2region</artifactId>
    <version>2.7.0</version>
</dependency>

<!-- 系统监控（容器内 fallback 用） -->
<dependency>
    <groupId>com.github.oshi</groupId>
    <artifactId>oshi-core</artifactId>
    <version>6.5.0</version>
</dependency>
```

---

## §4 功能模块详细设计

### 模块 1：服务器信息管理（ServerInfoController）

**对齐源项目**：`controller/server_info.go`

**端点**（前缀 `/api/plugin/l4d2/server-info`）：
- `GET /get?instanceId=` — 获取 hostname/motd/host 三个字段
- `POST /update` — 更新字段，body: `{ instanceId, hostname?, motd?, host? }`

**Service 逻辑**：
- 读：`fileAccessService.readTextFile(hostId, getHostnameConfigPath(instance), GBK)` → GBK→UTF-8
- 写：`fileAccessService.writeTextFile(hostId, path, UTF-8→GBK bytes)`
- hostname 通过 SourceMod 插件（`l4d2_hostname.txt`）动态设置；motd/host 直接覆盖 `motd.txt`/`host.txt`
- 三个字段独立更新，未提供的字段不修改

### 模块 2：SourceMod 日志 SSE 流（LogsController）

**对齐源项目**：`controller/logs.go`

**端点**（前缀 `/api/plugin/l4d2/logs`）：
- `GET /files?instanceId=` — 日志文件列表（`addons/sourcemod/logs/L\d{8}.log` + `errors_\d{8}.log`）
- `GET /content?instanceId=&file=` — 完整日志内容（GBK 解码，限 200KB）
- `GET /stream?instanceId=&file=` — SSE 实时日志流

**Service 逻辑**：
- SSE 用 `SseEmitter`（超时设为 0 = 无限）
- 启动时读取文件末尾 200KB 历史推送
- 之后用 `FileAccessService.tailFile` 远程 tail
- **简化方案**：每秒轮询文件大小，若增大则范围读取增量内容并 GBK→UTF-8 推送；文件不存在时等待
- 客户端断开时 `emitter.onCompletion/onTimeout/onError` 取消轮询任务

### 模块 3：备份还原（BackupController）

**对齐源项目**：`controller/plugins.go`（备份端点部分）+ `logic/backup.go`

**端点**（前缀 `/api/plugin/l4d2/backups`）：
- `GET /list?instanceId=` — 备份列表
- `POST /create` — 创建备份，body: `{ instanceId, name, description? }`
- `POST /restore` — 还原，body: `{ instanceId, backupId }`
- `POST /rename` — 重命名
- `DELETE /{backupId}` — 删除备份
- `GET /{backupId}` — 备份详情
- `POST /export` — 导出单个备份为 JSON 文件下载
- `POST /import` — 导入备份（multipart JSON）

**Service 逻辑**：
- **创建备份**（`captureAll`）：
  1. 调用 `PluginInstallService.listEnabledPlugins(instanceId)` 获取启用插件列表
  2. 读 `admins_simple.ini` 内容
  3. 读 `motd.txt`/`host.txt`/`l4d2_hostname.txt`
  4. 解析 `server.cfg` 提取 `sv_tags`/`sv_allow_lobby_connect_only`/`sv_steamgroup` + `// [L4D2-MANAGER-CUSTOM]` 标记的自定义块
  5. 序列化为 `PluginBackupSpec.content`，通过 `ExtensionClient.create` 持久化
- **还原备份**（`restore`）：
  1. 通过 `ExtensionClient.getById` 获取备份内容
  2. 调用 `PluginInstallService.disableAllPlugins(instanceId)`
  3. 调用 `PluginInstallService.enablePlatformPlugins(instanceId)`（平台插件优先，如 1.11 插件平台）
  4. 启用备份中其他插件
  5. 写回 admins_simple.ini → RCON `sm_reloadadmins`
  6. 写回 hostname/motd/host
  7. 写回 server.cfg（合并自定义块）
  8. RCON `sm plugins reload_all` 或逐个 reload

### 模块 4：插件配置 cfg（PluginConfigController）

**对齐源项目**：`controller/plugin_config.go` + `logic/plugin_config.go` + `logic/config_parser.go`

**端点**（前缀 `/api/plugin/l4d2/plugin-config`）：
- `GET /get?instanceId=&pluginName=` — 获取配置项列表
- `POST /update` — 更新配置
- `GET /candidates?instanceId=&pluginName=` — 列出候选 cfg 文件路径

**Service 逻辑**：
- **候选路径推导**（`pluginName` → cfg 文件）：
  - 优先匹配前缀 `l4d2_`/`l4d_`：如 `l4d2_ai_upgrade` → `cfg/sourcemod/l4d2_ai_upgrade.cfg`
  - 否则用插件名直接匹配：`{pluginName}.cfg`
  - 候选路径列表：`addons/sourcemod/plugins/{pluginName}.cfg`、`cfg/sourcemod/{pluginName}.cfg`
- **读**：第一个存在的候选文件 → GBK→UTF-8 → `SourceModCfgParser.parse`
- **写**：`SourceModCfgParser.serialize`（保留注释和元数据）→ UTF-8→GBK → 写回

### 模块 5：插件商店（PluginStoreController）

**对齐源项目**：`controller/plugins.go`（store 部分）+ `logic/plugin_store.go`

**端点**（前缀 `/api/plugin/l4d2/plugin-store`）：
- `GET /list?keyword=&category=&page=&size=` — 商店插件列表（缓存 10 分钟）
- `GET /{pluginId}` — 商店插件详情（含 README）
- `GET /{pluginId}/readme` — README Markdown 内容
- `POST /download` — 下载到指定实例
- `GET /tasks?instanceId=` — 下载任务列表（复用 `DownloadTaskResource`，source=STORE）
- `POST /tasks/{taskId}/cancel` — 取消下载

**Service 逻辑**：
- GitHub API `GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1` 获取目录树
- 每个插件目录含 `README.md` + `plugin.zip`（Git LFS）
- 检测 LFS pointer：文件内容以 `version https://git-lfs.github.com/spec/v1` 开头
- LFS BatchAPI：`POST /repos/{owner}/{repo}/info/lfs/objects/batch` 获取真实下载链接
- 3 并发下载（`Semaphore`）
- 下载完成后调用 `PluginInstallService.installFromLocalFile(instanceId, downloadedFile)` 走标准安装流程

### 模块 6：预设系统（PresetController）

**对齐源项目**：`logic/preset.go` + `backend/preset.yaml`

**端点**（前缀 `/api/plugin/l4d2/presets`）：
- `GET /list` — 预设列表
- `GET /{presetId}` — 预设详情
- `POST /{presetId}/apply?instanceId=` — 应用预设

**Service 逻辑**：
- `preset.yaml` 内嵌在 `plugin-l4d2-core/src/main/resources/preset.yaml`
- 4 个预设场景：多特战役、娱乐多特战役、纯净战役、官图肉鸽模式
- 平台映射：检测实例所在平台 → 选择对应平台插件（如 `1.11插件平台`）
- **应用流程**：
  1. `PluginInstallService.disableAllPlugins(instanceId)`
  2. 启用预设中的平台插件（优先）
  3. 启用预设中其他插件（每个走 `enableAndLoad` 流程）
  4. 应用预设中的 cfg 覆盖（如有）

### 模块 7：插件管理重构（PluginManageController）

**对齐源项目**：`controller/plugins.go`（CRUD 部分）+ `logic/plugins.go`

**端点扩展**（前缀 `/api/plugin/l4d2/plugins`）：
- 现有：`GET /list`、`PUT /{filename}/toggle`、`POST /upload`、`DELETE /{filename}`
- 新增：
  - `POST /enable-load?instanceId=&pluginName=` — 启用并 RCON 加载（带失败回滚）
  - `POST /disable-unload?instanceId=&pluginName=` — RCON 卸载并禁用
  - `POST /batch-enable` — 批量启用
  - `POST /batch-disable` — 批量禁用
  - `GET /export-all/start?instanceId=` — 启动全量导出任务
  - `GET /export-all/status?instanceId=` — 查询导出进度
  - `GET /export-all/download?instanceId=` — 下载导出的 ZIP
  - `POST /export-all/cancel?instanceId=` — 取消导出
- 重构现有：
  - `POST /upload` 改为调用 `PluginInstallService.installFromUpload`，统一处理 ZIP/RAR/7z/VPK
  - `PUT /{filename}/toggle` **废弃**，由 `enable-load` / `disable-unload` 替代（保留兼容期 1 个版本，标记为 `@Deprecated`）

**Service 逻辑**：

`PluginInstallService` 核心职责：

```java
@Service
public class PluginInstallService {
    public InstallResult installFromUpload(Long instanceId, MultipartFile file);
    void installPlugin(Long instanceId, File pluginDir);
    public RconResult enableAndLoad(Long instanceId, String pluginName);
    public RconResult disableAndUnload(Long instanceId, String pluginName);
    public List<PluginInfo> listPlugins(Long instanceId);
    public List<String> listEnabledPlugins(Long instanceId);
    public void disableAllPlugins(Long instanceId);
    public void enablePlatformPlugins(Long instanceId);
}
```

**fileRefs 引用计数**：
- 共享文件（cfg、translations）可能被多个插件引用
- 持久化到 `addons/sourcemod/.file_refs.json`：file path → 引用它的插件名集合
- 删除插件时，从 map 移除该插件对每个共享文件的引用，归零则删除文件
- 每次启动加载该文件

**PluginExportService 逻辑**：
- 创建临时目录
- 遍历 `addons/sourcemod/plugins/` 所有 .smx + 对应 cfg + translations
- 打包为 ZIP（保留 `left4dead2/` 根目录结构）
- 30 分钟过期清理
- 任务状态用内存 Map 跟踪（不持久化，重启失效可接受）

### 模块 8：地图管理增强（MapController 重构）

**对齐源项目**：`controller/maps.go` + `controller/map_hot_reload.go` + `controller/map_trim.go`

**端点扩展**（前缀 `/api/plugin/l4d2/maps`）：
- 现有：`GET /list`、`POST /upload`、`DELETE /{name}`
- 新增：
  - `POST /hot-reload?instanceId=` — 地图热重载（RCON 命令可配置）
  - `POST /trim?instanceId=&mapName=` — VPK 手动裁剪（带备份）
  - `POST /trim-batch` — 批量裁剪
  - `GET /{mapName}/mission` — 解析 VPK mission 信息
  - `POST /upload-chunk-init` — 分片上传初始化（与 ChunkUploadController 集成）

**Service 逻辑**：
- `MapController.upload` 复用 `ChunkUploadService` 完成大文件接收后，调用 `installMap(instanceId, vpkFile)`
- `installMap`：VPK magic 校验 → 复制到 `addons/{filename}.vpk` → 可选自动裁剪（`L4D2Config.vpk-trim.enabled=true`）
- `trim`：
  1. 备份原 VPK 为 `{filename}.vpk.bak.{timestamp}`
  2. 调用 `VpkTrimService.trim` 裁剪
  3. 失败则回滚（恢复备份）
  4. 成功则删除备份（或保留 1 个备份）
- `hot-reload`：调用 `RconService.hotReloadMaps(instanceId)`，命令默认 `update_addon_paths; mission_reload`

### 模块 9：分片上传（ChunkUploadController）

**对齐源项目**：`controller/chunk_upload.go` + `controller/file_processor.go`

**端点**（前缀 `/api/plugin/l4d2/chunk-upload`）：
- `POST /init` — 初始化上传，body: `{ instanceId, filename, totalSize, totalChunks, targetPath? }` → 返回 `uploadId`
- `POST /chunk` — 上传分片，multipart: `uploadId, index, chunk` → 返回接收状态
- `GET /status?uploadId=` — 查询上传进度
- `POST /complete?uploadId=` — 完成上传，合并分片并处理
- `POST /cancel?uploadId=` — 取消并清理

**Service 逻辑**：
- 分片大小：5MB（`L4D2Config.chunk-upload.chunk-size`）
- 总大小上限：2GB
- uploadId：UUIDv4 严格校验
- 元数据通过 `ChunkUploadResource` 持久化（含 receivedIndexes 集合）
- 实际分片文件存到 `~/game-platform-l4d2/chunk-temp/{uploadId}/chunk-{index}`
- **磁盘空间检查**：本机磁盘使用率 > 90% 拒绝新上传
- **完成处理**：
  1. 校验所有分片已接收
  2. 合并分片为完整文件
  3. 调用 `FileProcessorService.process(mergedFile, originalFilename, instanceId)`
     - ZIP/RAR/7z → `ArchiveExtractService.extract` → 识别为插件或地图
     - VPK → 复制到 `addons/`（地图）
  4. 上传到远程主机（通过 `FileAccessService.uploadLocalFile`）
  5. 清理临时文件
- **过期清理**：每小时扫描，删除 6 小时未完成的记录 + 临时文件

### 模块 10：下载器（DownloadController）

**对齐源项目**：`controller/download.go` + `logic/link_parser.go` + `logic/workshop.go`

**端点**（前缀 `/api/plugin/l4d2/download`）：
- `POST /url` — URL 直接下载
- `POST /workshop` — Workshop 下载
- `GET /tasks?instanceId=&status?` — 下载任务列表
- `GET /tasks/{taskId}` — 任务详情（含进度）
- `POST /tasks/{taskId}/cancel` — 取消下载

**Service 逻辑**：

`DownloadService`（URL 下载）：
- 用 `ExternalHttpClient.download` 下载到本机临时目录
- 用 `FileAccessService.uploadLocalFile` 上传到远程 `targetPath`
- 文件名清洗：`FilenameSanitizeUtil.sanitize`
- VPK magic 检测：若下载内容是 VPK，自动设置 targetPath 为 `addons/`

`WorkshopDownloadService`（Steam Web API）：
- 调用 Steam Web API `IPublishedFileService/GetDetails`（需 API key）
- **降级策略**：
  1. 优先用 Steam API 返回的 `file_url`（若有）
  2. 若为空，记录元信息到 `DownloadTaskResource.metadata`，返回 `pending_manual` 状态
  3. 提示用户配置第三方代理 URL（`L4D2Config.workshop.proxy-url`）

**并发控制**：`Semaphore(3)` 限制全局下载并发数

### 模块 11：玩家统计（PlayerStatsController）

**对齐源项目**：`controller/player_stats.go` + `model/player_stats.go`

**端点**（前缀 `/api/plugin/l4d2/player-stats`）：
- `GET /snapshots?instanceId=&start=&end=&page=&size=` — 快照列表
- `GET /snapshots/{snapshotId}/players` — 快照内玩家详情
- `GET /players/search?instanceId=&keyword=` — 按 SteamID 或名称搜索玩家历史
- `GET /stats/summary?instanceId=&start=&end=` — 聚合统计

**Service 逻辑**：
- **采集器**（`ScheduledExecutorService`，每 10 分钟）：
  1. 遍历所有运行中的 L4D2 实例
  2. 对每个实例调用 `RconService.executeCommand(instanceId, "status")`
  3. 解析为 `PlayerStatSnapshotSpec` + 多个 `PlayerStatPlayerSpec`
  4. 通过 `ExtensionClient.create` 持久化
- **GeoIP 查询**：每个玩家 IP 通过 `GeoIpService.query` 填充 location
- **清理任务**：每小时扫描，删除 30 天前的快照和对应玩家记录
- **聚合查询**：按小时/天分组，计算玩家数峰值、独立玩家数、平均时长

### 模块 12：Steam API 游玩时长（PlaytimeController）

**对齐源项目**：`controller/playtime.go`

**端点**（前缀 `/api/plugin/l4d2/playtime`）：
- `GET /?steamId=&instanceId=` — 查询玩家 L4D2 游玩时长

**Service 逻辑**：
- SteamID 格式转换：`SteamIdUtil.toSteam64`
- 并发请求两个 API（`CompletableFuture.allOf`）：
  - `IPlayerService/GetOwnedGames/v0001`：`appid=550`，返回 `playtime_forever`（分钟→小时）
  - `ISteamUserStats/GetUserStatsForGame/v0002`：`appid=550`，返回 `stats.TotalPlayTime.Total`（秒→小时）
- 任一成功即返回，都失败抛异常
- API key 缺失时返回明确错误

### 模块 13：监控采集重构（MonitorController）

**对齐源项目**：`controller/monitor.go` + `model/metric.go`

**端点扩展**（前缀 `/api/plugin/l4d2/monitor`）：
- 现有：`GET /status`、`GET /history`
- 重构：
  - `GET /current?instanceId=` — 实时指标
  - `GET /history?instanceId=&start=&end=&interval=` — 历史时序数据（已降采样）
  - `GET /network-interfaces?instanceId=` — 网络接口列表

**Service 逻辑**：
- **采集器**（`ScheduledExecutorService`，每 1 秒）：
  - PF4J 模式：调用 `HostQueryService.getHostResourceInfo(hostId)` 获取宿主实时指标
  - Standalone 模式：用 `oshi-core` 直接采集本机指标
  - 通过 `ExtensionClient.create` 持久化到 `SystemMetricResource`
- **降采样**：单实例超过 2000 条时降至 720 点
- **清理**：每小时删除 3 天前的记录
- **网络接口过滤**：忽略 `docker`/`veth`/`br-`/`lo` 接口

### 模块 14：服务器重启（RestartController）

**对齐源项目**：`controller/restart.go`

**端点**（前缀 `/api/plugin/l4d2/restart`）：
- `POST /?instanceId=` — 重启服务器

**Service 逻辑**：
- 方式 1（RCON）：当 `L4D2Config.restart.by-rcon=true` 时，调用 `RconService.restartServer(instanceId)` 执行 `_restart`
- 方式 2（容器命令）：默认方式，优先复用 `InstanceQueryService.restartInstance`，RCON 方式作为备选

### 模块 15：服务器配置多 tick 同步（ServerConfigController 增强）

**对齐源项目**：`controller/server_config.go`

**端点扩展**：
- 现有：`GET /get`、`POST /update`
- 增强 `POST /update`：
  - 同步更新到多 tick 版本：`server.cfg.128tick`、`server.cfg.100tick`、`server.cfg.60tick`、`server.cfg.30tick`
  - 自定义配置块管理：用 `// [L4D2-MANAGER-CUSTOM]` 标记
- 增强 `GET /get`：
  - 返回字段增加 `customConfig`、`tickVersions`

### 模块 16：版本信息（VersionController）

**对齐源项目**：`controller/version.go`

**端点**（前缀 `/api/plugin/l4d2/version`）：
- `GET /` — 返回 `{ version, buildTime, gitCommit }`

**Service 逻辑**：
- 版本号常量在 `L4D2Constants.VERSION`
- BuildTime / GitCommit 通过 Maven `git-commit-id-plugin` 注入到 `META-INF/MANIFEST.MF`

### 端点路径规范

所有端点路径前缀 `/api/plugin/l4d2/**`，遵循现有约定。

Path 参数风格：
- 资源 ID 用 path：`/{backupId}`、`/{presetId}`
- 查询条件用 query：`?instanceId=&status=`
- 创建/更新用 JSON body

### 异常处理

统一异常类（在 `plugin-l4d2-core` 中）：

```java
public class L4D2PluginException extends RuntimeException {
    private final String code;  // BUSINESS / RCON / FILE / NETWORK / EXTERNAL_API
    public L4D2PluginException(String code, String message) { ... }
    public L4D2PluginException(String code, String message, Throwable cause) { ... }
}
```

由 `GlobalExceptionHandler`（主应用提供，或 standalone 的 `StandaloneExceptionHandler`）捕获并转为 `Result.fail(msg)`。

---

## §5 前端页面详细设计

### 5.1 前端架构基础

**技术栈**（与现有保持一致）：Vue 3.4 + Vue Router 4.3 + Pinia 2.1 + Element Plus 2.6 + Axios 1.6 + Vite 5.2 + ECharts 5.5

**新增依赖**：
```json
{
  "marked": "^12.0.0",
  "dompurify": "^3.0.0",
  "dayjs": "^1.11.0"
}
```

### 5.2 路由配置扩展

`router/index.ts` 新增路由（全部 `meta.requiresInstance: true`）：

```typescript
const routes = [
  // 现有
  { path: '/', redirect: '/dashboard' },
  { path: '/instance-select', component: () => import('@/pages/InstanceSelect.vue') },
  { path: '/dashboard', component: () => import('@/pages/Dashboard.vue') },
  { path: '/rcon', component: () => import('@/pages/Rcon.vue') },
  { path: '/maps', component: () => import('@/pages/Maps.vue') },
  { path: '/plugins', component: () => import('@/pages/Plugins.vue') },
  { path: '/admins', component: () => import('@/pages/Admins.vue') },
  { path: '/monitor', component: () => import('@/pages/Monitor.vue') },
  { path: '/server-config', component: () => import('@/pages/ServerConfig.vue') },

  // 新增
  { path: '/server-info', component: () => import('@/pages/ServerInfo.vue') },
  { path: '/plugin-store', component: () => import('@/pages/PluginStore.vue') },
  { path: '/plugin-config', component: () => import('@/pages/PluginConfig.vue') },
  { path: '/backup', component: () => import('@/pages/Backup.vue') },
  { path: '/preset', component: () => import('@/pages/Preset.vue') },
  { path: '/download', component: () => import('@/pages/Download.vue') },
  { path: '/player-stats', component: () => import('@/pages/PlayerStats.vue') },
  { path: '/logs', component: () => import('@/pages/Logs.vue') },
  { path: '/playtime', component: () => import('@/pages/Playtime.vue') },
]
```

### 5.3 菜单布局

`MainLayout.vue` 左侧菜单分组：

```
运维管理
├── 仪表盘 (Dashboard)
├── 服务器信息 (ServerInfo)
├── 服务器配置 (ServerConfig)
├── 监控 (Monitor)
└── 日志 (Logs)

游戏管理
├── 地图管理 (Maps)
├── 插件管理 (Plugins)
├── 插件商店 (PluginStore)
├── 插件配置 (PluginConfig)
├── 预设场景 (Preset)
└── 备份还原 (Backup)

玩家管理
├── RCON 控制台 (Rcon)
├── 玩家统计 (PlayerStats)
├── 游玩时长 (Playtime)
└── 管理员 (Admins)

资源下载
└── 下载器 (Download)
```

### 5.4 页面详细设计

#### Dashboard.vue（重构）

**布局**：4 个统计卡片 + 2 个图表 + 快捷操作区

- 卡片：服务器状态（在线/离线）、玩家数、当前地图、运行时长
- 图表 1：CPU/内存 实时折线（30 秒滑动窗口，调用 `monitor/current` 轮询）
- 图表 2：网络上行/下行 实时折线
- 快捷操作：换图、踢人、重启、热重载地图
- 版本号显示在右上角

#### ServerInfo.vue（新增）

**布局**：3 个独立可编辑卡片

- hostname：textarea + 保存按钮（同步到 `l4d2_hostname.txt`）
- motd：textarea + 保存按钮（同步到 `motd.txt`）
- host：textarea + 保存按钮（同步到 `host.txt`）
- 每个卡片独立加载/保存，loading 状态隔离
- 字符计数显示（hostname 限 64 字符）

#### Logs.vue（新增）

**布局**：日志文件列表（左）+ 日志内容（右）

- 左侧：`logs/files` 返回的文件列表，按时间倒序
- 右侧上：历史日志内容（200KB 限制，可搜索、可复制）
- 右侧下：实时 SSE 流（自动滚动到底部，可暂停/继续）
- 顶部工具栏：文件选择、搜索框、清屏按钮、暂停按钮
- 颜色区分：errors_*.log 文件标红

#### Backup.vue（新增）

**布局**：备份列表 + 操作工具栏

- 工具栏：创建备份按钮、导入备份按钮、导出全部按钮
- 表格：名称、描述、创建时间、内容摘要、操作列
- 操作：还原（二次确认）、重命名、详情、导出、删除
- 还原进度弹窗：显示禁用→启用→同步各步骤进度

#### PluginConfig.vue（新增）

**布局**：插件选择 + 配置项编辑

- 顶部：插件下拉选择，加载后显示候选 cfg 路径
- 表格：配置项 key、当前值、默认值、Min/Max、描述、行号
- 操作：双击单元格编辑值、保存、重置默认值、刷新
- 高级模式：切换显示原始 cfg 内容

#### PluginStore.vue（新增）

**布局**：商店列表 + 详情弹窗

- 顶部：搜索框、分类筛选
- 卡片网格：每个插件卡片显示名称、描述摘要、README 预览、下载按钮
- 详情弹窗：完整 README（Markdown 渲染）、版本、下载量、安装按钮
- 已安装标识：与本地 `plugins/list` 对比标记
- 任务进度：下载中显示进度条

#### Preset.vue（新增）

**布局**：预设卡片列表

- 4 个预设卡片
- 每张卡片：名称、描述、插件数、平台标识、应用按钮
- 应用流程：二次确认 → 进度 → 完成通知
- 当前激活预设高亮显示

#### Plugins.vue（重构）

**布局**：标签页（已安装 / 批量操作 / 导出）

- **已安装**：表格（名称、状态、来源、HasSMX、HasConfig、操作）
  - 操作：启用并加载、禁用并卸载、配置、README、删除
  - 共享文件提示：删除时若 fileRefs > 1，弹窗提示
- **批量操作**：复选框列表 + 批量启用/禁用按钮
- **导出**：导出全部按钮、进度条、下载链接、取消按钮
- 上传区域：拖拽上传，支持 ZIP/RAR/7z/VPK

#### Maps.vue（重构）

**布局**：地图列表 + 上传/操作工具栏

- 表格：地图名、VPK 大小、mission 信息、是否已裁剪、操作
- 操作：热重载、裁剪、mission 详情、删除
- 上传：支持分片上传（>100MB 自动启用分片）
- 批量操作：批量裁剪、批量删除

#### Download.vue（新增）

**布局**：下载任务列表 + 新建下载

- 新建下载区域（顶部 Tab）：
  - URL 下载：URL 输入框、文件名、目标路径
  - Workshop 下载：PublishedFileId 输入框、查询按钮、下载按钮
- 任务列表：来源、文件名、进度、状态、开始时间、操作
- 实时进度条更新（2 秒轮询）
- 配置提示：若 `STEAM_API_KEY` 未配置，Workshop 标签显示提示

#### PlayerStats.vue（新增）

**布局**：时间范围选择 + 快照列表 + 玩家详情

- 顶部：时间范围选择器、查询按钮
- 统计卡片：总快照数、在线峰值、独立玩家数、平均时长
- 图表：玩家数变化折线（按小时聚合）
- 快照表格：时间、在线人数、地图、难度、游戏模式、操作
- 玩家详情弹窗：快照内所有玩家
- 搜索：按 SteamID 或名称搜索历史记录

#### Playtime.vue（新增）

**布局**：查询表单 + 结果展示

- 输入：SteamID（支持 STEAM_0:1:xxx 格式）
- 结果：总时长（小时）、实战时长（小时）、Steam64 ID
- 历史查询记录（最近 10 条，本地存储）

#### Monitor.vue（重构）

**布局**：实时卡片 + 历史图表

- 实时卡片：CPU、内存、Swap、磁盘、网络上行、网络下行（每秒刷新）
- 实时图表：1 分钟滑动窗口折线（CPU + 内存双 Y 轴）
- 历史图表：时间范围选择器 + ECharts 折线
- 网络接口过滤：复选框选择
- 自动刷新开关

#### ServerConfig.vue（增强）

**布局**：基础字段 + 自定义配置块 + 多 tick 同步

- 基础字段：sv_tags、sv_allow_lobby_connect_only、sv_steamgroup
- 自定义配置块：textarea
- 多 tick 同步：复选框选择要同步的版本
- 保存按钮：先预览变更，二次确认后写入

### 5.5 公共组件

`components/` 下新增可复用组件：

| 组件 | 用途 | 使用页面 |
|------|------|---------|
| `InstanceSelector.vue` | 实例选择下拉 | 所有页面顶栏 |
| `MapSelectorModal.vue` | 地图选择弹窗 | Rcon、Preset |
| `PluginSelectorModal.vue` | 插件多选弹窗 | Preset、Backup |
| `SteamIdInput.vue` | SteamID 输入+校验 | Admins、Playtime、PlayerStats |
| `ProgressBar.vue` | 通用进度条 | Download、PluginStore、Plugins |
| `MarkdownRenderer.vue` | Markdown 渲染 | PluginStore |
| `TimeRangePicker.vue` | 时间范围选择器 | Monitor、PlayerStats |
| `LogViewer.vue` | 日志查看器 | Logs |
| `ConfirmDialog.vue` | 二次确认弹窗 | 全局 |
| `EmptyState.vue` | 空状态占位 | 全局 |

### 5.6 API 封装扩展

`api/index.ts` 按业务域分组扩展：

```typescript
export const api = {
  // 现有
  server, map, plugin, rcon, monitor, admin, file,

  // 新增
  serverInfo: { get, update },
  pluginConfig: { get, update, candidates },
  pluginStore: { list, detail, readme, download, tasks, cancelTask },
  backup: { list, create, restore, rename, delete, detail, export, import },
  preset: { list, detail, apply },
  logs: { files, content, streamUrl },
  download: { url, workshop, tasks, taskDetail, cancelTask },
  playerStats: { snapshots, players, search, summary },
  playtime: { get },
  chunkUpload: { init, uploadChunk, status, complete, cancel },
  restart: { restart },
  version: { get },
}
```

### 5.7 SSE 实现

前端用原生 `EventSource` 订阅 SSE 流：

```typescript
// composables/useLogStream.ts
export function useLogStream(instanceId: string, file: string) {
  const logs = ref<string[]>([])
  const connected = ref(false)
  let eventSource: EventSource | null = null

  function connect() {
    const url = api.logs.streamUrl(instanceId, file)
    eventSource = new EventSource(url, { withCredentials: true })
    eventSource.onopen = () => { connected.value = true }
    eventSource.onmessage = (e) => { logs.value.push(e.data) }
    eventSource.onerror = () => {
      connected.value = false
      setTimeout(connect, 5000)  // 5 秒后自动重连
    }
  }

  function disconnect() {
    eventSource?.close()
    eventSource = null
    connected.value = false
  }

  onUnmounted(disconnect)

  return { logs, connected, connect, disconnect }
}
```

### 5.8 状态管理扩展

`stores/plugin.ts` 新增状态：

```typescript
export const usePluginStore = defineStore('plugin', () => {
  // 现有
  const instanceInfo = ref<InstanceInfo | null>(null)
  const authInfo = ref<AuthInfo | null>(null)

  // 新增
  const downloadTasks = ref<DownloadTask[]>([])
  const monitorRealtime = ref<SystemMetric | null>(null)
  const activeSSEConnections = ref<number>(0)

  async function refreshDownloadTasks() { ... }
  function startMonitorPolling(intervalMs = 1000) { ... }
  function stopMonitorPolling() { ... }

  return { ... }
})
```

### 5.9 构建配置调整

`vite.config.ts` 调整：

```typescript
{
  build: {
    outDir: '../plugin-l4d2-core/src/main/resources/ui',  // 不变
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'echarts': ['echarts'],
          'markdown': ['marked', 'dompurify'],
          'utils': ['dayjs', 'axios'],
        }
      }
    }
  }
}
```

---

## §6 实施顺序与验证策略

### 6.1 实施阶段划分（垂直切片）

按依赖关系将功能模块拆为 7 个阶段。

#### 阶段 0：基础设施（前置）

**目标**：抽取被多个模块依赖的公共能力。

**任务**：
1. 新增 `plugin-l4d2-core/pom.xml` 依赖（commons-compress / xz / ip2region / oshi-core）
2. 扩展 `FileAccessService` 接口：新增 `readTextFile(hostId, path, Charset)` / `tailFile(hostId, path, long offset, Consumer<String>)` / `getFileBytes(hostId, path, long offset, long length)` 三个方法
3. 在 core 的 `FileAccessServiceImpl` 和 standalone 的 `StandaloneFileAccessService` 同步实现
4. 实现 `util/` 工具类：`GbkCodecUtil` / `FilenameSanitizeUtil` / `SteamIdUtil`
5. 实现 `L4D2PathResolver` 组件
6. 实现 `ArchiveExtractService`（ZIP/RAR/7z）
7. 实现 `SourceModCfgParser` / `AdminsIniParser`
8. 扩展 `L4D2Config`（新增所有配置项）
9. 新增 `ExternalHttpClient` 组件
10. 准备 `preset.yaml` classpath 资源
11. 准备 `geoip/ip2region.xdb` classpath 资源

**验证**：单元测试覆盖工具类（GBK 编码、文件名清洗、SteamID 转换、cfg 解析、admins_ini 解析、ZIP 解压、VPK magic 检测）。

**不交付**：本阶段不产出可见功能。

#### 阶段 1：运维核心模块

**目标**：交付服务器信息管理 + SourceMod 日志 SSE + 备份还原三个模块。

**任务**：
1. `ServerInfoController` + Service
2. `LogsController` + `SourceModLogService`
3. `BackupController` + `BackupService`
4. `PluginBackupResource` 扩展资源
5. `BackupService` 依赖的 `PluginInstallService.listEnabledPlugins` 临时用最简实现，阶段 2 替换
6. 前端 `ServerInfo.vue` / `Logs.vue` / `Backup.vue`
7. `useLogStream` composable + `LogViewer` 组件

**验证**：
- 修改 hostname/motd/host 后能在游戏内看到效果
- 订阅 SSE 流，触发游戏事件后能看到实时日志
- 创建备份→修改插件状态→还原，验证配置回滚

#### 阶段 2：插件增强模块

**目标**：交付插件配置 cfg + 插件商店 + 预设系统 + 插件管理重构四个模块。

**任务**：
1. `PluginConfigController` + `SourceModCfgService`
2. `PluginStoreController` + `PluginStoreService`（GitHub API + Git LFS + 缓存）
3. `PresetController` + `PresetService`
4. **重构** `PluginManageController`
5. 实现 `PluginInstallService`（ZIP 解压、fileRefs、enable/disable + RCON load/unload）
6. 实现 `PluginExportService`
7. fileRefs 持久化到 `addons/sourcemod/.file_refs.json`
8. 前端 `PluginConfig.vue` / `PluginStore.vue` / `Preset.vue` 页面
9. 重构 `Plugins.vue`
10. `MarkdownRenderer` 组件 + `ProgressBar` 组件
11. 阶段 1 的 `BackupService` 切换为正式 `PluginInstallService` 依赖

**验证**：
- 上传 ZIP 插件包能正确安装
- 启用插件后 RCON `sm plugins list` 确认加载
- 删除共享 cfg 文件的插件时 fileRefs 提示正确
- 应用预设场景后插件列表与预设一致
- 浏览插件商店并下载安装一个插件
- 编辑插件 cfg 后游戏内立即生效

#### 阶段 3：地图增强模块

**目标**：交付 VPK 裁剪 + 地图热重载 + 分片上传。

**任务**：
1. `VpkTrimService`（纯 Java FileChannel + ByteBuffer）
2. **重构** `MapController`
3. `ChunkUploadController` + `ChunkUploadService`
4. `ChunkUploadResource` 扩展资源
5. 分片上传临时目录管理 + 过期清理任务
6. `FileProcessorService`（识别格式并路由）
7. 前端 `Maps.vue` 重构
8. 分片上传前端逻辑

**验证**：
- 上传 50MB 的 VPK 地图能正确安装
- 上传 500MB 的 VPK 地图自动启用分片上传
- 手动裁剪 VPK 后游戏内仍能正常加载
- 地图热重载后新地图立即出现
- 取消上传中任务，临时文件被清理

#### 阶段 4：下载体系

**目标**：交付 URL 下载器 + Workshop 下载器。

**任务**：
1. `DownloadController` + `DownloadService`（URL 下载）
2. `WorkshopDownloadService`（Steam Web API + 第三方代理降级）
3. 扩展 `DownloadTaskResource`
4. 下载任务并发控制（Semaphore 3）
5. 取消令牌
6. 前端 `Download.vue`

**验证**：
- 输入 URL 下载完成后文件出现在 `addons/`
- 输入 Workshop ID 能看到元信息
- 未配置 API key 时 Workshop 标签显示提示
- 下载中取消，状态变为 `cancelled`
- 同时下载 3 个文件，第 4 个排队等待

#### 阶段 5：数据采集模块

**目标**：交付玩家统计 + Steam API 游玩时长 + 监控采集重构。

**任务**：
1. `PlayerStatSnapshotResource` + `PlayerStatPlayerResource` 扩展资源
2. `PlayerStatsController` + `PlayerStatsService`
3. `PlaytimeController` + `PlaytimeService`
4. `GeoIpService`
5. **重构** `MonitorController` + `MonitorService`
6. 扩展 `SystemMetricResource`
7. `ScheduledExecutorService` 任务调度框架
8. 前端 `PlayerStats.vue` / `Playtime.vue` 页面
9. 重构 `Monitor.vue`
10. `TimeRangePicker` 组件

**验证**：
- 启动后 1 分钟内监控历史曲线开始绘制
- 玩家加入服务器后 10 分钟内 PlayerStats 出现快照
- 查询玩家历史能按 SteamID 找到所有快照
- 查询游玩时长返回正确数据
- 3 天前的监控数据被自动清理

#### 阶段 6：服务器控制与配置

**目标**：交付服务器重启 + ServerConfig 多 tick 同步 + RCON 增强 + 版本号。

**任务**：
1. `RestartController` + `RestartService`
2. **增强** `ServerConfigController`
3. **增强** `RconController`
4. `VersionController` + 版本常量
5. Maven `git-commit-id-plugin` 配置
6. 重构 `Dashboard.vue`
7. 增强 `ServerConfig.vue` / `Rcon.vue`

**验证**：
- 重启按钮点击后服务器在 30 秒内重启
- 修改 `sv_tags` 后多 tick 版本同步更新
- 自定义配置块修改后标记外内容不被覆盖
- RCON 设置最大玩家数立即生效
- 版本号在 Dashboard 显示正确

### 6.2 阶段依赖关系

```
阶段 0 (基础设施)
  ├─→ 阶段 1 (运维核心)
  │     └─→ 阶段 2 (插件增强) ── 依赖 PluginInstallService
  │           ├─→ 阶段 3 (地图增强)
  │           ├─→ 阶段 4 (下载体系)
  │           └─→ 阶段 5 (数据采集)
  │                 └─→ 阶段 6 (服务器控制)
  └─→ 阶段 5 也依赖阶段 0 的 GeoIpService
```

**关键依赖**：
- 阶段 1 的 BackupService 临时依赖 PluginInstallService 的最简实现，阶段 2 替换为正式实现
- 阶段 2 是核心阶段，PluginInstallService 被 1/3/4 多个阶段依赖
- 阶段 3/4/5 之间无强依赖，理论上可并行
- 阶段 6 是收尾

### 6.3 每阶段交付物清单

每个阶段完成时必须包含：

| 类别 | 内容 |
|------|------|
| 后端代码 | Controller + Service + DTO/VO + 扩展资源 + 测试 |
| 前端代码 | Vue 页面 + 组件 + API 封装 + 路由注册 |
| 配置 | `L4D2Config` 新增项 + `application.yml` 示例 |
| 文档 | 更新 `L4D2Extension.getManifest()` 的 features 列表 |
| 构建 | `mvn package -pl plugin-l4d2/plugin-l4d2-core -am` 通过 + `npm run build` 通过 |
| 双模式 | PF4J 模式 + standalone 模式均能启动并工作 |
| 验证 | 完成「验证」清单中所有项目 |

### 6.4 双模式同步策略

每个阶段实施时，PF4J 与 standalone 双模式必须同步：

**PF4J 模式**（主应用集成）：
- 通过 `InstanceQueryService` / `HostQueryService` / `FileAccessService` 获取宿主能力
- 配置从主应用 `application.yml` 的 `plugin.l4d2.*` 读取
- 鉴权由主应用 JWT 处理

**Standalone 模式**（独立运行）：
- 通过 `StandaloneInstanceQueryService` / `StandaloneHostQueryService` / `StandaloneFileAccessService`
- 配置从 standalone `application.yml` 读取
- 无鉴权（本地运行）

**验证流程**：
1. 先在 standalone 模式开发与测试
2. 通过后打包 PF4J JAR，部署到主应用 `plugins/` 目录
3. 重启主应用，验证扩展点加载、Controller 注册、前端资源访问

### 6.5 测试策略

#### 单元测试

- **工具类**：100% 覆盖（GbkCodecUtil / FilenameSanitizeUtil / SteamIdUtil / SourceModCfgParser / AdminsIniParser / VpkParser / VpkTrimService）
- **Service**：核心业务逻辑用 Mockito mock 掉 FileAccessService / RconService / ExtensionClient
- **扩展资源**：用 H2 内存库验证 ExtensionClient CRUD

#### 集成测试

- **Standalone 模式**：用 `@SpringBootTest` 启动 standalone 上下文，配合 mock SSH 服务器（Apache MINA SSHD 内嵌）验证完整流程
- **PF4J 模式**：用 `@SpringBootTest` 启动主应用上下文（含插件加载），验证 Controller 注册与扩展点加载

#### 手工验证清单

每阶段交付前在真实 L4D2 实例上手工执行：
- standalone 模式连接 WSL2 中的 L4D2 Docker 容器
- 验证每个端点的 happy path
- 验证关键错误场景（文件不存在、RCON 失败、网络中断）

### 6.6 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| RCON 协议实现差异 | status 解析失败 | 现有 `RconService` 已验证可用 |
| VPK 裁剪破坏文件 | 地图无法加载 | 必做备份 + 失败回滚 + 单元测试覆盖 |
| GitHub API 限流 | 插件商店不可用 | 缓存 10 分钟 + 错误降级提示 |
| Steam API key 缺失 | Workshop/Playtime 不可用 | 启动时检测 + 前端提示 |
| 大文件上传超时 | 分片上传失败 | 5MB 分片 + 断点续传 + 取消重试 |
| oshi-core 跨平台差异 | 监控数据不准 | PF4J 模式优先用宿主 HostQueryService |
| WSL2 网络/DNS 问题 | 远程文件操作失败 | 已有 hosts 刷新功能缓解 |
| GBK 编码文件混入 UTF-8 BOM | 解析乱码 | `GbkCodecUtil.decodeAuto` 自动检测 BOM |

### 6.7 完成标准

整个移植完成的判定标准：

1. **功能完整性**：源项目 21 个模块中，除明确跳过的 8 项（QQ 闪传/GeoIP 白名单/自服务码/ManagerConfig/JWT/IP 锁定/auth/审计）外，其余 13 个模块全部实现并可用
2. **双模式可用**：PF4J 插件 JAR 部署到主应用后无报错；standalone JAR 独立启动后所有功能可用
3. **前端完整**：9 个新增页面 + 8 个重构页面全部实现，菜单导航正常，三模式路由切换正常
4. **测试通过**：单元测试覆盖率 ≥ 60%（工具类 100%），集成测试覆盖核心场景
5. **构建通过**：`mvn clean package -pl plugin-l4d2/plugin-l4d2-core,plugin-l4d2/plugin-l4d2-standalone -am` + `npm run build` 全部成功
6. **文档同步**：`L4D2Extension.getManifest()` 的 features 与 apiEndpoints 完整列出所有端点

### 6.8 不在本设计范围

- **源项目的 Docker 部署脚本**：L4D2 插件不复制，仍用现有 `LinuxGsmDockerAdapter` 部署
- **源项目的 Windows/Linux 启动脚本**：standalone 模式用 `java -jar` 启动
- **源项目的 README/AGENTS.md**：插件文档独立维护
- **性能优化**：如批量 RCON 命令合并、SSE 连接池等
- **国际化**：源项目仅中文，本次保持中文
- **主题切换**：沿用主应用主题

---

## 附录 A：源项目模块与目标模块映射

| 源项目模块 (Go) | 目标模块 (Java) | 状态 |
|---------------|---------------|------|
| `controller/auth.go` | (跳过) | 主应用统一鉴权 |
| `controller/rcon.go` | `RconController` | 重构对齐 |
| `controller/plugins.go` | `PluginManageController` + `BackupController` + `PluginStoreController` | 拆分重构 |
| `controller/plugin_config.go` | `PluginConfigController` | 新增 |
| `controller/maps.go` | `MapController` | 重构 |
| `controller/map_hot_reload.go` | `MapController` | 合并 |
| `controller/map_trim.go` | `MapController` + `VpkTrimService` | 合并 |
| `controller/chunk_upload.go` | `ChunkUploadController` | 新增 |
| `controller/file_processor.go` | `FileProcessorService` | 转为 Service |
| `controller/download.go` | `DownloadController` | 新增 |
| `controller/monitor.go` | `MonitorController` | 重构 |
| `controller/player_stats.go` | `PlayerStatsController` | 新增 |
| `controller/logs.go` | `LogsController` | 新增 |
| `controller/server_config.go` | `ServerConfigController` | 增强 |
| `controller/server_info.go` | `ServerInfoController` | 新增 |
| `controller/admins.go` / `admin_manager.go` | `AdminController` | 已对齐 |
| `controller/audit.go` | (跳过) | 用户决定移除 |
| `controller/backup.go` | `BackupController` | 新增 |
| `controller/restart.go` | `RestartController` | 新增 |
| `controller/playtime.go` | `PlaytimeController` | 新增 |
| `controller/version.go` | `VersionController` | 新增 |
| `controller/plugin_config.go` | `PluginConfigController` | 新增 |
| `logic/workshop.go` | `WorkshopDownloadService` | 新增 |
| `logic/qq_flash_transfer.go` | (跳过) | 小众 |
| `logic/plugin_store.go` | `PluginStoreService` | 新增 |
| `logic/preset.go` | `PresetService` + `preset.yaml` | 新增 |
| `logic/manager_config.go` | `L4D2Config` | 转为配置类 |
| `middlewares/geoip.go` | (跳过白名单) + `GeoIpService` (查询用) | 仅保留查询 |
| `middlewares/auth.go` | (跳过) | 主应用统一鉴权 |

## 附录 B：关键源文件路径索引

### 源项目（Go）

| 文件 | 用途 |
|------|------|
| `D:\program\open_source\l4d2-server-next-master\backend\main.go` | 路由定义与初始化 |
| `D:\program\open_source\l4d2-server-next-master\backend\consts\consts.go` | 路径常量 |
| `D:\program\open_source\l4d2-server-next-master\backend\db\db.go` | 数据库初始化 |
| `D:\program\open_source\l4d2-server-next-master\backend\controller\*.go` | 22 个控制器 |
| `D:\program\open_source\l4d2-server-next-master\backend\logic\*.go` | 15 个业务逻辑文件 |
| `D:\program\open_source\l4d2-server-next-master\backend\preset.yaml` | 预设配置 |

### 目标项目（Java）

| 文件 | 用途 |
|------|------|
| `d:\program\ai\game_platform_manger\backend\plugin-l4d2\plugin-l4d2-core\src\main\java\com\gameplatform\plugin\l4d2\` | 业务代码根目录 |
| `d:\program\ai\game_platform_manger\backend\plugin-l4d2\plugin-l4d2-core\src\main\resources\plugin.properties` | PF4J manifest |
| `d:\program\ai\game_platform_manger\backend\plugin-l4d2\frontend\src\` | 前端源码 |
| `d:\program\ai\game_platform_manger\backend\plugin\src\main\java\com\gameplatform\plugin\extension\GameEnhancementExtension.java` | 扩展点接口 |
| `d:\program\ai\game_platform_manger\backend\plugin\src\main\java\com\gameplatform\plugin\service\*.java` | 宿主能力服务接口 |

## 附录 C：相关设计文档

| 文档 | 内容 |
|------|------|
| `docs/superpowers/specs/2026-07-14-plugin-extension-storage-design.md` | 扩展存储设计 |
| `docs/superpowers/specs/2026-07-14-extension-snowflake-id-design.md` | 雪花 ID 设计 |
| `docs/superpowers/specs/2026-07-14-plugin-l4d2-decouple-core-design.md` | 解耦 core 设计 |
| `docs/superpowers/specs/2026-07-14-plugin-l4d2-dual-packaging-design.md` | 双模式打包设计 |
| `docs/superpowers/specs/2026-07-15-standalone-default-ui-design.md` | standalone 默认 UI 设计 |

---

*设计完成日期：2026-07-19*
*实施方法：垂直切片（A 方案）*
*预计实施阶段：7 个阶段（阶段 0-6）*
