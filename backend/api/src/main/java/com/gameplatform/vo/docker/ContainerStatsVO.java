package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器资源统计视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器资源统计视图对象")
public class ContainerStatsVO {

    @Schema(description = "容器ID")
    private String containerId;

    @Schema(description = "容器名称")
    private String containerName;

    @Schema(description = "CPU统计")
    private CpuStats cpu;

    @Schema(description = "内存统计")
    private MemoryStats memory;

    @Schema(description = "网络统计")
    private NetworkStats network;

    @Schema(description = "磁盘IO统计")
    private BlockIOStats blockIO;

    @Schema(description = "统计时间")
    private LocalDateTime timestamp;

    /**
     * CPU统计
     */
    @Data
    @Schema(description = "CPU统计")
    public static class CpuStats {
        @Schema(description = "CPU使用率(%)")
        private Double usagePercent;

        @Schema(description = "系统CPU使用量")
        private Long systemUsage;

        @Schema(description = "总CPU使用量")
        private Long totalUsage;
    }

    /**
     * 内存统计
     */
    @Data
    @Schema(description = "内存统计")
    public static class MemoryStats {
        @Schema(description = "内存使用率(%)")
        private Double usagePercent;

        @Schema(description = "已用内存(MB)")
        private Long used;

        @Schema(description = "内存限制(MB)")
        private Long limit;

        @Schema(description = "缓存(MB)")
        private Long cache;
    }

    /**
     * 网络统计
     */
    @Data
    @Schema(description = "网络统计")
    public static class NetworkStats {
        @Schema(description = "接收字节数")
        private Long rxBytes;

        @Schema(description = "发送字节数")
        private Long txBytes;

        @Schema(description = "接收包数")
        private Long rxPackets;

        @Schema(description = "发送包数")
        private Long txPackets;
    }

    /**
     * 磁盘IO统计
     */
    @Data
    @Schema(description = "磁盘IO统计")
    public static class BlockIOStats {
        @Schema(description = "读取字节数")
        private Long readBytes;

        @Schema(description = "写入字节数")
        private Long writeBytes;
    }
}
