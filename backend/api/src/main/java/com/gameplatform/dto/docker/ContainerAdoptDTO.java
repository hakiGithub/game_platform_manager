package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Docker 容器认领为游戏实例请求 DTO（ADR-0023）
 *
 * <p>deployType 可省略：后端按游戏元数据 supportedDeployTypes 自动探测
 * （优先级 docker > docker-compose > linuxgsm-docker）。
 * compose 字段（workDir/projectName/serviceName）仅在探测结果为 docker-compose 时生效，
 * 缺省时从容器的 com.docker.compose.* labels 预填。
 */
@Data
@Schema(description = "容器认领为游戏实例请求")
public class ContainerAdoptDTO {

    @NotBlank(message = "实例名称不能为空")
    @Schema(description = "实例名称（同主机唯一）")
    private String instanceName;

    @NotNull(message = "游戏不能为空")
    @Schema(description = "游戏元数据ID")
    private Long gameId;

    @Schema(description = "部署方式（可选，缺省按游戏元数据自动探测）")
    private String deployType;

    @Schema(description = "compose 工作目录（docker-compose 类型时生效，缺省读容器 labels）")
    private String workDir;

    @Schema(description = "compose 项目名（docker-compose 类型时生效，缺省读容器 labels）")
    private String projectName;

    @Schema(description = "compose 服务名（docker-compose 类型时生效，缺省读容器 labels）")
    private String serviceName;

    @Schema(description = "备注")
    private String remark;
}
