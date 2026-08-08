package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家别名记录 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家别名记录")
public class PlayerStatsAliasVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 玩家名 */
    @Schema(description = "玩家名")
    private String name;

    /** 采样数 */
    @Schema(description = "采样数")
    private Integer samples;

    /** 预估在线分钟数 */
    @Schema(description = "预估在线分钟数")
    private Long estimatedMinutes;

    /** 首次出现时间（Unix 秒） */
    @Schema(description = "首次出现时间（Unix 秒）")
    private Long firstSeen;

    /** 最后出现时间（Unix 秒） */
    @Schema(description = "最后出现时间（Unix 秒）")
    private Long lastSeen;
}
