package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.util.SteamApiClient;
import com.gameplatform.plugin.l4d2.vo.PlaytimeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PlaytimeService 单元测试（对齐 plan §5.2.6）。
 *
 * <p>SteamApiClient 被 mock，所有用例不发起真实 HTTP 请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaytimeServiceTest {

    private static final String STEAM_ID = "STEAM_0:1:1234";
    private static final String STEAM_ID64 = "76561197960268197";

    @Mock
    private SteamApiClient steamApiClient;

    private L4D2Config config;

    private PlaytimeService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getSteam().setApiKey("test-api-key");
        service = new PlaytimeService(steamApiClient, config);
    }

    // ============================================================
    // get_playtime_both_apis_succeed：两个 API 都返回 found=true
    // ============================================================

    @Test
    void get_playtime_both_apis_succeed() {
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.OwnedGamesResult(true, 120L)); // 120 分钟 = 2 小时
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.UserStatsResult(true, 7200L)); // 7200 秒 = 2 小时

        PlaytimeVO vo = service.getPlaytime(STEAM_ID);

        assertNotNull(vo);
        assertEquals(STEAM_ID, vo.getSteamId());
        assertEquals(STEAM_ID64, vo.getSteamId64());
        assertEquals(2.0, vo.getTotalPlaytimeHours(), 0.001);
        assertEquals(2.0, vo.getRealPlaytimeHours(), 0.001);
        assertEquals("Steam Web API", vo.getSource());
    }

    // ============================================================
    // get_playtime_only_owned_games_succeeds：只有 OwnedGames found=true
    // ============================================================

    @Test
    void get_playtime_only_owned_games_succeeds() {
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.OwnedGamesResult(true, 180L)); // 3 小时
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.UserStatsResult(false, 0L));

        PlaytimeVO vo = service.getPlaytime(STEAM_ID);

        assertNotNull(vo);
        assertEquals(3.0, vo.getTotalPlaytimeHours(), 0.001);
        assertEquals(0.0, vo.getRealPlaytimeHours(), 0.001);
    }

    // ============================================================
    // get_playtime_only_user_stats_succeeds：只有 UserStats found=true
    // ============================================================

    @Test
    void get_playtime_only_user_stats_succeeds() {
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.OwnedGamesResult(false, 0L));
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.UserStatsResult(true, 3600L)); // 1 小时

        PlaytimeVO vo = service.getPlaytime(STEAM_ID);

        assertNotNull(vo);
        assertEquals(0.0, vo.getTotalPlaytimeHours(), 0.001);
        assertEquals(1.0, vo.getRealPlaytimeHours(), 0.001);
    }

    // ============================================================
    // get_playtime_both_fail_throws_exception：两个都 found=false → IllegalStateException
    // ============================================================

    @Test
    void get_playtime_both_fail_throws_exception() {
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.OwnedGamesResult(false, 0L));
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.UserStatsResult(false, 0L));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getPlaytime(STEAM_ID));
        assertTrue(ex.getMessage().contains("未找到玩家的游戏数据"));
    }

    // ============================================================
    // get_playtime_invalid_steamid_throws：SteamID 格式无效 → 抛异常
    // ============================================================

    @Test
    void get_playtime_invalid_steamid_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPlaytime("invalid"));
        assertTrue(ex.getMessage().contains("无效的 SteamID"));
    }

    // ============================================================
    // get_playtime_concurrent_timeout_handled：超时不阻塞主流程
    // ============================================================

    @Test
    void get_playtime_concurrent_timeout_handled() {
        // 缩短超时时间到 100ms，使慢响应触发超时
        config.getPlaytime().setRequestTimeoutMs(100L);

        // OwnedGames 立即返回 found=true（部分结果可用）
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenReturn(new SteamApiClient.OwnedGamesResult(true, 60L)); // 1 小时
        // UserStats 模拟慢响应（sleep 2s，远超 100ms 超时）
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(2000L);
                    return new SteamApiClient.UserStatsResult(true, 3600L);
                });

        long start = System.currentTimeMillis();
        PlaytimeVO vo = service.getPlaytime(STEAM_ID);
        long elapsed = System.currentTimeMillis() - start;

        // 验证主流程未因慢响应阻塞太久（应远小于 2s）
        assertTrue(elapsed < 2000L,
                "Service should not block on slow API, elapsed=" + elapsed + "ms");
        // 部分结果应正确返回
        assertNotNull(vo);
        assertEquals(1.0, vo.getTotalPlaytimeHours(), 0.001);
    }

    // ============================================================
    // get_playtime_both_apis_throw_exception：两个 API 都抛异常 → RuntimeException
    // ============================================================

    @Test
    void get_playtime_both_apis_throw_exception() {
        when(steamApiClient.getOwnedGames(eq(STEAM_ID64), anyInt()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "Steam 500"));
        when(steamApiClient.getUserStatsForGame(eq(STEAM_ID64), anyInt()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "Steam 500"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getPlaytime(STEAM_ID));
        assertTrue(ex.getMessage().contains("Steam API 查询失败"));
    }
}
