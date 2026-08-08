package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PlayerStatsConfigDTO;
import com.gameplatform.plugin.l4d2.service.PlayerStatsService;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsAliasVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsConfigVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsDayVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsPlayerVO;
import com.gameplatform.plugin.l4d2.vo.PlayerStatsTrendVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L4D2 玩家在线统计控制器。
 * <p>
 * 对齐源项目 {@code controller/player_stats.go}（去除审计相关逻辑）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 玩家统计", description = "L4D2 玩家在线统计接口")
@RestController
@RequestMapping("/api/plugin/l4d2/player-stats")
@RequiredArgsConstructor
@Validated
public class PlayerStatsController {

    private final PlayerStatsService playerStatsService;

    /**
     * 获取采集配置（含最近一次快照）。
     */
    @Operation(summary = "获取采集配置", description = "获取玩家统计采集配置与最近一次快照")
    @GetMapping("/config")
    public Result<PlayerStatsConfigVO> getConfig() {
        return Result.success(playerStatsService.getConfig());
    }

    /**
     * 更新采集开关。
     */
    @Operation(summary = "更新采集开关", description = "启用或停用玩家统计采集")
    @PostMapping("/config")
    public Result<Void> setConfig(@Valid @RequestBody PlayerStatsConfigDTO dto) {
        if (dto.getEnable() == null) {
            return Result.fail("enable 参数不能为空");
        }
        playerStatsService.setEnabled(dto.getEnable());
        return Result.success();
    }

    /**
     * 趋势查询（支持 hour/day 两种桶）。
     */
    @Operation(summary = "查询玩家统计趋势", description = "按小时或天聚合查询玩家统计趋势")
    @GetMapping("/hourly")
    public Result<List<PlayerStatsTrendVO>> getTrend(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "起始时间（Unix 秒）") @RequestParam(required = false) Long start,
            @Parameter(description = "结束时间（Unix 秒）") @RequestParam(required = false) Long end,
            @Parameter(description = "桶类型 hour/day，默认 hour") @RequestParam(defaultValue = "hour") String bucket) {
        long endSec = end == null ? System.currentTimeMillis() / 1000L : end;
        long startSec = start == null || start <= 0 ? endSec - 30L * 24 * 3600 : start;
        List<PlayerStatsTrendVO> trend;
        if ("day".equalsIgnoreCase(bucket)) {
            trend = playerStatsService.getDailyTrend(instanceId, startSec, endSec);
        } else {
            trend = playerStatsService.getHourlyTrend(instanceId, startSec, endSec);
        }
        return Result.success(trend);
    }

    /**
     * 玩家搜索。
     */
    @Operation(summary = "搜索玩家", description = "按 steamId 或 name 模糊匹配；空关键字返回 Top 列表")
    @GetMapping("/players/search")
    public Result<List<PlayerStatsPlayerVO>> searchPlayers(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "关键字（steamId / name）") @RequestParam(required = false) String keyword,
            @Parameter(description = "起始时间（Unix 秒）") @RequestParam(required = false) Long start) {
        long startSec = start == null || start <= 0
                ? System.currentTimeMillis() / 1000L - 30L * 24 * 3600
                : start;
        return Result.success(playerStatsService.searchPlayers(instanceId, keyword, startSec));
    }

    /**
     * 玩家按日统计。
     */
    @Operation(summary = "玩家按日统计", description = "查询指定玩家按日在线统计")
    @GetMapping("/players/{steamId}/days")
    public Result<List<PlayerStatsDayVO>> getPlayerDays(
            @Parameter(description = "SteamID") @PathVariable String steamId,
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "起始时间（Unix 秒）") @RequestParam(required = false) Long start) {
        long startSec = start == null || start <= 0
                ? System.currentTimeMillis() / 1000L - 30L * 24 * 3600
                : start;
        return Result.success(playerStatsService.getPlayerDays(instanceId, steamId, startSec));
    }

    /**
     * 玩家别名记录。
     */
    @Operation(summary = "玩家别名记录", description = "查询指定玩家使用过的名字列表")
    @GetMapping("/players/{steamId}/aliases")
    public Result<List<PlayerStatsAliasVO>> getPlayerAliases(
            @Parameter(description = "SteamID") @PathVariable String steamId,
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "起始时间（Unix 秒）") @RequestParam(required = false) Long start) {
        long startSec = start == null || start <= 0
                ? System.currentTimeMillis() / 1000L - 30L * 24 * 3600
                : start;
        return Result.success(playerStatsService.getPlayerAliases(instanceId, steamId, startSec));
    }
}
