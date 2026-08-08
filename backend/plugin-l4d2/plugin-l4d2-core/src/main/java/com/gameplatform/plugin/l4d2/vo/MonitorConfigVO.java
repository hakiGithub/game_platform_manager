package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 监控配置 VO（GET /api/plugin/l4d2/monitor/config 返回体）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "监控配置")
public class MonitorConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否启用历史持久化 */
    @Schema(description = "是否启用历史持久化")
    private boolean historyEnabled;

    /** 采集间隔（毫秒） */
    @Schema(description = "采集间隔（毫秒）")
    private long collectIntervalMs;

    /** 数据保留时长（毫秒） */
    @Schema(description = "数据保留时长（毫秒）")
    private long retentionMs;

    /** 触发降采样的最大点数阈值 */
    @Schema(description = "触发降采样的最大点数阈值")
    private int maxPoints;

    /** 降采样目标点数 */
    @Schema(description = "降采样目标点数")
    private int downsampleTo;

    /** 是否启用主动采集 */
    @Schema(description = "是否启用主动采集")
    private boolean collectEnabled;
}
