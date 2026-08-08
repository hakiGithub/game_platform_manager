package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量插件操作请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "批量插件操作请求")
public class BatchPluginOperationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID")
    private Long instanceId;

    /**
     * 插件名称列表
     */
    @NotEmpty(message = "插件名称列表不能为空")
    @Schema(description = "插件名称列表")
    private List<String> pluginNames;
}
