package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.extension.PlayerStatPlayerResource;
import com.gameplatform.plugin.l4d2.extension.PlayerStatPlayerSpec;
import com.gameplatform.plugin.l4d2.extension.PlayerStatSnapshotResource;
import com.gameplatform.plugin.l4d2.extension.PlayerStatSnapshotSpec;
import com.gameplatform.plugin.l4d2.util.StatusParser;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsAliasVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsConfigVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsDayVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsPlayerVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsTrendVO;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * L4D2 玩家在线统计服务。
 * <p>
 * 对齐源项目 {@code controller/player_stats.go}：
 * <ul>
 *   <li>定时 10 分钟采集：RCON {@code status} + {@code z_difficulty} + {@code sm_cvar mp_gamemode}</li>
 *   <li>30 天数据保留，每日清理</li>
 *   <li>小时/天聚合查询（avg/peak/unique/offline_samples/sample_count）</li>
 *   <li>玩家搜索（steam_id / name 模糊匹配）</li>
 *   <li>玩家按日统计、别名聚合</li>
 * </ul>
 *
 * <p>采集逻辑：遍历 L4D2 实例，对每个实例执行 RCON 三命令；用 {@link StatusParser} 解析；
 * 调用 {@link GeoIpService#queryProvince(String)} 填充 location；持久化 snapshot + players。
 * 失败时持久化 serverOnline=false/collectOk=false/errorMessage 的 snapshot。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerStatsService {

    /** 默认采集间隔（分钟），与源项目 playerStatsIntervalMinutes 对齐 */
    public static final int INTERVAL_MINUTES = 10;

    /** 默认保留天数，与源项目 playerStatsRetentionDays 对齐 */
    public static final int RETENTION_DAYS = 30;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RconService rconService;
    private final GeoIpService geoIpService;
    private final ExtensionClient extensionClient;
    private final InstanceQueryService instanceQueryService;
    private final L4D2Config config;
    private final StatusParser statusParser;

    /** 运行时开关（持久化到 L4D2Config.PlayerStats.enabled） */
    private volatile boolean enabled = true;

    /**
     * 定时采集玩家统计。
     * <p>每 10 分钟执行一次（可通过 {@code plugin.l4d2.player-stats.collect-interval-ms} 配置）。
     */
    @Scheduled(fixedRateString = "${plugin.l4d2.player-stats.collect-interval-ms:600000}")
    public void collectPlayerStats() {
        if (!isEnabled()) {
            return;
        }
        try {
            List<InstanceVO> instances = collectL4d2Instances();
            if (instances.isEmpty()) {
                return;
            }
            for (InstanceVO instance : instances) {
                try {
                    collectForInstance(instance);
                } catch (Exception e) {
                    log.warn("采集实例 {} 玩家统计失败: {}", instance.getId(), e.getMessage(), e);
                    persistErrorSnapshot(instance.getId(), "采集异常: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("玩家统计采集任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 定时清理过期数据（每日执行）。
     */
    @Scheduled(fixedRate = 86400_000L)
    public void cleanupExpired() {
        try {
            long now = System.currentTimeMillis();
            long retentionMs = config.getPlayerStats().getRetentionMs();
            long expireBefore = now - retentionMs;
            long expireSeconds = expireBefore / 1000L;

            // 拉取所有 snapshot，按 timestamp 过滤
            ListOptions snapOpts = ListOptions.builder()
                    .limit(10000)
                    .orderBy("creation_timestamp")
                    .build();
            List<PlayerStatSnapshotResource> snapshots = extensionClient.list(PlayerStatSnapshotResource.class, snapOpts);
            int snapDeleted = 0;
            for (PlayerStatSnapshotResource snap : snapshots) {
                PlayerStatSnapshotSpec spec = snap.getSpec();
                if (spec == null || spec.getTimestamp() == null) {
                    continue;
                }
                if (spec.getTimestamp() < expireSeconds) {
                    try {
                        extensionClient.deleteById(PlayerStatSnapshotResource.class, snap.getId());
                        snapDeleted++;
                    } catch (Exception e) {
                        log.warn("删除过期 snapshot 失败 id={}, err={}", snap.getId(), e.getMessage());
                    }
                }
            }

            // 拉取所有 player，按 timestamp 过滤
            ListOptions playerOpts = ListOptions.builder()
                    .limit(50000)
                    .orderBy("creation_timestamp")
                    .build();
            List<PlayerStatPlayerResource> players = extensionClient.list(PlayerStatPlayerResource.class, playerOpts);
            int playerDeleted = 0;
            for (PlayerStatPlayerResource p : players) {
                PlayerStatPlayerSpec spec = p.getSpec();
                if (spec == null || spec.getTimestamp() == null) {
                    continue;
                }
                if (spec.getTimestamp() < expireSeconds) {
                    try {
                        extensionClient.deleteById(PlayerStatPlayerResource.class, p.getId());
                        playerDeleted++;
                    } catch (Exception e) {
                        log.warn("删除过期 player 失败 id={}, err={}", p.getId(), e.getMessage());
                    }
                }
            }
            if (snapDeleted > 0 || playerDeleted > 0) {
                log.info("玩家统计清理完成：{} 个快照, {} 个玩家记录", snapDeleted, playerDeleted);
            }
        } catch (Exception e) {
            log.error("玩家统计清理任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 启用/禁用采集。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        config.getPlayerStats().setEnabled(enabled);
        log.info("玩家统计采集已{}", enabled ? "启用" : "停用");
    }

    /**
     * 当前是否启用采集。
     */
    public boolean isEnabled() {
        return enabled && config.getPlayerStats().isEnabled();
    }

    /**
     * 获取当前配置（含最近一次快照）。
     */
    public PlayerStatsConfigVO getConfig() {
        PlayerStatsConfigVO vo = new PlayerStatsConfigVO();
        vo.setEnabled(isEnabled());
        vo.setIntervalMinutes((int) (config.getPlayerStats().getCollectIntervalMs() / 60_000L));
        vo.setRetentionDays((int) (config.getPlayerStats().getRetentionMs() / (24L * 3600 * 1000)));
        vo.setLastSnapshot(getLatestSnapshot());
        return vo;
    }

    /**
     * 小时聚合查询（按 timestamp/3600*3600 桶）。
     */
    public List<PlayerStatsTrendVO> getHourlyTrend(Long instanceId, Long start, Long end) {
        List<PlayerStatSnapshotResource> snapshots = listSnapshots(instanceId, start, end);
        List<PlayerStatPlayerResource> players = listPlayers(instanceId, start, end);

        // 按小时桶聚合 snapshots
        Map<Long, TrendAgg> aggByBucket = new LinkedHashMap<>();
        for (PlayerStatSnapshotResource snap : snapshots) {
            PlayerStatSnapshotSpec spec = snap.getSpec();
            if (spec == null || spec.getTimestamp() == null) {
                continue;
            }
            long bucket = spec.getTimestamp() / 3600 * 3600;
            TrendAgg agg = aggByBucket.computeIfAbsent(bucket, k -> new TrendAgg());
            agg.sampleCount++;
            boolean online = Boolean.TRUE.equals(spec.getServerOnline()) && Boolean.TRUE.equals(spec.getCollectOk());
            if (!online) {
                agg.offlineSamples++;
                continue;
            }
            int count = spec.getPlayerCount() == null ? 0 : spec.getPlayerCount();
            agg.sumPlayers += count;
            agg.onlineSamples++;
            if (!agg.hasPeak || count > agg.peakPlayers) {
                agg.peakPlayers = count;
                agg.hasPeak = true;
            }
        }

        // 按小时桶聚合 unique players
        Map<Long, Set<String>> uniqueByBucket = new HashMap<>();
        for (PlayerStatPlayerResource p : players) {
            PlayerStatPlayerSpec spec = p.getSpec();
            if (spec == null || spec.getTimestamp() == null || spec.getSteamId() == null || spec.getSteamId().isEmpty()) {
                continue;
            }
            long bucket = spec.getTimestamp() / 3600 * 3600;
            uniqueByBucket.computeIfAbsent(bucket, k -> new HashSet<>()).add(spec.getSteamId());
        }

        List<PlayerStatsTrendVO> result = new ArrayList<>(aggByBucket.size());
        for (Map.Entry<Long, TrendAgg> entry : aggByBucket.entrySet()) {
            long bucket = entry.getKey();
            TrendAgg agg = entry.getValue();
            PlayerStatsTrendVO vo = new PlayerStatsTrendVO();
            vo.setTimestamp(bucket);
            vo.setOfflineSamples((long) agg.offlineSamples);
            vo.setSampleCount((long) agg.sampleCount);
            if (agg.onlineSamples > 0) {
                double avg = Math.round(((double) agg.sumPlayers / agg.onlineSamples) * 100.0) / 100.0;
                vo.setAvgPlayers(avg);
                vo.setPeakPlayers(agg.peakPlayers);
            }
            Set<String> unique = uniqueByBucket.get(bucket);
            vo.setUniquePlayers(unique == null ? 0L : (long) unique.size());
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(PlayerStatsTrendVO::getTimestamp));
        return result;
    }

    /**
     * 天聚合查询（按 localDayStart 桶）。
     */
    public List<PlayerStatsTrendVO> getDailyTrend(Long instanceId, Long start, Long end) {
        List<PlayerStatSnapshotResource> snapshots = listSnapshots(instanceId, start, end);
        List<PlayerStatPlayerResource> players = listPlayers(instanceId, start, end);

        // 按本地天起始桶聚合 snapshots
        Map<Long, TrendAgg> aggByBucket = new LinkedHashMap<>();
        for (PlayerStatSnapshotResource snap : snapshots) {
            PlayerStatSnapshotSpec spec = snap.getSpec();
            if (spec == null || spec.getTimestamp() == null) {
                continue;
            }
            long bucket = localDayStart(spec.getTimestamp());
            TrendAgg agg = aggByBucket.computeIfAbsent(bucket, k -> new TrendAgg());
            agg.sampleCount++;
            boolean online = Boolean.TRUE.equals(spec.getServerOnline()) && Boolean.TRUE.equals(spec.getCollectOk());
            if (!online) {
                agg.offlineSamples++;
                continue;
            }
            int count = spec.getPlayerCount() == null ? 0 : spec.getPlayerCount();
            agg.sumPlayers += count;
            agg.onlineSamples++;
            if (!agg.hasPeak || count > agg.peakPlayers) {
                agg.peakPlayers = count;
                agg.hasPeak = true;
            }
        }

        // 按本地天起始桶聚合 unique players
        Map<Long, Set<String>> uniqueByBucket = new HashMap<>();
        for (PlayerStatPlayerResource p : players) {
            PlayerStatPlayerSpec spec = p.getSpec();
            if (spec == null || spec.getTimestamp() == null || spec.getSteamId() == null || spec.getSteamId().isEmpty()) {
                continue;
            }
            long bucket = localDayStart(spec.getTimestamp());
            uniqueByBucket.computeIfAbsent(bucket, k -> new HashSet<>()).add(spec.getSteamId());
        }

        List<PlayerStatsTrendVO> result = new ArrayList<>(aggByBucket.size());
        for (Map.Entry<Long, TrendAgg> entry : aggByBucket.entrySet()) {
            long bucket = entry.getKey();
            TrendAgg agg = entry.getValue();
            PlayerStatsTrendVO vo = new PlayerStatsTrendVO();
            vo.setTimestamp(bucket);
            vo.setOfflineSamples((long) agg.offlineSamples);
            vo.setSampleCount((long) agg.sampleCount);
            if (agg.onlineSamples > 0) {
                double avg = Math.round(((double) agg.sumPlayers / agg.onlineSamples) * 100.0) / 100.0;
                vo.setAvgPlayers(avg);
                vo.setPeakPlayers(agg.peakPlayers);
            }
            Set<String> unique = uniqueByBucket.get(bucket);
            vo.setUniquePlayers(unique == null ? 0L : (long) unique.size());
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(PlayerStatsTrendVO::getTimestamp));
        return result;
    }

    /**
     * 玩家搜索（按 steam_id / name 模糊匹配，空关键字返回全部）。
     * <p>estimated_minutes = samples × INTERVAL_MINUTES。
     */
    public List<PlayerStatsPlayerVO> searchPlayers(Long instanceId, String keyword, Long start) {
        List<PlayerStatPlayerResource> players = listPlayers(instanceId, start, null);
        // 聚合 steamId -> (samples, lastSeen, latest record)
        Map<String, PlayerAgg> aggBySteam = new HashMap<>();
        for (PlayerStatPlayerResource p : players) {
            PlayerStatPlayerSpec spec = p.getSpec();
            if (spec == null || spec.getSteamId() == null || spec.getSteamId().isEmpty()) {
                continue;
            }
            PlayerAgg agg = aggBySteam.computeIfAbsent(spec.getSteamId(), k -> new PlayerAgg());
            agg.samples++;
            long ts = spec.getTimestamp() == null ? 0L : spec.getTimestamp();
            if (ts > agg.lastSeen) {
                agg.lastSeen = ts;
                agg.latest = spec;
            }
        }
        // 排序：samples desc, lastSeen desc, steamId asc
        List<PlayerAgg> ranked = new ArrayList<>(aggBySteam.values());
        ranked.sort((a, b) -> {
            int c = Integer.compare(b.samples, a.samples);
            if (c != 0) return c;
            c = Long.compare(b.lastSeen, a.lastSeen);
            if (c != 0) return c;
            return a.latest == null || b.latest == null ? 0
                    : a.latest.getSteamId().compareTo(b.latest.getSteamId());
        });
        // 应用关键字过滤 + rank
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<PlayerStatsPlayerVO> result = new ArrayList<>();
        int rank = 0;
        for (PlayerAgg agg : ranked) {
            rank++;
            if (agg.latest == null) {
                continue;
            }
            if (!kw.isEmpty()) {
                String sid = agg.latest.getSteamId() == null ? "" : agg.latest.getSteamId().toLowerCase();
                String name = agg.latest.getName() == null ? "" : agg.latest.getName().toLowerCase();
                if (!sid.contains(kw) && !name.contains(kw)) {
                    continue;
                }
            }
            PlayerStatsPlayerVO vo = new PlayerStatsPlayerVO();
            vo.setSteamId(agg.latest.getSteamId());
            vo.setName(agg.latest.getName());
            vo.setLocation(agg.latest.getLocation());
            vo.setIp(agg.latest.getIp());
            vo.setLastSeen(agg.lastSeen);
            vo.setEstimatedMinutes((long) agg.samples * INTERVAL_MINUTES);
            vo.setRank(rank);
            result.add(vo);
            if (result.size() >= 50) {
                break;
            }
        }
        return result;
    }

    /**
     * 玩家按日统计。
     */
    public List<PlayerStatsDayVO> getPlayerDays(Long instanceId, String steamId, Long start) {
        List<PlayerStatPlayerResource> players = listPlayers(instanceId, start, null);
        // 按 steamId 过滤 + 按日期聚合
        Map<String, DayAgg> aggByDate = new LinkedHashMap<>();
        for (PlayerStatPlayerResource p : players) {
            PlayerStatPlayerSpec spec = p.getSpec();
            if (spec == null || spec.getTimestamp() == null) {
                continue;
            }
            if (steamId != null && !steamId.isEmpty() && !steamId.equals(spec.getSteamId())) {
                continue;
            }
            String date = LocalDate.ofInstant(Instant.ofEpochSecond(spec.getTimestamp()), ZoneId.systemDefault()).format(DATE_FMT);
            DayAgg agg = aggByDate.computeIfAbsent(date, k -> new DayAgg());
            agg.date = date;
            agg.samples++;
            agg.onlineMinutes += INTERVAL_MINUTES;
            if (agg.firstSeen == null || spec.getTimestamp() < agg.firstSeen) {
                agg.firstSeen = spec.getTimestamp();
            }
            if (agg.lastSeen == null || spec.getTimestamp() > agg.lastSeen) {
                agg.lastSeen = spec.getTimestamp();
            }
        }
        List<PlayerStatsDayVO> result = new ArrayList<>(aggByDate.size());
        for (DayAgg agg : aggByDate.values()) {
            PlayerStatsDayVO vo = new PlayerStatsDayVO();
            vo.setDate(agg.date);
            vo.setOnlineMinutes(agg.onlineMinutes);
            vo.setSamples(agg.samples);
            vo.setFirstSeen(agg.firstSeen);
            vo.setLastSeen(agg.lastSeen);
            result.add(vo);
        }
        result.sort(Comparator.comparing(PlayerStatsDayVO::getDate));
        return result;
    }

    /**
     * 玩家别名聚合。
     */
    public List<PlayerStatsAliasVO> getPlayerAliases(Long instanceId, String steamId, Long start) {
        List<PlayerStatPlayerResource> players = listPlayers(instanceId, start, null);
        // 按 steamId 过滤 + 按 name 聚合
        Map<String, AliasAgg> aggByName = new HashMap<>();
        for (PlayerStatPlayerResource p : players) {
            PlayerStatPlayerSpec spec = p.getSpec();
            if (spec == null || spec.getTimestamp() == null) {
                continue;
            }
            if (steamId != null && !steamId.isEmpty() && !steamId.equals(spec.getSteamId())) {
                continue;
            }
            String name = spec.getName() == null || spec.getName().trim().isEmpty() ? "Unknown" : spec.getName().trim();
            AliasAgg agg = aggByName.computeIfAbsent(name, k -> new AliasAgg());
            agg.name = name;
            agg.samples++;
            agg.estimatedMinutes += INTERVAL_MINUTES;
            if (agg.firstSeen == null || spec.getTimestamp() < agg.firstSeen) {
                agg.firstSeen = spec.getTimestamp();
            }
            if (agg.lastSeen == null || spec.getTimestamp() > agg.lastSeen) {
                agg.lastSeen = spec.getTimestamp();
            }
        }
        List<PlayerStatsAliasVO> result = new ArrayList<>(aggByName.size());
        for (AliasAgg agg : aggByName.values()) {
            PlayerStatsAliasVO vo = new PlayerStatsAliasVO();
            vo.setName(agg.name);
            vo.setSamples(agg.samples);
            vo.setEstimatedMinutes(agg.estimatedMinutes);
            vo.setFirstSeen(agg.firstSeen);
            vo.setLastSeen(agg.lastSeen);
            result.add(vo);
        }
        // 按 lastSeen desc 排序
        result.sort((a, b) -> {
            long la = a.getLastSeen() == null ? 0L : a.getLastSeen();
            long lb = b.getLastSeen() == null ? 0L : b.getLastSeen();
            return Long.compare(lb, la);
        });
        return result;
    }

    // ===== 私有辅助方法 =====

    /**
     * 拉取所有 L4D2 实例。
     * <p>优先使用 {@code L4D2Config.Monitor.gameId}（如已配置）；为 null 时调用
     * {@code getInstancesByGameId(null)}（返回全部实例），并按 gameCode 过滤为 l4d2。
     */
    private List<InstanceVO> collectL4d2Instances() {
        Long gameId = config.getMonitor().getGameId();
        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.getInstancesByGameId(gameId);
        } catch (Exception e) {
            log.warn("拉取实例列表失败 gameId={}, err={}", gameId, e.getMessage());
            return List.of();
        }
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        // 过滤 L4D2 实例
        return instances.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.getGameCode() == null || "l4d2".equalsIgnoreCase(i.getGameCode()))
                .collect(Collectors.toList());
    }

    /**
     * 对单个实例执行 RCON 三命令采集，并持久化。
     */
    private void collectForInstance(InstanceVO instance) {
        long now = System.currentTimeMillis() / 1000L;
        Long instanceId = instance.getId();
        String statusText;
        try {
            statusText = rconService.executeCommand(instanceId, "status");
        } catch (Exception e) {
            persistErrorSnapshot(instanceId, "RCON status 失败: " + e.getMessage());
            return;
        }
        String difficultyText = "";
        String gameModeText = "";
        try {
            difficultyText = rconService.executeCommand(instanceId, "z_difficulty");
        } catch (Exception e) {
            log.debug("实例 {} z_difficulty 失败: {}", instanceId, e.getMessage());
        }
        try {
            gameModeText = rconService.executeCommand(instanceId, "sm_cvar mp_gamemode");
        } catch (Exception e) {
            log.debug("实例 {} sm_cvar mp_gamemode 失败: {}", instanceId, e.getMessage());
        }

        StatusParser.Status status = statusParser.parse(statusText);
        String difficulty = statusParser.parseDifficulty(difficultyText);
        String gameMode = statusParser.parseGameMode(gameModeText);

        int playerCount = status.getPlayerCount();
        if (playerCount == 0 && !status.getUsers().isEmpty()) {
            playerCount = status.getUsers().size();
        }

        PlayerStatSnapshotResource snap = new PlayerStatSnapshotResource();
        snap.setName(instanceId + "-" + now);
        PlayerStatSnapshotSpec spec = new PlayerStatSnapshotSpec();
        spec.setInstanceId(instanceId);
        spec.setTimestamp(now);
        spec.setServerOnline(true);
        spec.setCollectOk(true);
        spec.setPlayerCount(playerCount);
        spec.setMaxPlayers(status.getMaxPlayers());
        spec.setMap(status.getMap());
        spec.setHostname(status.getHostname());
        spec.setDifficulty(difficulty);
        spec.setGameMode(gameMode);
        spec.setErrorMessage("");
        snap.setSpec(spec);

        String snapshotId;
        try {
            extensionClient.create(snap);
            snapshotId = snap.getId();
        } catch (Exception e) {
            log.warn("持久化快照失败 instanceId={}, err={}", instanceId, e.getMessage());
            return;
        }

        // 持久化玩家
        int idx = 0;
        for (StatusParser.User user : status.getUsers()) {
            try {
                String location = geoIpService.queryProvince(stripPort(user.getIp()));
                if (location == null || location.isEmpty()) {
                    location = "未知";
                }
                PlayerStatPlayerResource pr = new PlayerStatPlayerResource();
                pr.setName(instanceId + "-" + snapshotId + "-" + (user.getSteamId() == null ? "unknown" : user.getSteamId()) + "-" + idx);
                PlayerStatPlayerSpec ps = new PlayerStatPlayerSpec();
                ps.setInstanceId(instanceId);
                ps.setSnapshotId(snapshotId);
                ps.setTimestamp(now);
                ps.setSteamId(user.getSteamId());
                ps.setName(user.getName());
                ps.setIp(user.getIp());
                ps.setLocation(location);
                ps.setStatus(user.getStatus());
                ps.setDelay(user.getDelay());
                ps.setLoss(user.getLoss());
                ps.setDuration(user.getDuration());
                ps.setLinkRate(user.getLinkRate());
                pr.setSpec(ps);
                extensionClient.create(pr);
                idx++;
            } catch (Exception e) {
                log.warn("持久化玩家失败 instanceId={}, steamId={}, err={}",
                        instanceId, user.getSteamId(), e.getMessage());
            }
        }
    }

    /**
     * 持久化错误快照。
     */
    private void persistErrorSnapshot(Long instanceId, String errorMessage) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            PlayerStatSnapshotResource snap = new PlayerStatSnapshotResource();
            snap.setName(instanceId + "-" + now);
            PlayerStatSnapshotSpec spec = new PlayerStatSnapshotSpec();
            spec.setInstanceId(instanceId);
            spec.setTimestamp(now);
            spec.setServerOnline(false);
            spec.setCollectOk(false);
            spec.setPlayerCount(0);
            spec.setMaxPlayers(0);
            spec.setMap("");
            spec.setHostname("");
            spec.setDifficulty("未知");
            spec.setGameMode("未知");
            spec.setErrorMessage(errorMessage == null ? "" : errorMessage);
            snap.setSpec(spec);
            extensionClient.create(snap);
        } catch (Exception e) {
            log.warn("持久化错误快照失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
    }

    /**
     * 从扩展存储拉取指定实例、时间范围内的快照。
     */
    private List<PlayerStatSnapshotResource> listSnapshots(Long instanceId, Long start, Long end) {
        ListOptions.Builder builder = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .limit(10000)
                .orderBy("creation_timestamp");
        // 通过 createdAfter 过滤起始时间（spec.timestamp 为秒，metadata.creation_timestamp 为毫秒）
        if (start != null && start > 0) {
            builder.createdAfter(start * 1000L);
        }
        List<PlayerStatSnapshotResource> list = extensionClient.list(PlayerStatSnapshotResource.class, builder.build());
        if (end == null || end <= 0) {
            return list;
        }
        // 内存按 spec.timestamp 过滤上界
        return list.stream()
                .filter(r -> r.getSpec() != null && r.getSpec().getTimestamp() != null
                        && r.getSpec().getTimestamp() <= end)
                .collect(Collectors.toList());
    }

    /**
     * 从扩展存储拉取指定实例、时间范围内的玩家记录。
     */
    private List<PlayerStatPlayerResource> listPlayers(Long instanceId, Long start, Long end) {
        ListOptions.Builder builder = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .limit(50000)
                .orderBy("creation_timestamp");
        if (start != null && start > 0) {
            builder.createdAfter(start * 1000L);
        }
        List<PlayerStatPlayerResource> list = extensionClient.list(PlayerStatPlayerResource.class, builder.build());
        if (end == null || end <= 0) {
            return list;
        }
        return list.stream()
                .filter(r -> r.getSpec() != null && r.getSpec().getTimestamp() != null
                        && r.getSpec().getTimestamp() <= end)
                .collect(Collectors.toList());
    }

    /**
     * 获取最近一条快照。
     */
    private PlayerStatSnapshotResource getLatestSnapshot() {
        try {
            ListOptions opts = ListOptions.builder()
                    .limit(1)
                    .orderBy("creation_timestamp")
                    .build();
            List<PlayerStatSnapshotResource> list = extensionClient.list(PlayerStatSnapshotResource.class, opts);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.debug("查询最近快照失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 去掉 IP:port 中的端口部分。
     */
    private String stripPort(String ipPort) {
        if (ipPort == null || ipPort.isEmpty()) {
            return "";
        }
        int idx = ipPort.lastIndexOf(':');
        return idx > 0 ? ipPort.substring(0, idx) : ipPort;
    }

    /**
     * 本地时区当日 0 点对应的 Unix 秒。
     */
    private long localDayStart(long timestampSeconds) {
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochSecond(timestampSeconds), ZoneId.systemDefault());
        LocalDateTime dayStart = day.atStartOfDay();
        return dayStart.atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();
    }

    /** 趋势聚合临时结构 */
    private static class TrendAgg {
        int sumPlayers;
        int onlineSamples;
        int peakPlayers;
        boolean hasPeak;
        long offlineSamples;
        long sampleCount;
    }

    /** 玩家聚合临时结构 */
    private static class PlayerAgg {
        int samples;
        long lastSeen;
        PlayerStatPlayerSpec latest;
    }

    /** 按日聚合临时结构 */
    private static class DayAgg {
        String date;
        int samples;
        int onlineMinutes;
        Long firstSeen;
        Long lastSeen;
    }

    /** 别名聚合临时结构 */
    private static class AliasAgg {
        String name;
        int samples;
        long estimatedMinutes;
        Long firstSeen;
        Long lastSeen;
    }
}
