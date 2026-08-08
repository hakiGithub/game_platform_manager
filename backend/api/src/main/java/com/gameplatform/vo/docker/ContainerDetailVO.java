package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 容器详情视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器详情视图对象")
public class ContainerDetailVO {

    @Schema(description = "容器完整ID")
    private String containerId;

    @Schema(description = "容器短ID")
    private String containerIdShort;

    @Schema(description = "容器名称")
    private String containerName;

    @Schema(description = "镜像名称")
    private String imageName;

    @Schema(description = "镜像ID")
    private String imageId;

    @Schema(description = "状态：running/stopped/paused/restarting")
    private String status;

    @Schema(description = "详细状态信息")
    private String state;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "启动时间")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "端口映射列表")
    private List<ContainerListVO.PortMapping> ports;

    @Schema(description = "网络配置列表")
    private List<NetworkInfo> networks;

    @Schema(description = "挂载卷列表")
    private List<VolumeMount> volumes;

    @Schema(description = "环境变量列表")
    private List<String> env;

    @Schema(description = "启动命令")
    private String command;

    @Schema(description = "标签信息")
    private Map<String, String> labels;

    @Schema(description = "是否已关联")
    private Boolean isLinked;

    @Schema(description = "关联信息")
    private LinkInfo linkInfo;

    /**
     * 网络信息
     */
    @Data
    @Schema(description = "网络信息")
    public static class NetworkInfo {
        @Schema(description = "网络名称")
        private String networkName;

        @Schema(description = "IP地址")
        private String ipAddress;

        @Schema(description = "网关")
        private String gateway;
    }

    /**
     * 卷挂载信息
     */
    @Data
    @Schema(description = "卷挂载信息")
    public static class VolumeMount {
        @Schema(description = "源路径")
        private String source;

        @Schema(description = "目标路径")
        private String destination;

        @Schema(description = "挂载模式")
        private String mode;
    }

    /**
     * 关联信息
     */
    @Data
    @Schema(description = "关联信息")
    public static class LinkInfo {
        @Schema(description = "关联记录ID")
        private Long id;

        @Schema(description = "实例ID")
        private Long instanceId;

        @Schema(description = "实例名称")
        private String instanceName;

        @Schema(description = "关联类型：instance/host")
        private String linkType;

        @Schema(description = "是否自动关联")
        private Boolean autoLinked;
    }
}
