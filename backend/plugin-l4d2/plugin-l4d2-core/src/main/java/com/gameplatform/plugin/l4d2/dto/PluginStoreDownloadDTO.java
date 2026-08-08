package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 从插件商店下载安装请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店下载请求")
public class PluginStoreDownloadDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标实例 ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 插件 ID（仓库子目录名） */
    @NotBlank(message = "插件ID不能为空")
    @Schema(description = "插件ID", required = true)
    private String pluginId;

    /** 可选目标路径，默认由 PluginInstallService 决定（addons/sourcemod/plugins/） */
    @Schema(description = "可选目标路径，默认 addons/sourcemod/plugins/")
    private String targetPath;
}
