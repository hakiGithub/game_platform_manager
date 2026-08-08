package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 临时配置请求（RCON sm_cvar，不写文件）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "临时配置请求")
public class PluginTempConfigDTO {

    @NotNull(message = "instanceId 不能为空")
    @Schema(description = "实例ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long instanceId;

    @NotBlank(message = "cvarName 不能为空")
    @Schema(description = "CVAR 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cvarName;

    @NotBlank(message = "cvarValue 不能为空")
    @Schema(description = "CVAR 值", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cvarValue;
}
