package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器关联视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器关联视图对象")
public class ContainerLinkVO {

    @Schema(description = "关联记录ID")
    private Long id;

    @Schema(description = "主机ID")
    private Long hostId;

    @Schema(description = "主机名称")
    private String hostName;

    @Schema(description = "容器ID")
    private String containerId;

    @Schema(description = "容器名称")
    private String containerName;

    @Schema(description = "实例ID")
    private Long instanceId;

    @Schema(description = "实例名称")
    private String instanceName;

    @Schema(description = "关联类型")
    private String linkType;

    @Schema(description = "镜像名称")
    private String imageName;

    @Schema(description = "镜像标签")
    private String imageTag;

    @Schema(description = "是否自动关联")
    private Boolean autoLinked;

    @Schema(description = "创建人ID")
    private Long createBy;

    @Schema(description = "创建人名称")
    private String createByName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
