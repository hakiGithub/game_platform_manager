package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 容器操作请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器操作请求DTO")
public class ContainerOperationDTO {

    @Schema(description = "是否强制停止/删除")
    private Boolean force;

    @Schema(description = "超时时间(秒)")
    private Integer timeout;

    @Schema(description = "是否删除关联卷")
    private Boolean volumes;
}
