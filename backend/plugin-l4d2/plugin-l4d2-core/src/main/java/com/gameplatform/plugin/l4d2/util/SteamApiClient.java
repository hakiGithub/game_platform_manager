package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.ExternalHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Steam Web API 客户端：封装 IPublishedFileService/GetDetails 等接口。
 *
 * <p>本类通过 {@link ExternalHttpClient}（已封装 RestClient）发起 HTTP 请求，
 * 便于单测 mock。
 *
 * <p>对齐 spec §4 模块 10：将源项目 {@code workshop.go} 中对私有解析服务
 * {@code l4d2-workshop-parse.laoyutang.cn} 的依赖改为直接调用 Steam 官方 API。
 *
 * <p>核心端点：
 * <pre>
 * POST https://api.steampowered.com/IPublishedFileService/GetDetails/v1/
 * Content-Type: application/x-www-form-urlencoded
 *
 * key={api_key}&appid=550&publishedfileids[0]={id1}&includechildren=true
 * </pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamApiClient {

    /** Steam PublishedFileService GetDetails 端点 */
    private static final String GET_DETAILS_URL =
            "https://api.steampowered.com/IPublishedFileService/GetDetails/v1/";

    /** IPlayerService/GetOwnedGames 端点（查询玩家拥有的游戏 + 总时长） */
    private static final String GET_OWNED_GAMES_URL =
            "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/";

    /** ISteamUserStats/GetUserStatsForGame 端点（查询玩家游戏内统计 + 实战时长） */
    private static final String GET_USER_STATS_FOR_GAME_URL =
            "https://api.steampowered.com/ISteamUserStats/GetUserStatsForGame/v2/";

    /** L4D2 实战时长统计名（对齐源项目 playtime.go:119） */
    private static final String STAT_TOTAL_PLAY_TIME = "Stat.TotalPlayTime.Total";

    private final ExternalHttpClient httpClient;
    private final L4D2Config config;

    /**
     * 调用 IPublishedFileService/GetDetails/v1 批量查询 Workshop 文件详情。
     *
     * <p>对齐源项目 {@code workshop.go:122-152 fetchWorkshopDetails}。
     *
     * @param publishedFileIds Workshop 文件 ID 列表（纯数字字符串）
     * @return 详情列表（顺序不保证与入参一致，调用方按 publishedFileId 匹配）
     * @throws L4D2PluginException 当 API key 未配置或 HTTP 请求失败时抛出
     */
    @SuppressWarnings("unchecked")
    public List<WorkshopDetail> getPublishedFileDetails(List<String> publishedFileIds) {
        if (publishedFileIds == null || publishedFileIds.isEmpty()) {
            return Collections.emptyList();
        }

        L4D2Config.Steam steam = config.getSteam();
        String apiKey = steam == null ? null : steam.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "STEAM_API_KEY 未配置，请在 application.yml 中设置 plugin.l4d2.steam.api-key");
        }
        int appid = steam == null ? 550 : steam.getL4d2Appid();

        // 构造 form-urlencoded body
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", apiKey);
        form.add("appid", String.valueOf(appid));
        for (int i = 0; i < publishedFileIds.size(); i++) {
            form.add("publishedfileids[" + i + "]", publishedFileIds.get(i));
        }
        form.add("includechildren", "true");

        Map<String, Object> resp;
        try {
            resp = httpClient.postForObject(GET_DETAILS_URL, form, Map.class);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                    "Steam API 调用失败: " + e.getMessage(), e);
        }
        if (resp == null) {
            return Collections.emptyList();
        }

        Object responseObj = resp.get("response");
        if (!(responseObj instanceof Map<?, ?> responseMap)) {
            return Collections.emptyList();
        }
        Object detailsObj = responseMap.get("publishedfiledetails");
        if (!(detailsObj instanceof List<?> rawList)) {
            return Collections.emptyList();
        }

        List<WorkshopDetail> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            WorkshopDetail detail = parseDetail(m);
            if (detail != null) {
                result.add(detail);
            }
        }
        return result;
    }

    /**
     * 查询玩家拥有的游戏列表，匹配指定 appid 返回总时长（playtime_forever，分钟）。
     *
     * <p>对齐源项目 {@code playtime.go:81-100}：调用 IPlayerService/GetOwnedGames/v1，
     * 解析 {@code response.games[]}，匹配 {@code appid == 550} 取 {@code playtime_forever}。
     *
     * @param steamId64 玩家 SteamID64（17 位数字字符串）
     * @param appid     游戏 AppID（L4D2 = 550）
     * @return OwnedGamesResult（found=true 表示玩家拥有该游戏并返回了总时长）
     * @throws L4D2PluginException 当 API key 未配置或 HTTP 请求失败时抛出
     */
    @SuppressWarnings("unchecked")
    public OwnedGamesResult getOwnedGames(String steamId64, int appid) {
        String apiKey = requireApiKey();

        Map<String, Object> params = new HashMap<>();
        params.put("key", apiKey);
        params.put("steamid", steamId64);
        params.put("format", "json");
        params.put("include_appinfo", "false");

        Map<String, Object> resp;
        try {
            resp = httpClient.getForObject(GET_OWNED_GAMES_URL, Map.class, params);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                    "Steam API 调用失败: " + e.getMessage(), e);
        }
        if (resp == null) {
            return new OwnedGamesResult(false, 0L);
        }

        Object responseObj = resp.get("response");
        if (!(responseObj instanceof Map<?, ?> responseMap)) {
            return new OwnedGamesResult(false, 0L);
        }
        Object gamesObj = responseMap.get("games");
        if (!(gamesObj instanceof List<?> gamesList)) {
            return new OwnedGamesResult(false, 0L);
        }

        for (Object item : gamesList) {
            if (!(item instanceof Map<?, ?> game)) {
                continue;
            }
            int gameAppid = asInt(game.get("appid"), -1);
            if (gameAppid == appid) {
                long minutes = asLong(game.get("playtime_forever"));
                return new OwnedGamesResult(true, minutes);
            }
        }
        return new OwnedGamesResult(false, 0L);
    }

    /**
     * 查询玩家游戏内统计，匹配 Stat.TotalPlayTime.Total 返回实战时长（秒）。
     *
     * <p>对齐源项目 {@code playtime.go:102-125}：调用 ISteamUserStats/GetUserStatsForGame/v2，
     * 解析 {@code playerstats.stats[]}，匹配 {@code name == "Stat.TotalPlayTime.Total"} 取 {@code value}。
     *
     * @param steamId64 玩家 SteamID64（17 位数字字符串）
     * @param appid     游戏 AppID（L4D2 = 550）
     * @return UserStatsResult（found=true 表示玩家资料公开且包含 TotalPlayTime 统计）
     * @throws L4D2PluginException 当 API key 未配置或 HTTP 请求失败时抛出
     */
    @SuppressWarnings("unchecked")
    public UserStatsResult getUserStatsForGame(String steamId64, int appid) {
        String apiKey = requireApiKey();

        Map<String, Object> params = new HashMap<>();
        params.put("key", apiKey);
        params.put("steamid", steamId64);
        params.put("appid", String.valueOf(appid));
        params.put("format", "json");

        Map<String, Object> resp;
        try {
            resp = httpClient.getForObject(GET_USER_STATS_FOR_GAME_URL, Map.class, params);
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                    "Steam API 调用失败: " + e.getMessage(), e);
        }
        if (resp == null) {
            return new UserStatsResult(false, 0L);
        }

        Object playerStatsObj = resp.get("playerstats");
        if (!(playerStatsObj instanceof Map<?, ?> playerStats)) {
            return new UserStatsResult(false, 0L);
        }
        Object statsObj = playerStats.get("stats");
        if (!(statsObj instanceof List<?> statsList)) {
            return new UserStatsResult(false, 0L);
        }

        for (Object item : statsList) {
            if (!(item instanceof Map<?, ?> stat)) {
                continue;
            }
            Object nameObj = stat.get("name");
            if (nameObj == null) {
                continue;
            }
            if (STAT_TOTAL_PLAY_TIME.equals(nameObj.toString())) {
                long seconds = asLong(stat.get("value"));
                return new UserStatsResult(true, seconds);
            }
        }
        return new UserStatsResult(false, 0L);
    }

    /**
     * 校验并返回 Steam API key，未配置时抛业务异常。
     */
    private String requireApiKey() {
        L4D2Config.Steam steam = config.getSteam();
        String apiKey = steam == null ? null : steam.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "STEAM_API_KEY 未配置，请在 application.yml 中设置 plugin.l4d2.steam.api-key");
        }
        return apiKey;
    }

    // ===== 私有辅助方法 =====

    /**
     * 解析单个 publishedfiledetails 元素为 {@link WorkshopDetail}。
     */
    @SuppressWarnings("unchecked")
    private WorkshopDetail parseDetail(Map<?, ?> m) {
        String publishedFileId = asString(m.get("publishedfileid"));
        if (publishedFileId == null || publishedFileId.isBlank()) {
            return null;
        }
        int result = asInt(m.get("result"), 0);
        String title = asString(m.get("title"));
        String filename = asString(m.get("filename"));
        long fileSize = asLong(m.get("file_size"));
        String fileUrl = asString(m.get("file_url"));
        String previewUrl = asString(m.get("preview_url"));
        List<String> childrenIds = new ArrayList<>();
        Object childrenObj = m.get("children");
        if (childrenObj instanceof List<?> childrenList) {
            for (Object child : childrenList) {
                if (child instanceof Map<?, ?> cm) {
                    String childId = asString(cm.get("publishedfileid"));
                    if (childId != null && !childId.isBlank()) {
                        childrenIds.add(childId.trim());
                    }
                }
            }
        }
        return new WorkshopDetail(
                publishedFileId.trim(),
                result,
                title,
                filename,
                fileSize,
                fileUrl == null ? null : fileUrl.trim(),
                previewUrl == null ? null : previewUrl.trim(),
                childrenIds
        );
    }

    private String asString(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private int asInt(Object o, int defaultValue) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Workshop 文件详情 record（对齐源项目 workshop.go WorkshopDownloadItem）。
     *
     * @param publishedFileId Workshop 文件 ID
     * @param result          Steam API 返回码（1=成功）
     * @param title           标题
     * @param fileName        文件名
     * @param fileSize        文件大小（字节）
     * @param fileUrl         文件下载 URL
     * @param previewUrl      预览图 URL
     * @param childrenIds     合集子项 ID 列表（无子项时为空列表）
     */
    public record WorkshopDetail(
            String publishedFileId,
            int result,
            String title,
            String fileName,
            long fileSize,
            String fileUrl,
            String previewUrl,
            List<String> childrenIds
    ) {
    }

    /**
     * GetOwnedGames 结果（对齐源项目 playtime.go:81-100）。
     *
     * @param found                  玩家是否拥有该游戏（在 games 数组中匹配到 appid）
     * @param playtimeForeverMinutes 总时长（分钟，playtime_forever）
     */
    public record OwnedGamesResult(boolean found, long playtimeForeverMinutes) {
    }

    /**
     * GetUserStatsForGame 结果（对齐源项目 playtime.go:102-125）。
     *
     * @param found                玩家资料是否公开且包含 TotalPlayTime 统计
     * @param totalPlayTimeSeconds 实战时长（秒，Stat.TotalPlayTime.Total）
     */
    public record UserStatsResult(boolean found, long totalPlayTimeSeconds) {
    }
}
