package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家搜索结果 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家搜索结果")
public class PlayerStatsPlayerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SteamID */
    @Schema(description = "SteamID")
    private String steamId;

    /** 玩家名 */
    @Schema(description = "玩家名")
    private String name;

    /** 归属地 */
    @Schema(description = "归属地")
    private String location;

    /** IP */
    @Schema(description = "IP")
    private String ip;

    /** 最后在线时间（Unix 秒） */
    @Schema(description = "最后在线时间（Unix 秒）")
    private Long lastSeen;

    /** 预估在线分钟数（samples × 采集间隔分钟） */
    @Schema(description = "预估在线分钟数")
    private Long estimatedMinutes;

    /** 排名 */
    @Schema(description = "排名")
    private Integer rank;
}
