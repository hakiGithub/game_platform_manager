package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * Steam 游玩时长查询结果 VO（对齐源项目 playtime.go:33-37）。
 *
 * <p>包含 L4D2 的总时长（GetOwnedGames.playtime_forever）与实战时长
 * （GetUserStatsForGame.Stat.TotalPlayTime.Total）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "Steam 游玩时长查询结果")
public class PlaytimeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原始 SteamID（STEAM_X:Y:Z 格式） */
    @Schema(description = "原始 SteamID（STEAM_X:Y:Z 格式）")
    private String steamId;

    /** 转换后的 SteamID64（17 位数字字符串） */
    @Schema(description = "转换后的 SteamID64")
    private String steamId64;

    /** 总时长（小时，playtime_forever / 60） */
    @Schema(description = "总时长（小时）")
    private double totalPlaytimeHours;

    /** 实战时长（小时，TotalPlayTime / 3600） */
    @Schema(description = "实战时长（小时）")
    private double realPlaytimeHours;

    /** 数据来源（固定 "Steam Web API"） */
    @Schema(description = "数据来源")
    private String source;
}
