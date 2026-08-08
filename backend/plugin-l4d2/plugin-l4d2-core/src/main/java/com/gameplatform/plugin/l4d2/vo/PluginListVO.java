package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 插件列表响应 VO（对齐 l4d2-server-next）。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Data
@Schema(description = "插件列表响应")
public class PluginListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "插件名（plugins_store 子目录名）")
    private String name;

    @Schema(description = "状态", allowableValues = {"enabled", "disabled"})
    private String status;

    @Schema(description = "来源", allowableValues = {"panel", "store", "upload"})
    private String source;

    @Schema(description = "是否包含 .smx 文件")
    private Boolean hasSmx;

    @Schema(description = "是否包含 cfg 配置文件")
    private Boolean hasConfig;

    @Schema(description = "描述（README 第一段）")
    private String description;

    @Schema(description = "版本")
    private String version;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "插件文件列表（相对 left4dead2/）")
    private List<String> fileList;

    @Schema(description = "配置文件列表")
    private List<String> configFiles;

    @Schema(description = "启用时间")
    private LocalDateTime enableTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
