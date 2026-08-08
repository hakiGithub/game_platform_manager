package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PlaytimeQueryDTO;
import com.gameplatform.plugin.l4d2.service.PlaytimeService;
import com.gameplatform.plugin.l4d2.vo.PlaytimeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Steam 游玩时长查询控制器（Phase 5.2）。
 *
 * <p>对齐源项目 {@code playtime.go:20-37 GetUserPlaytime}：接收 SteamID（STEAM_X:Y:Z），
 * 并发查询 GetOwnedGames + GetUserStatsForGame，返回总时长与实战时长。
 *
 * <p>路径前缀：{@code /api/plugin/l4d2/playtime}
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 游玩时长查询", description = "Steam Web API 查询玩家 L4D2 游玩时长")
@RestController
@RequestMapping("/api/plugin/l4d2/playtime")
@RequiredArgsConstructor
@Validated
public class PlaytimeController {

    private final PlaytimeService playtimeService;

    /**
     * 查询玩家游玩时长（POST，body 携带 steamId）。
     */
    @Operation(summary = "查询玩家游玩时长", description = "通过 SteamID 查询玩家 L4D2 总时长与实战时长")
    @PostMapping("/query")
    public Result<PlaytimeVO> query(@Valid @RequestBody PlaytimeQueryDTO dto) {
        log.info("查询游玩时长(POST): steamId={}", dto.getSteamId());
        return Result.success(playtimeService.getPlaytime(dto.getSteamId()));
    }

    /**
     * 查询玩家游玩时长（GET 便捷版本，内部转发到 POST 逻辑）。
     */
    @Operation(summary = "查询玩家游玩时长(GET)", description = "GET 便捷版本，参数通过 query 传递")
    @GetMapping("/query")
    public Result<PlaytimeVO> queryGet(
            @Parameter(description = "玩家 SteamID（STEAM_X:Y:Z 格式）")
            @RequestParam @NotBlank(message = "SteamID 不能为空") String steamId) {
        log.info("查询游玩时长(GET): steamId={}", steamId);
        return Result.success(playtimeService.getPlaytime(steamId));
    }
}
