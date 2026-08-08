package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 内置插件批量安装请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "内置插件批量安装请求")
public class BuiltinPluginInstallDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例 ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID")
    private Long instanceId;

    /** 内置插件 ID 列表（对应 builtin-plugins.yaml 中的 id 字段） */
    @NotEmpty(message = "插件ID列表不能为空")
    @Schema(description = "内置插件ID列表")
    private List<String> pluginIds;
}
