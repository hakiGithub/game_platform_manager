package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.extension.SystemMetricResource;
import com.gameplatform.plugin.l4d2.extension.SystemMetricSpec;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 系统监控指标 VO（用于历史查询接口返回）。
 *
 * <p>对齐源项目 {@code model/metric.go} SystemMetric（9 字段，GB/KB 单位），
 * timestamp 使用毫秒。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "系统监控指标")
public class SystemMetricVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 时间戳（毫秒） */
    @Schema(description = "时间戳（毫秒）")
    private Long timestamp;

    /** CPU总使用率（%） */
    @Schema(description = "CPU总使用率（%）")
    private Double cpuPercent;

    /** CPU最高核心使用率（%） */
    @Schema(description = "CPU最高核心使用率（%）")
    private Double cpuMaxCore;

    /** 已用内存（GB） */
    @Schema(description = "已用内存（GB）")
    private Double memUsed;

    /** 总内存（GB） */
    @Schema(description = "总内存（GB）")
    private Double memTotal;

    /** 已用交换内存（GB） */
    @Schema(description = "已用交换内存（GB）")
    private Double swapUsed;

    /** 网络上传速度（KB/s） */
    @Schema(description = "网络上传速度（KB/s）")
    private Double netUpSpeed;

    /** 网络下载速度（KB/s） */
    @Schema(description = "网络下载速度（KB/s）")
    private Double netDownSpeed;

    /** 已用磁盘空间（GB） */
    @Schema(description = "已用磁盘空间（GB）")
    private Double diskUsed;

    /** 总磁盘空间（GB） */
    @Schema(description = "总磁盘空间（GB）")
    private Double diskTotal;

    /**
     * 从 Resource 转换为 VO。
     */
    public static SystemMetricVO from(SystemMetricResource resource) {
        SystemMetricVO vo = new SystemMetricVO();
        if (resource == null) {
            return vo;
        }
        SystemMetricSpec spec = resource.getSpec();
        if (spec == null) {
            return vo;
        }
        vo.setTimestamp(spec.getTimestamp());
        vo.setCpuPercent(spec.getCpuPercent());
        vo.setCpuMaxCore(spec.getCpuMaxCore());
        vo.setMemUsed(spec.getMemUsed());
        vo.setMemTotal(spec.getMemTotal());
        vo.setSwapUsed(spec.getSwapUsed());
        vo.setNetUpSpeed(spec.getNetUpSpeed());
        vo.setNetDownSpeed(spec.getNetDownSpeed());
        vo.setDiskUsed(spec.getDiskUsed());
        vo.setDiskTotal(spec.getDiskTotal());
        return vo;
    }

    /**
     * 格式化已用内存，例如 "12.34 GB"。
     */
    public String getMemUsedFormatted() {
        return formatGB(memUsed);
    }

    /**
     * 格式化总内存，例如 "16.00 GB"。
     */
    public String getMemTotalFormatted() {
        return formatGB(memTotal);
    }

    /**
     * 格式化已用磁盘，例如 "120.50 GB"。
     */
    public String getDiskUsedFormatted() {
        return formatGB(diskUsed);
    }

    /**
     * 格式化总磁盘，例如 "500.00 GB"。
     */
    public String getDiskTotalFormatted() {
        return formatGB(diskTotal);
    }

    /**
     * 格式化上传速度，例如 "12.34 KB/s"。
     */
    public String getNetUpSpeedFormatted() {
        return formatKB(netUpSpeed);
    }

    /**
     * 格式化下载速度，例如 "56.78 KB/s"。
     */
    public String getNetDownSpeedFormatted() {
        return formatKB(netDownSpeed);
    }

    private static String formatGB(Double value) {
        if (value == null) {
            return "0.00 GB";
        }
        return String.format("%.2f GB", value);
    }

    private static String formatKB(Double value) {
        if (value == null) {
            return "0.00 KB/s";
        }
        return String.format("%.2f KB/s", value);
    }
}
