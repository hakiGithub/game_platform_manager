package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 监控状态响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "监控状态响应")
public class MonitorStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long instanceId;

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
     * 内存使用率（%）
     */
    @Schema(description = "内存使用率（%）")
    private Double memPercent;

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
     * 磁盘使用率（%）
     */
    @Schema(description = "磁盘使用率（%）")
    private Double diskPercent;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    private Long timestamp;

    /**
     * 计算内存使用率
     */
    public Double getMemPercent() {
        if (memTotal != null && memTotal > 0 && memUsed != null) {
            return Math.round(memUsed / memTotal * 10000.0) / 100.0;
        }
        return 0.0;
    }

    /**
     * 计算磁盘使用率
     */
    public Double getDiskPercent() {
        if (diskTotal != null && diskTotal > 0 && diskUsed != null) {
            return Math.round(diskUsed / diskTotal * 10000.0) / 100.0;
        }
        return 0.0;
    }
}
