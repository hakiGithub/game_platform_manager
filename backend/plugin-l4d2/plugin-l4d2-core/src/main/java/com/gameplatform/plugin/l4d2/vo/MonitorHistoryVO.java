package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 监控历史数据响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "监控历史数据响应")
public class MonitorHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID
     */
    @Schema(description = "记录ID")
    private Long id;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long instanceId;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    private Long timestamp;

    /**
     * CPU总使用率（%）
     */
    @Schema(description = "CPU总使用率（%）")
    private Double cpuPercent;

    /**
     * CPU最高核心使用率（%）
     */
    @Schema(description = "CPU最高核心使用率（%）")
    private Double cpuMaxCore;

    /**
     * 已用内存（GB）
     */
    @Schema(description = "已用内存（GB）")
    private Double memUsed;

    /**
     * 总内存（GB）
     */
    @Schema(description = "总内存（GB）")
    private Double memTotal;

    /**
     * 已用交换内存（GB）
     */
    @Schema(description = "已用交换内存（GB）")
    private Double swapUsed;

    /**
     * 网络上传速度（KB/s）
     */
    @Schema(description = "网络上传速度（KB/s）")
    private Double netUpSpeed;

    /**
     * 网络下载速度（KB/s）
     */
    @Schema(description = "网络下载速度（KB/s）")
    private Double netDownSpeed;

    /**
     * 已用磁盘空间（GB）
     */
    @Schema(description = "已用磁盘空间（GB）")
    private Double diskUsed;

    /**
     * 总磁盘空间（GB）
     */
    @Schema(description = "总磁盘空间（GB）")
    private Double diskTotal;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
