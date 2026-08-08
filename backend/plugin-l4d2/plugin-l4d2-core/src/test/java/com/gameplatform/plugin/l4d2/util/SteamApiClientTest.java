package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.ExternalHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SteamApiClient 单元测试（对齐 plan §4.2.6）。
 *
 * <p>所有 HTTP 请求通过 mock ExternalHttpClient 完成，不发起真实网络请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SteamApiClientTest {

    @Mock
    private ExternalHttpClient httpClient;

    private L4D2Config config;

    private SteamApiClient client;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getSteam().setApiKey("test-api-key");
        config.getSteam().setL4d2Appid(550);
        client = new SteamApiClient(httpClient, config);
    }

    // ============================================================
    // get_details_api_key_missing：API key 未配置 → 抛异常
    // ============================================================

    @Test
    void get_details_api_key_missing() {
        config.getSteam().setApiKey("");
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> client.getPublishedFileDetails(List.of("123456")));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        assertTrue(ex.getMessage().contains("STEAM_API_KEY 未配置"));
    }

    @Test
    void get_details_api_key_null() {
        config.getSteam().setApiKey(null);
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> client.getPublishedFileDetails(List.of("123456")));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ============================================================
    // get_details_single：单个 ID 查询
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_single() {
        Map<String, Object> resp = buildSteamResponse(List.of(
                buildDetailMap("123456", 1, "Test Map", "test.vpk",
                        "12345", "https://download/test.vpk",
                        "https://preview/test.png", null)
        ));
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));

        assertEquals(1, result.size());
        SteamApiClient.WorkshopDetail d = result.get(0);
        assertEquals("123456", d.publishedFileId());
        assertEquals(1, d.result());
        assertEquals("Test Map", d.title());
        assertEquals("test.vpk", d.fileName());
        assertEquals(12345L, d.fileSize());
        assertEquals("https://download/test.vpk", d.fileUrl());
        assertEquals("https://preview/test.png", d.previewUrl());
        assertTrue(d.childrenIds().isEmpty());

        // 验证 form 数据包含 key / appid / publishedfileids[0] / includechildren
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(httpClient).postForObject(anyString(), captor.capture(), eq(Map.class));
        MultiValueMap<String, String> form = captor.getValue();
        assertEquals("test-api-key", form.getFirst("key"));
        assertEquals("550", form.getFirst("appid"));
        assertEquals("123456", form.getFirst("publishedfileids[0]"));
        assertEquals("true", form.getFirst("includechildren"));
    }

    // ============================================================
    // get_details_with_children：含 children 的详情
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_with_children() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(Map.of("publishedfileid", "789"));
        children.add(Map.of("publishedfileid", "999"));

        Map<String, Object> resp = buildSteamResponse(List.of(
                buildDetailMap("123456", 1, "Collection", "collection.vpk",
                        "0", "https://download/col.vpk",
                        "https://preview/col.png", children)
        ));
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));

        assertEquals(1, result.size());
        SteamApiClient.WorkshopDetail d = result.get(0);
        assertEquals(2, d.childrenIds().size());
        assertEquals("789", d.childrenIds().get(0));
        assertEquals("999", d.childrenIds().get(1));
    }

    // ============================================================
    // get_details_multiple_ids：批量 ID 查询
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_multiple_ids() {
        Map<String, Object> resp = buildSteamResponse(List.of(
                buildDetailMap("111", 1, "A", "a.vpk", "100", "https://a", null, null),
                buildDetailMap("222", 1, "B", "b.vpk", "200", "https://b", null, null)
        ));
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result =
                client.getPublishedFileDetails(List.of("111", "222"));

        assertEquals(2, result.size());

        // 验证 form 数据包含 publishedfileids[0] 和 publishedfileids[1]
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(httpClient).postForObject(anyString(), captor.capture(), eq(Map.class));
        MultiValueMap<String, String> form = captor.getValue();
        assertEquals("111", form.getFirst("publishedfileids[0]"));
        assertEquals("222", form.getFirst("publishedfileids[1]"));
    }

    // ============================================================
    // get_details_empty_response：空 publishedfiledetails
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_empty_response() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("publishedfiledetails", List.of());
        resp.put("response", inner);
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================
    // get_details_null_response：返回 null
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_null_response() {
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(null);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================
    // get_details_no_response_key：缺少 response 键
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_no_response_key() {
        when(httpClient.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(new HashMap<>());

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================
    // get_details_empty_input：空 ID 列表
    // ============================================================

    @Test
    void get_details_empty_input() {
        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void get_details_null_input() {
        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================
    // get_details_skips_invalid_entries：跳过 publishedfileid 为空的项
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_skips_invalid_entries() {
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("result", 1);
        invalid.put("publishedfileid", "");  // 空 ID
        invalid.put("title", "Invalid");

        Map<String, Object> resp = buildSteamResponse(List.of(
                invalid,
                buildDetailMap("123456", 1, "Valid", "valid.vpk",
                        "100", "https://download", null, null)
        ));
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));
        assertEquals(1, result.size());
        assertEquals("123456", result.get(0).publishedFileId());
    }

    // ============================================================
    // get_details_steam_error：HTTP 请求失败 → 抛异常
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_steam_error() {
        when(httpClient.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "Steam API 500"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> client.getPublishedFileDetails(List.of("123456")));
        assertEquals(L4D2PluginException.EXTERNAL_API, ex.getCode());
    }

    // ============================================================
    // get_details_result_zero：result=0 仍解析（由调用方过滤）
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_details_result_zero() {
        Map<String, Object> resp = buildSteamResponse(List.of(
                buildDetailMap("123456", 0, "Failed", "f.vpk",
                        "0", "", null, null)
        ));
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        List<SteamApiClient.WorkshopDetail> result = client.getPublishedFileDetails(List.of("123456"));
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).result());
    }

    // ============================================================
    // get_owned_games_returns_playtime：包含 appid=550 的 games 数组
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_owned_games_returns_playtime() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        List<Map<String, Object>> games = new ArrayList<>();
        // 另一个游戏
        games.add(Map.of("appid", 440, "playtime_forever", 600));
        // L4D2 (appid=550) 总时长 120 分钟
        games.add(Map.of("appid", 550, "playtime_forever", 120));
        inner.put("games", games);
        resp.put("response", inner);
        when(httpClient.getForObject(anyString(), eq(Map.class), anyMap())).thenReturn(resp);

        SteamApiClient.OwnedGamesResult result = client.getOwnedGames("76561197960287930", 550);

        assertTrue(result.found());
        assertEquals(120L, result.playtimeForeverMinutes());

        // 验证 query 参数包含 key / steamid / format / include_appinfo
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).getForObject(anyString(), eq(Map.class), captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals("test-api-key", params.get("key"));
        assertEquals("76561197960287930", params.get("steamid"));
        assertEquals("json", params.get("format"));
        assertEquals("false", params.get("include_appinfo"));
    }

    // ============================================================
    // get_owned_games_not_in_library：games 不含 550 → found=false
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_owned_games_not_in_library() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        List<Map<String, Object>> games = new ArrayList<>();
        games.add(Map.of("appid", 440, "playtime_forever", 600));
        inner.put("games", games);
        resp.put("response", inner);
        when(httpClient.getForObject(anyString(), eq(Map.class), anyMap())).thenReturn(resp);

        SteamApiClient.OwnedGamesResult result = client.getOwnedGames("76561197960287930", 550);

        assertFalse(result.found());
        assertEquals(0L, result.playtimeForeverMinutes());
    }

    // ============================================================
    // get_user_stats_returns_total_playtime：包含 Stat.TotalPlayTime.Total
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_user_stats_returns_total_playtime() {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> playerStats = new HashMap<>();
        List<Map<String, Object>> stats = new ArrayList<>();
        stats.add(Map.of("name", "Stat.TotalPlayTime.Total", "value", 3600)); // 1 小时 = 3600 秒
        stats.add(Map.of("name", "Other.Stat", "value", 100));
        playerStats.put("stats", stats);
        resp.put("playerstats", playerStats);
        when(httpClient.getForObject(anyString(), eq(Map.class), anyMap())).thenReturn(resp);

        SteamApiClient.UserStatsResult result = client.getUserStatsForGame("76561197960287930", 550);

        assertTrue(result.found());
        assertEquals(3600L, result.totalPlayTimeSeconds());

        // 验证 query 参数包含 key / steamid / appid / format
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).getForObject(anyString(), eq(Map.class), captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals("test-api-key", params.get("key"));
        assertEquals("76561197960287930", params.get("steamid"));
        assertEquals("550", params.get("appid"));
        assertEquals("json", params.get("format"));
    }

    // ============================================================
    // get_user_stats_profile_not_public：空 stats 或错误响应 → found=false
    // ============================================================

    @SuppressWarnings("unchecked")
    @Test
    void get_user_stats_profile_not_public() {
        // 模拟资料未公开：playerstats 中无 stats 字段
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> playerStats = new HashMap<>();
        resp.put("playerstats", playerStats);
        when(httpClient.getForObject(anyString(), eq(Map.class), anyMap())).thenReturn(resp);

        SteamApiClient.UserStatsResult result = client.getUserStatsForGame("76561197960287930", 550);

        assertFalse(result.found());
        assertEquals(0L, result.totalPlayTimeSeconds());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 构造 Steam API 响应：{@code {"response":{"publishedfiledetails":[...]}}}
     */
    private Map<String, Object> buildSteamResponse(List<Map<String, Object>> details) {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("publishedfiledetails", details);
        resp.put("response", inner);
        return resp;
    }

    /**
     * 构造单个 publishedfiledetails 元素。
     */
    private Map<String, Object> buildDetailMap(String id, int result, String title, String filename,
                                                String fileSize, String fileUrl, String previewUrl,
                                                List<Map<String, Object>> children) {
        Map<String, Object> m = new HashMap<>();
        m.put("publishedfileid", id);
        m.put("result", result);
        m.put("title", title);
        m.put("filename", filename);
        m.put("file_size", fileSize);
        m.put("file_url", fileUrl);
        if (previewUrl != null) {
            m.put("preview_url", previewUrl);
        }
        if (children != null) {
            m.put("children", children);
        }
        return m;
    }
}
