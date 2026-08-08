package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 系统监控指标业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class SystemMetricSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 时间戳（毫秒） */
    private Long timestamp;

    /** CPU总使用率（%） */
    private Double cpuPercent;

    /** CPU最高核心使用率（%） */
    private Double cpuMaxCore;

    /** 已用内存（GB） */
    private Double memUsed;

    /** 总内存（GB） */
    private Double memTotal;

    /** 已用交换内存（GB） */
    private Double swapUsed;

    /** 网络上传速度（KB/s） */
    private Double netUpSpeed;

    /** 网络下载速度（KB/s） */
    private Double netDownSpeed;

    /** 已用磁盘空间（GB） */
    private Double diskUsed;

    /** 总磁盘空间（GB） */
    private Double diskTotal;
}
