package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家按日统计 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家按日统计")
public class PlayerStatsDayVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日期（yyyy-MM-dd） */
    @Schema(description = "日期（yyyy-MM-dd）")
    private String date;

    /** 在线分钟数（samples × 采集间隔分钟） */
    @Schema(description = "在线分钟数")
    private Integer onlineMinutes;

    /** 采样数 */
    @Schema(description = "采样数")
    private Integer samples;

    /** 当日首次出现时间（Unix 秒） */
    @Schema(description = "当日首次出现时间（Unix 秒）")
    private Long firstSeen;

    /** 当日最后出现时间（Unix 秒） */
    @Schema(description = "当日最后出现时间（Unix 秒）")
    private Long lastSeen;
}
