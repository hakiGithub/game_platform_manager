package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器列表视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器列表视图对象")
public class ContainerListVO {

    @Schema(description = "容器ID（短ID）")
    private String containerId;

    @Schema(description = "容器名称")
    private String containerName;

    @Schema(description = "镜像名称")
    private String imageName;

    @Schema(description = "镜像标签")
    private String imageTag;

    @Schema(description = "状态：running/stopped/paused/restarting")
    private String status;

    @Schema(description = "详细状态信息")
    private String state;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "端口映射列表")
    private List<PortMapping> ports;

    @Schema(description = "CPU使用率(%)")
    private Double cpuUsage;

    @Schema(description = "内存使用率(%)")
    private Double memoryUsage;

    @Schema(description = "已用内存(MB)")
    private Long memoryUsed;

    @Schema(description = "内存限制(MB)")
    private Long memoryLimit;

    @Schema(description = "是否已关联到实例")
    private Boolean isLinked;

    @Schema(description = "关联的实例ID")
    private Long linkedInstanceId;

    @Schema(description = "关联的实例名称")
    private String linkedInstanceName;

    /**
     * 端口映射
     */
    @Data
    @Schema(description = "端口映射")
    public static class PortMapping {
        @Schema(description = "容器端口")
        private Integer containerPort;

        @Schema(description = "主机端口")
        private Integer hostPort;

        @Schema(description = "协议：tcp/udp")
        private String protocol;
    }
}
