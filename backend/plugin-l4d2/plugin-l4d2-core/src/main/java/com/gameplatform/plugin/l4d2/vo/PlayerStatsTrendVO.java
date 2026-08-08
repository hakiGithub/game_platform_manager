package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家统计趋势响应 VO（小时/天聚合）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家统计趋势响应")
public class PlayerStatsTrendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 桶起始时间戳（Unix 秒） */
    @Schema(description = "桶起始时间戳（Unix 秒）")
    private Long timestamp;

    /** 平均玩家数 */
    @Schema(description = "平均玩家数")
    private Double avgPlayers;

    /** 峰值玩家数 */
    @Schema(description = "峰值玩家数")
    private Integer peakPlayers;

    /** 独立玩家数 */
    @Schema(description = "独立玩家数")
    private Long uniquePlayers;

    /** 离线采样数 */
    @Schema(description = "离线采样数")
    private Long offlineSamples;

    /** 总采样数 */
    @Schema(description = "总采样数")
    private Long sampleCount;
}
