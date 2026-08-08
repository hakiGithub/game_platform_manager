package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 恢复默认配置请求（从 CVAR 元数据 Default 字段重建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "恢复默认配置请求")
public class PluginRestoreDefaultsDTO {

    @NotNull(message = "instanceId 不能为空")
    @Schema(description = "实例ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long instanceId;

    @NotBlank(message = "pluginName 不能为空")
    @Schema(description = "插件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pluginName;
}
