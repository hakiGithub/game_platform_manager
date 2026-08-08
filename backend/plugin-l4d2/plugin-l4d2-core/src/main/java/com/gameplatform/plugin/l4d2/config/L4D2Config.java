package com.gameplatform.plugin.l4d2.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * L4D2 插件配置类
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "plugin.l4d2")
public class L4D2Config {

    /**
     * RCON 连接超时时间（毫秒）
     */
    private int rconTimeout = 5000;

    /**
     * RCON 连接重试次数
     */
    private int rconRetryCount = 3;

    /**
     * RCON 连接重试间隔（毫秒）
     */
    private int rconRetryInterval = 1000;

    /**
     * VPK 文件扫描路径
     */
    private String vpkScanPath = "addons";

    /**
     * 是否启用 VPK 缓存
     */
    private boolean vpkCacheEnabled = true;

    /**
     * VPK 缓存过期时间（秒）
     */
    private int vpkCacheExpire = 300;

    // ===== RCON 增强 =====
    private Rcon rcon = new Rcon();

    @Data
    public static class Rcon {
        private int defaultPort = 27020;
        /** 空闲超时（秒），超过后连接关闭回收 */
        private int idleTimeoutSeconds = 300;
        /** 最大寿命（秒），防止长期持有导致服务端断开 */
        private int maxAgeSeconds = 1800;
        /** 清理扫描间隔（秒） */
        private int cleanIntervalSeconds = 60;
        /** 借用等待超时（秒） */
        private int borrowTimeoutSeconds = 3;
        /** 缓存开关，false 时每次新建连接 */
        private boolean poolEnabled = true;
    }

    // ===== Steam Web API =====
    private Steam steam = new Steam();

    @Data
    public static class Steam {
        private String apiKey = "";
        private int l4d2Appid = 550;
    }

    // ===== Workshop 下载 =====
    private Workshop workshop = new Workshop();

    @Data
    public static class Workshop {
        private String downloadDir = "addons/";
        private int maxConcurrent = 3;
        private String proxyUrl = "";
        /** Steam Web API 请求超时（毫秒） */
        private long parseTimeoutMs = 30_000L;
        /** 是否允许 Steam API 未返回 file_url 时创建 PENDING_MANUAL 任务 */
        private boolean allowManualProxy = true;
    }

    // ===== 插件商店 =====
    private PluginStore pluginStore = new PluginStore();

    @Data
    public static class PluginStore {
        private String repo = "LaoYutang/l4d2-plugins-store";
        private String branch = "master";
        private long cacheTtlMs = 600_000L; // 10 分钟
        private int maxConcurrent = 3;
        /** GitHub 代理 URL（如 https://gh-proxy.com/），为空则直连 */
        private String proxyUrl = "";
        /** GitHub Token（优先于环境变量 GITHUB_TOKEN），为空则用环境变量 */
        private String githubToken = "";
    }

    // ===== 监控采集 =====
    private Monitor monitor = new Monitor();

    @Data
    public static class Monitor {
        private long collectIntervalMs = 1000L;
        private long retentionMs = 3L * 24 * 3600 * 1000; // 3 天
        private int maxPoints = 2000;
        private int downsampleTo = 720;
        private String networkIgnorePattern = "docker|veth|br-|lo";
        /** 是否启用历史持久化（关闭时只读不写） */
        private boolean historyEnabled = true;
        /** 是否启用主动采集（@Scheduled 实际执行） */
        private boolean collectEnabled = true;
        /** L4D2 游戏 ID（用于拉取所有 L4D2 实例；为 null 时不采集） */
        private Long gameId;
    }

    // ===== 玩家统计 =====
    private PlayerStats playerStats = new PlayerStats();

    @Data
    public static class PlayerStats {
        private long collectIntervalMs = 600_000L; // 10 分钟
        private long retentionMs = 30L * 24 * 3600 * 1000; // 30 天
        /** 是否启用采集（默认开启） */
        private boolean enabled = true;
        /** 查询接口仅管理员可见（默认 true） */
        private boolean adminOnly = true;
    }

    // ===== 游玩时长 =====
    private Playtime playtime = new Playtime();

    @Data
    public static class Playtime {
        /** 单次查询 Steam API 总超时（毫秒） */
        private long requestTimeoutMs = 10_000L;
        /** 是否允许部分结果（任一 API 成功即返回） */
        private boolean allowPartialResult = true;
    }

    public Playtime getPlaytime() {
        return playtime;
    }

    // ===== 分片上传 =====
    private ChunkUpload chunkUpload = new ChunkUpload();

    @Data
    public static class ChunkUpload {
        private long chunkSizeBytes = 5L * 1024 * 1024; // 5MB
        private long maxTotalSizeBytes = 2L * 1024 * 1024 * 1024; // 2GB
        private long expireMs = 6L * 3600 * 1000; // 6 小时
        private double diskUsageThreshold = 0.9;
    }

    // ===== VPK 裁剪 =====
    private VpkTrim vpkTrim = new VpkTrim();

    @Data
    public static class VpkTrim {
        private boolean enabled = true;
    }

    // ===== 地图热重载 =====
    private MapHotReload mapHotReload = new MapHotReload();

    @Data
    public static class MapHotReload {
        private String command = "update_addon_paths; mission_reload";
    }

    // ===== GeoIP =====
    private GeoIp geoip = new GeoIp();

    @Data
    public static class GeoIp {
        private String xdbPath = "geoip/ip2region.xdb";
    }

    // ===== 重启 =====
    private Restart restart = new Restart();

    @Data
    public static class Restart {
        private boolean byRcon = false;
        private String containerName = "l4d2";
        private String customCmd = "";
        /** 命令模式执行超时（毫秒） */
        private long commandTimeoutMs = 30_000L;
        /** 运行时开关；关闭后 restart 调用直接抛 IllegalStateException */
        private boolean enabled = true;
    }
}
