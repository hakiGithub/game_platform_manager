package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 主机资源信息响应VO
 * 对应接口文档中的资源使用情况接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "主机资源信息响应VO")
public class HostResourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * CPU信息
     */
    @Schema(description = "CPU信息")
    private CpuInfo cpu;

    /**
     * 内存信息
     */
    @Schema(description = "内存信息")
    private MemoryInfo memory;

    /**
     * 磁盘信息
     */
    @Schema(description = "磁盘信息")
    private DiskInfo disk;

    /**
     * 网络信息
     */
    @Schema(description = "网络信息")
    private NetworkInfo network;

    /**
     * CPU信息内部类
     */
    @Data
    @Schema(description = "CPU信息")
    public static class CpuInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * CPU核心数
         */
        @Schema(description = "CPU核心数")
        private Integer cores;

        /**
         * CPU使用率(%)
         */
        @Schema(description = "CPU使用率(%)")
        private Double usage;

        /**
         * CPU型号
         */
        @Schema(description = "CPU型号")
        private String model;
    }

    /**
     * 内存信息内部类
     */
    @Data
    @Schema(description = "内存信息")
    public static class MemoryInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 总内存(MB)
         */
        @Schema(description = "总内存(MB)")
        private Long total;

        /**
         * 已用内存(MB)
         */
        @Schema(description = "已用内存(MB)")
        private Long used;

        /**
         * 空闲内存(MB)
         */
        @Schema(description = "空闲内存(MB)")
        private Long free;

        /**
         * 内存使用率(%)
         */
        @Schema(description = "内存使用率(%)")
        private Double usage;
    }

    /**
     * 磁盘信息内部类
     */
    @Data
    @Schema(description = "磁盘信息")
    public static class DiskInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 总磁盘(GB)
         */
        @Schema(description = "总磁盘(GB)")
        private Long total;

        /**
         * 已用磁盘(GB)
         */
        @Schema(description = "已用磁盘(GB)")
        private Long used;

        /**
         * 空闲磁盘(GB)
         */
        @Schema(description = "空闲磁盘(GB)")
        private Long free;

        /**
         * 磁盘使用率(%)
         */
        @Schema(description = "磁盘使用率(%)")
        private Double usage;
    }

    /**
     * 网络信息内部类
     */
    @Data
    @Schema(description = "网络信息")
    public static class NetworkInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 接收字节数
         */
        @Schema(description = "接收字节数")
        private Long rxBytes;

        /**
         * 发送字节数
         */
        @Schema(description = "发送字节数")
        private Long txBytes;
    }
}
