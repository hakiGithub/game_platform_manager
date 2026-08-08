package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.util.SteamApiClient;
import com.gameplatform.plugin.l4d2.util.SteamIdUtil;
import com.gameplatform.plugin.l4d2.vo.PlaytimeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Steam 游玩时长查询服务（Phase 5.2）。
 *
 * <p>对齐源项目 {@code playtime.go:61-142 getPlaytimeDetails}：并发请求两个 Steam API
 * 获取 L4D2 玩家的总时长（GetOwnedGames.playtime_forever）与实战时长
 * （GetUserStatsForGame.Stat.TotalPlayTime.Total），任一成功即返回。
 *
 * <p>核心流程：
 * <ol>
 *   <li>将 STEAM_X:Y:Z 转换为 SteamID64</li>
 *   <li>并发提交两个 {@link CompletableFuture} 调用 {@link SteamApiClient}</li>
 *   <li>通过 {@link CompletableFuture#allOf} 等待两个 future 完成，超时由
 *       {@code L4D2Config.Playtime.requestTimeoutMs} 控制（默认 10s）</li>
 *   <li>任一 API 返回 found=true 即填充对应字段；两个都未找到且未抛异常 → 抛
 *       {@link IllegalStateException}；两个都抛异常 → 抛 {@link RuntimeException}</li>
 * </ol>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaytimeService {

    /** L4D2 AppID（固定 550） */
    private static final int L4D2_APPID = 550;

    /** 数据来源标记 */
    private static final String SOURCE = "Steam Web API";

    private final SteamApiClient steamApiClient;
    private final L4D2Config config;

    /**
     * 查询玩家 L4D2 游玩时长（总时长 + 实战时长）。
     *
     * @param steamId 玩家 SteamID（STEAM_X:Y:Z 格式）
     * @return PlaytimeVO（包含 steamId/steamId64/totalPlaytimeHours/realPlaytimeHours/source）
     * @throws IllegalArgumentException SteamID 格式无效
     * @throws IllegalStateException    两个 API 均未找到玩家数据且未抛异常
     * @throws RuntimeException         两个 API 均抛异常
     */
    public PlaytimeVO getPlaytime(String steamId) {
        if (steamId == null || steamId.isBlank()) {
            throw new IllegalArgumentException("SteamID 不能为空");
        }

        long steam64;
        try {
            steam64 = SteamIdUtil.toSteam64(steamId);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 SteamID: " + steamId, e);
        }
        String steamId64 = String.valueOf(steam64);

        long timeoutMs = config.getPlaytime() == null
                ? 10_000L
                : config.getPlaytime().getRequestTimeoutMs();

        CompletableFuture<SteamApiClient.OwnedGamesResult> ownedGamesFuture =
                CompletableFuture.supplyAsync(() -> steamApiClient.getOwnedGames(steamId64, L4D2_APPID));
        CompletableFuture<SteamApiClient.UserStatsResult> userStatsFuture =
                CompletableFuture.supplyAsync(() -> steamApiClient.getUserStatsForGame(steamId64, L4D2_APPID));

        // 等待两个 future 完成（带超时）
        try {
            CompletableFuture.allOf(ownedGamesFuture, userStatsFuture)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Steam API 查询超时, steamId={}, timeoutMs={}", steamId, timeoutMs);
            ownedGamesFuture.cancel(true);
            userStatsFuture.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Steam API 查询被中断", e);
        } catch (ExecutionException e) {
            // 由下面的 future 单独处理异常，这里忽略
            log.debug("Steam API 查询存在异常, steamId={}", steamId, e);
        }

        SteamApiClient.OwnedGamesResult ownedGamesResult = extractResult(ownedGamesFuture, "GetOwnedGames", steamId);
        SteamApiClient.UserStatsResult userStatsResult = extractResult(userStatsFuture, "GetUserStatsForGame", steamId);
        Throwable ownedGamesError = extractError(ownedGamesFuture);
        Throwable userStatsError = extractError(userStatsFuture);

        if (ownedGamesError != null) {
            log.warn("GetOwnedGames 调用失败, steamId={}", steamId, ownedGamesError);
        }
        if (userStatsError != null) {
            log.warn("GetUserStatsForGame 调用失败, steamId={}", steamId, userStatsError);
        }

        boolean ownedGamesFound = ownedGamesResult != null && ownedGamesResult.found();
        boolean userStatsFound = userStatsResult != null && userStatsResult.found();

        double totalPlaytimeHours = ownedGamesFound
                ? ownedGamesResult.playtimeForeverMinutes() / 60.0
                : 0.0;
        double realPlaytimeHours = userStatsFound
                ? userStatsResult.totalPlayTimeSeconds() / 3600.0
                : 0.0;

        if (!ownedGamesFound && !userStatsFound) {
            if (ownedGamesError != null && userStatsError != null) {
                throw new RuntimeException("Steam API 查询失败: " + ownedGamesError.getMessage(),
                        ownedGamesError);
            }
            throw new IllegalStateException("未找到玩家的游戏数据，可能资料未公开");
        }

        PlaytimeVO vo = new PlaytimeVO();
        vo.setSteamId(steamId);
        vo.setSteamId64(steamId64);
        vo.setTotalPlaytimeHours(totalPlaytimeHours);
        vo.setRealPlaytimeHours(realPlaytimeHours);
        vo.setSource(SOURCE);
        return vo;
    }

    /**
     * 安全提取 future 的结果，异常时返回 null。
     */
    private <T> T extractResult(CompletableFuture<T> future, String apiName, String steamId) {
        if (!future.isDone() || future.isCancelled()) {
            return null;
        }
        if (future.isCompletedExceptionally()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (Exception e) {
            log.debug("{} 提取结果异常, steamId={}", apiName, steamId, e);
            return null;
        }
    }

    /**
     * 提取 future 抛出的异常（未完成或未抛异常时返回 null）。
     */
    private Throwable extractError(CompletableFuture<?> future) {
        if (!future.isDone() || future.isCancelled() || !future.isCompletedExceptionally()) {
            return null;
        }
        try {
            future.getNow(null);
            return null;
        } catch (CompletionException ce) {
            return ce.getCause() != null ? ce.getCause() : ce;
        } catch (Exception e) {
            return e;
        }
    }
}
