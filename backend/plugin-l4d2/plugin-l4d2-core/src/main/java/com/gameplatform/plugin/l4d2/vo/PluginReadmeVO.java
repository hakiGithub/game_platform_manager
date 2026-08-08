package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 插件 README 响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件 README 内容")
public class PluginReadmeVO {

    @Schema(description = "插件名")
    private String pluginName;

    @Schema(description = "README 内容（Markdown 原文）")
    private String content;

    @Schema(description = "是否存在 README 文件")
    private boolean exists;
}
