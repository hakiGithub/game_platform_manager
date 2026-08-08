package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 插件商店列表项 VO。
 *
 * <p>每个插件对应 GitHub 仓库 {@code plugins/{pluginName}/} 子目录（多文件结构，
 * 对齐 l4d2-server-next 仓库约定）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店列表项")
public class PluginStoreItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件目录名作为 ID */
    @Schema(description = "插件ID（目录名）")
    private String pluginId;

    /** 插件名称 */
    @Schema(description = "插件名称")
    private String name;

    /** 插件描述 */
    @Schema(description = "插件描述")
    private String description;

    /** 分类 */
    @Schema(description = "分类")
    private String category;

    /** 字节数（插件目录下所有文件总大小） */
    @Schema(description = "插件总字节大小")
    private Long size;

    /** 更新时间（ISO 字符串，可能为空） */
    @Schema(description = "更新时间")
    private String updatedAt;

    /** 插件包含的文件数 */
    @Schema(description = "文件数")
    private Integer fileCount;
}
