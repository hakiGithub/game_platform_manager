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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlayerStatsService 单元测试（对齐 plan §5.1.7）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayerStatsServiceTest {

    @Mock
    private RconService rconService;

    @Mock
    private GeoIpService geoIpService;

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private InstanceQueryService instanceQueryService;

    private L4D2Config config;

    private StatusParser statusParser;

    private PlayerStatsService service;

    private final AtomicLong idSeq = new AtomicLong(1000L);

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getPlayerStats().setEnabled(true);
        config.getPlayerStats().setCollectIntervalMs(600_000L);
        config.getPlayerStats().setRetentionMs(30L * 24 * 3600 * 1000);
        // 复用 Monitor.gameId（为 null 时返回全部实例）
        config.getMonitor().setGameId(null);
        statusParser = new StatusParser();
        service = new PlayerStatsService(rconService, geoIpService, extensionClient,
                instanceQueryService, config, statusParser);
        idSeq.set(1000L);

        // 默认 create 时为资源分配 ID（snowflake 字符串）
        doAnswer(invocation -> {
            PlayerStatSnapshotResource r = invocation.getArgument(0);
            r.setId(String.valueOf(idSeq.incrementAndGet()));
            return null;
        }).when(extensionClient).create(any(PlayerStatSnapshotResource.class));
        doAnswer(invocation -> {
            PlayerStatPlayerResource r = invocation.getArgument(0);
            r.setId(String.valueOf(idSeq.incrementAndGet()));
            return null;
        }).when(extensionClient).create(any(PlayerStatPlayerResource.class));
    }

    // ============================================================
    // collect_success_persists_snapshot_and_players
    // ============================================================
    @Test
    void collect_success_persists_snapshot_and_players() {
        InstanceVO instance = buildInstance(1L, "127.0.0.1", 27015, "test-pwd");
        when(instanceQueryService.getInstancesByGameId(null)).thenReturn(List.of(instance));

        String statusText = String.join("\n",
                "hostname: Test Server",
                "map     : c1m1_hotel",
                "players : 2 humans, 0 bots (30 max)",
                "#     2 2 \"Player One\" STEAM_1:0:111 1:23:45 30 0 active 5 1.2.3.4:27005",
                "#     3 3 \"Player Two\" STEAM_1:0:222 0:05:12 50 1 active 5 5.6.7.8:27006",
                "#end");
        when(rconService.executeCommand(eq(1L), eq("status")))
                .thenReturn(statusText);
        when(rconService.executeCommand(eq(1L), eq("z_difficulty")))
                .thenReturn("\"z_difficulty\" = \"hard\"");
        when(rconService.executeCommand(eq(1L), eq("sm_cvar mp_gamemode")))
                .thenReturn("[SM] Value of cvar \"mp_gamemode\": \"coop\"");
        when(geoIpService.queryProvince(anyString())).thenReturn("北京");

        service.collectPlayerStats();

        // 验证：1 个 snapshot + 2 个 player
        ArgumentCaptor<PlayerStatSnapshotResource> snapCaptor = ArgumentCaptor.forClass(PlayerStatSnapshotResource.class);
        verify(extensionClient).create(snapCaptor.capture());
        PlayerStatSnapshotResource snap = snapCaptor.getValue();
        PlayerStatSnapshotSpec spec = snap.getSpec();
        assertEquals(1L, spec.getInstanceId());
        assertTrue(spec.getServerOnline());
        assertTrue(spec.getCollectOk());
        assertEquals(2, spec.getPlayerCount());
        assertEquals(30, spec.getMaxPlayers());
        assertEquals("c1m1_hotel", spec.getMap());
        assertEquals("Test Server", spec.getHostname());
        assertEquals("高级", spec.getDifficulty());
        assertEquals("合作", spec.getGameMode());

        // 验证 2 个玩家
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlayerStatPlayerResource> playerCaptor = ArgumentCaptor.forClass(PlayerStatPlayerResource.class);
        verify(extensionClient, org.mockito.Mockito.times(2)).create(playerCaptor.capture());
        List<PlayerStatPlayerResource> players = playerCaptor.getAllValues();
        assertEquals("STEAM_1:0:111", players.get(0).getSpec().getSteamId());
        assertEquals("Player One", players.get(0).getSpec().getName());
        assertEquals("北京", players.get(0).getSpec().getLocation());
        assertEquals("STEAM_1:0:222", players.get(1).getSpec().getSteamId());
    }

    // ============================================================
    // collect_rcon_failure_persists_error_snapshot
    // ============================================================
    @Test
    void collect_rcon_failure_persists_error_snapshot() {
        InstanceVO instance = buildInstance(2L, "127.0.0.1", 27015, "test-pwd");
        when(instanceQueryService.getInstancesByGameId(null)).thenReturn(List.of(instance));
        when(rconService.executeCommand(anyLong(), eq("status")))
                .thenThrow(new RuntimeException("connection refused"));

        service.collectPlayerStats();

        ArgumentCaptor<PlayerStatSnapshotResource> snapCaptor = ArgumentCaptor.forClass(PlayerStatSnapshotResource.class);
        // 失败 snapshot + error snapshot 都会调用 create
        verify(extensionClient, org.mockito.Mockito.atLeastOnce()).create(snapCaptor.capture());
        PlayerStatSnapshotResource snap = snapCaptor.getValue();
        PlayerStatSnapshotSpec spec = snap.getSpec();
        assertEquals(2L, spec.getInstanceId());
        assertFalse(spec.getServerOnline());
        assertFalse(spec.getCollectOk());
        assertNotNull(spec.getErrorMessage());
        assertTrue(spec.getErrorMessage().contains("connection refused") || spec.getErrorMessage().contains("RCON"));
    }

    // ============================================================
    // collect_disabled_skips
    // ============================================================
    @Test
    void collect_disabled_skips() {
        service.setEnabled(false);
        when(instanceQueryService.getInstancesByGameId(any())).thenReturn(List.of());

        service.collectPlayerStats();

        verify(extensionClient, never()).create(any(PlayerStatSnapshotResource.class));
        verify(extensionClient, never()).create(any(PlayerStatPlayerResource.class));
    }

    // ============================================================
    // cleanup_removes_expired_snapshots_and_players
    // ============================================================
    @Test
    void cleanup_removes_expired_snapshots_and_players() {
        // 设置较短保留期（1 小时）
        config.getPlayerStats().setRetentionMs(3600_000L);

        long now = System.currentTimeMillis() / 1000L;
        long expiredTs = now - 7200; // 2 小时前
        long validTs = now - 600;    // 10 分钟前

        PlayerStatSnapshotResource expiredSnap = buildSnapshot("snap-1", 1L, expiredTs, true, true, 5);
        PlayerStatSnapshotResource validSnap = buildSnapshot("snap-2", 1L, validTs, true, true, 3);
        when(extensionClient.list(eq(PlayerStatSnapshotResource.class), any(ListOptions.class)))
                .thenReturn(List.of(expiredSnap, validSnap));
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(List.of());

        service.cleanupExpired();

        verify(extensionClient).deleteById(PlayerStatSnapshotResource.class, "snap-1");
        verify(extensionClient, never()).deleteById(PlayerStatSnapshotResource.class, "snap-2");
    }

    // ============================================================
    // hourly_trend_aggregates_correctly
    // ============================================================
    @Test
    void hourly_trend_aggregates_correctly() {
        long baseBucket = 1_700_000_000L / 3600 * 3600; // 整小时桶
        long t1 = baseBucket + 60;
        long t2 = baseBucket + 120;
        long t3 = baseBucket + 3600 + 60; // 下一小时桶

        List<PlayerStatSnapshotResource> snapshots = List.of(
                buildSnapshot("s1", 1L, t1, true, true, 4),
                buildSnapshot("s2", 1L, t2, true, true, 6),
                buildSnapshot("s3", 1L, t3, true, true, 2)
        );
        when(extensionClient.list(eq(PlayerStatSnapshotResource.class), any(ListOptions.class)))
                .thenReturn(snapshots);
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(List.of());

        List<PlayerStatsTrendVO> trend = service.getHourlyTrend(1L, baseBucket, t3);

        assertEquals(2, trend.size());
        PlayerStatsTrendVO bucket1 = trend.get(0);
        assertEquals(baseBucket, bucket1.getTimestamp());
        assertEquals(5.0, bucket1.getAvgPlayers()); // (4+6)/2
        assertEquals(6, bucket1.getPeakPlayers());
        assertEquals(2L, bucket1.getSampleCount());
        assertEquals(0L, bucket1.getOfflineSamples());

        PlayerStatsTrendVO bucket2 = trend.get(1);
        assertEquals(baseBucket + 3600, bucket2.getTimestamp());
        assertEquals(2.0, bucket2.getAvgPlayers());
        assertEquals(2, bucket2.getPeakPlayers());
    }

    // ============================================================
    // daily_trend_aggregates_by_local_day
    // ============================================================
    @Test
    void daily_trend_aggregates_by_local_day() {
        // 使用两天的时间戳
        long day1Ts = 1_700_000_000L;
        long day2Ts = day1Ts + 24 * 3600L;
        // 确保两个时间戳落在不同的本地日期
        // 这里直接构造 snapshots，service 内部按 localDayStart 桶
        List<PlayerStatSnapshotResource> snapshots = List.of(
                buildSnapshot("s1", 1L, day1Ts, true, true, 4),
                buildSnapshot("s2", 1L, day1Ts + 60, true, true, 6),
                buildSnapshot("s3", 1L, day2Ts, true, true, 2)
        );
        when(extensionClient.list(eq(PlayerStatSnapshotResource.class), any(ListOptions.class)))
                .thenReturn(snapshots);
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(List.of());

        List<PlayerStatsTrendVO> trend = service.getDailyTrend(1L, day1Ts, day2Ts + 1);

        // 至少 2 个桶（可能 3 个，取决于本地时区）
        assertTrue(trend.size() >= 2);
        // 桶按时间升序
        for (int i = 1; i < trend.size(); i++) {
            assertTrue(trend.get(i - 1).getTimestamp() < trend.get(i).getTimestamp());
        }
    }

    // ============================================================
    // hourly_trend_includes_unique_players
    // ============================================================
    @Test
    void hourly_trend_includes_unique_players() {
        long bucket = 1_700_000_000L / 3600 * 3600;
        long t1 = bucket + 60;
        long t2 = bucket + 120;

        List<PlayerStatSnapshotResource> snapshots = List.of(
                buildSnapshot("s1", 1L, t1, true, true, 2),
                buildSnapshot("s2", 1L, t2, true, true, 2)
        );
        List<PlayerStatPlayerResource> players = List.of(
                buildPlayer("p1", 1L, t1, "STEAM_1:0:111", "Player1"),
                buildPlayer("p2", 1L, t1, "STEAM_1:0:222", "Player2"),
                buildPlayer("p3", 1L, t2, "STEAM_1:0:111", "Player1"), // 重复 steamId
                buildPlayer("p4", 1L, t2, "STEAM_1:0:333", "Player3")
        );
        when(extensionClient.list(eq(PlayerStatSnapshotResource.class), any(ListOptions.class)))
                .thenReturn(snapshots);
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsTrendVO> trend = service.getHourlyTrend(1L, bucket, t2);

        assertEquals(1, trend.size());
        PlayerStatsTrendVO vo = trend.get(0);
        // 同一小时内 3 个不同 steamId
        assertEquals(3L, vo.getUniquePlayers());
    }

    // ============================================================
    // search_players_by_steamid
    // ============================================================
    @Test
    void search_players_by_steamid() {
        long now = System.currentTimeMillis() / 1000L;
        List<PlayerStatPlayerResource> players = List.of(
                buildPlayer("p1", 1L, now, "STEAM_1:0:111", "Alice"),
                buildPlayer("p2", 1L, now - 600, "STEAM_1:0:222", "Bob"),
                buildPlayer("p3", 1L, now - 1200, "STEAM_1:0:111", "Alice")
        );
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsPlayerVO> result = service.searchPlayers(1L, "STEAM_1:0:111", 0L);

        assertEquals(1, result.size());
        PlayerStatsPlayerVO vo = result.get(0);
        assertEquals("STEAM_1:0:111", vo.getSteamId());
        assertEquals("Alice", vo.getName());
        assertEquals(now, vo.getLastSeen());
        // 2 个采样 × 10 分钟 = 20 分钟
        assertEquals(20L, vo.getEstimatedMinutes());
        assertEquals(1, vo.getRank());
    }

    // ============================================================
    // search_players_by_name
    // ============================================================
    @Test
    void search_players_by_name() {
        long now = System.currentTimeMillis() / 1000L;
        List<PlayerStatPlayerResource> players = List.of(
                buildPlayer("p1", 1L, now, "STEAM_1:0:111", "Alice"),
                buildPlayer("p2", 1L, now - 600, "STEAM_1:0:222", "Bob"),
                buildPlayer("p3", 1L, now - 1200, "STEAM_1:0:333", "Alice Cooper")
        );
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsPlayerVO> result = service.searchPlayers(1L, "alice", 0L);

        assertEquals(2, result.size());
        // 排序：samples desc → Alice(1 sample) 与 Alice Cooper(1 sample) 同分；lastSeen desc
        // Alice(now) > Alice Cooper(now-1200)，所以 Alice 在前
        assertEquals("Alice", result.get(0).getName());
        assertEquals("Alice Cooper", result.get(1).getName());
    }

    // ============================================================
    // search_players_empty_keyword_returns_all
    // ============================================================
    @Test
    void search_players_empty_keyword_returns_all() {
        long now = System.currentTimeMillis() / 1000L;
        List<PlayerStatPlayerResource> players = new ArrayList<>();
        players.add(buildPlayer("p1", 1L, now, "STEAM_1:0:111", "Alice"));
        players.add(buildPlayer("p2", 1L, now - 600, "STEAM_1:0:222", "Bob"));
        players.add(buildPlayer("p3", 1L, now - 60, "STEAM_1:0:222", "Bob"));
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsPlayerVO> result = service.searchPlayers(1L, "", 0L);

        // 2 个不同 steamId
        assertEquals(2, result.size());
        // 排序：Bob (2 samples) 在前，Alice (1 sample) 在后
        assertEquals("Bob", result.get(0).getName());
        assertEquals(1, result.get(0).getRank());
        assertEquals(20L, result.get(0).getEstimatedMinutes()); // 2 × 10
        assertEquals("Alice", result.get(1).getName());
        assertEquals(2, result.get(1).getRank());
    }

    // ============================================================
    // player_days_estimates_minutes_correctly
    // ============================================================
    @Test
    void player_days_estimates_minutes_correctly() {
        long day1 = 1_700_000_000L;
        long day2 = day1 + 24 * 3600L;
        List<PlayerStatPlayerResource> players = List.of(
                buildPlayer("p1", 1L, day1, "STEAM_1:0:111", "Alice"),
                buildPlayer("p2", 1L, day1 + 600, "STEAM_1:0:111", "Alice"),
                buildPlayer("p3", 1L, day2, "STEAM_1:0:111", "Alice")
        );
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsDayVO> days = service.getPlayerDays(1L, "STEAM_1:0:111", 0L);

        // 按本地时区可能落在 1~2 天，但至少 1 个
        assertTrue(days.size() >= 1);
        int totalSamples = days.stream().mapToInt(PlayerStatsDayVO::getSamples).sum();
        assertEquals(3, totalSamples);
        int totalMinutes = days.stream().mapToInt(PlayerStatsDayVO::getOnlineMinutes).sum();
        assertEquals(30, totalMinutes); // 3 × 10
    }

    // ============================================================
    // player_aliases_aggregates_names
    // ============================================================
    @Test
    void player_aliases_aggregates_names() {
        long now = System.currentTimeMillis() / 1000L;
        List<PlayerStatPlayerResource> players = List.of(
                buildPlayer("p1", 1L, now - 1200, "STEAM_1:0:111", "Alice"),
                buildPlayer("p2", 1L, now - 600, "STEAM_1:0:111", "Alice2"),
                buildPlayer("p3", 1L, now, "STEAM_1:0:111", "Alice"),
                buildPlayer("p4", 1L, now - 60, "STEAM_1:0:111", "  ") // 空名 → Unknown
        );
        when(extensionClient.list(eq(PlayerStatPlayerResource.class), any(ListOptions.class)))
                .thenReturn(players);

        List<PlayerStatsAliasVO> aliases = service.getPlayerAliases(1L, "STEAM_1:0:111", 0L);

        // 3 个别名：Alice(2), Alice2(1), Unknown(1)
        assertEquals(3, aliases.size());
        // 按 lastSeen desc 排序：Alice(now) → Unknown(now-60) → Alice2(now-600)
        assertEquals("Alice", aliases.get(0).getName());
        assertEquals(2, aliases.get(0).getSamples());
        assertEquals(20L, aliases.get(0).getEstimatedMinutes());
        assertEquals("Unknown", aliases.get(1).getName());
        assertEquals("Alice2", aliases.get(2).getName());
    }

    // ============================================================
    // get_config_returns_current_settings
    // ============================================================
    @Test
    void get_config_returns_current_settings() {
        when(extensionClient.list(eq(PlayerStatSnapshotResource.class), any(ListOptions.class)))
                .thenReturn(List.of());

        PlayerStatsConfigVO vo = service.getConfig();

        assertNotNull(vo);
        assertTrue(vo.getEnabled());
        assertEquals(10, vo.getIntervalMinutes());
        assertEquals(30, vo.getRetentionDays());
        // 无快照
        assertNull(vo.getLastSnapshot());
    }

    // ============================================================
    // set_enabled_toggles_runtime_flag
    // ============================================================
    @Test
    void set_enabled_toggles_runtime_flag() {
        assertTrue(service.isEnabled());
        service.setEnabled(false);
        assertFalse(service.isEnabled());
        // 同步到 config
        assertFalse(config.getPlayerStats().isEnabled());
        service.setEnabled(true);
        assertTrue(service.isEnabled());
        assertTrue(config.getPlayerStats().isEnabled());
    }

    // ============================================================
    // collect_filters_non_l4d2_instances
    // ============================================================
    @Test
    void collect_filters_non_l4d2_instances() {
        InstanceVO l4d2Instance = buildInstance(1L, "127.0.0.1", 27015, "test-pwd");
        l4d2Instance.setGameCode("l4d2");
        InstanceVO otherInstance = buildInstance(2L, "127.0.0.1", 27016, "test-pwd");
        otherInstance.setGameCode("minecraft");
        when(instanceQueryService.getInstancesByGameId(null))
                .thenReturn(List.of(l4d2Instance, otherInstance));
        when(rconService.executeCommand(anyLong(), anyString()))
                .thenReturn("hostname: x\n");

        service.collectPlayerStats();

        // 只采集 l4d2 实例（id=1）
        verify(rconService).executeCommand(eq(1L), eq("status"));
        verify(rconService, never()).executeCommand(eq(2L), eq("status"));
    }

    // ============================================================
    // strip_port_helper_works
    // ============================================================
    @Test
    void collect_calls_geoip_with_stripped_ip() {
        InstanceVO instance = buildInstance(1L, "127.0.0.1", 27015, "test-pwd");
        when(instanceQueryService.getInstancesByGameId(null)).thenReturn(List.of(instance));

        String statusText = String.join("\n",
                "hostname: Test",
                "map     : c1m1_hotel",
                "players : 1 humans, 0 bots (30 max)",
                "#     2 2 \"Player\" STEAM_1:0:111 1:23:45 30 0 active 5 1.2.3.4:27005",
                "#end");
        when(rconService.executeCommand(anyLong(), anyString()))
                .thenReturn(statusText);
        when(geoIpService.queryProvince(anyString())).thenReturn("上海");

        service.collectPlayerStats();

        // 验证 GeoIpService 收到的是去掉端口的 IP
        verify(geoIpService).queryProvince(eq("1.2.3.4"));
    }

    // ===== 辅助方法 =====

    private InstanceVO buildInstance(Long id, String hostIp, int rconPort, String rconPassword) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        vo.setHostId(10L);
        vo.setHostIp(hostIp);
        vo.setGameCode("l4d2");
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", rconPort);
        configInfo.put("rconPassword", rconPassword);
        vo.setConfigInfo(configInfo);
        return vo;
    }

    private PlayerStatSnapshotResource buildSnapshot(String id, Long instanceId, long timestamp,
                                                      boolean online, boolean ok, int players) {
        PlayerStatSnapshotResource r = new PlayerStatSnapshotResource();
        r.setId(id);
        r.setName(instanceId + "-" + timestamp);
        PlayerStatSnapshotSpec spec = new PlayerStatSnapshotSpec();
        spec.setInstanceId(instanceId);
        spec.setTimestamp(timestamp);
        spec.setServerOnline(online);
        spec.setCollectOk(ok);
        spec.setPlayerCount(players);
        spec.setMaxPlayers(30);
        spec.setMap("c1m1_hotel");
        spec.setHostname("Test");
        spec.setDifficulty("普通");
        spec.setGameMode("合作");
        spec.setErrorMessage("");
        r.setSpec(spec);
        return r;
    }

    private PlayerStatPlayerResource buildPlayer(String id, Long instanceId, long timestamp,
                                                  String steamId, String name) {
        PlayerStatPlayerResource r = new PlayerStatPlayerResource();
        r.setId(id);
        r.setName(instanceId + "-" + steamId + "-" + timestamp);
        PlayerStatPlayerSpec spec = new PlayerStatPlayerSpec();
        spec.setInstanceId(instanceId);
        spec.setSnapshotId("snap-" + id);
        spec.setTimestamp(timestamp);
        spec.setSteamId(steamId);
        spec.setName(name);
        spec.setIp("1.2.3.4:27005");
        spec.setLocation("北京");
        spec.setStatus("active");
        spec.setDelay(30);
        spec.setLoss(0);
        spec.setDuration("1:23:45");
        spec.setLinkRate(5);
        r.setSpec(spec);
        return r;
    }
}
