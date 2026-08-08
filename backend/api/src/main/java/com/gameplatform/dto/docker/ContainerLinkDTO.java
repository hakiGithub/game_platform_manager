package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 容器关联请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器关联请求DTO")
public class ContainerLinkDTO {

    @NotNull(message = "主机ID不能为空")
    @Schema(description = "主机ID", required = true)
    private Long hostId;

    @NotBlank(message = "容器ID不能为空")
    @Schema(description = "容器ID", required = true)
    private String containerId;

    @NotBlank(message = "容器名称不能为空")
    @Schema(description = "容器名称", required = true)
    private String containerName;

    @Schema(description = "关联的实例ID（linkType为instance时必填）")
    private Long instanceId;

    @NotBlank(message = "关联类型不能为空")
    @Schema(description = "关联类型：instance/host", required = true)
    private String linkType;

    @Schema(description = "镜像名称")
    private String imageName;

    @Schema(description = "镜像标签")
    private String imageTag;

    @Schema(description = "备注")
    private String remark;
}
