package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.extension.PlayerStatSnapshotResource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家统计配置响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家统计配置响应")
public class PlayerStatsConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否启用采集 */
    @Schema(description = "是否启用采集")
    private Boolean enabled;

    /** 采集间隔（分钟） */
    @Schema(description = "采集间隔（分钟）")
    private Integer intervalMinutes;

    /** 数据保留天数 */
    @Schema(description = "数据保留天数")
    private Integer retentionDays;

    /** 最近一次采集快照（无数据时为 null） */
    @Schema(description = "最近一次采集快照")
    private PlayerStatSnapshotResource lastSnapshot;
}
