package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * L4D2 插件商店详情 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店详情")
public class PluginStoreDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "插件ID（目录名）")
    private String pluginId;

    @Schema(description = "插件名称")
    private String name;

    @Schema(description = "插件描述")
    private String description;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "plugin.zip 字节大小")
    private Long size;

    @Schema(description = "更新时间")
    private String updatedAt;

    /** README Markdown 原文 */
    @Schema(description = "README Markdown 原文")
    private String readme;

    /** 仓库内该插件目录下的所有文件 */
    @Schema(description = "文件列表")
    private List<FileEntry> fileList;

    @Data
    @Schema(description = "文件条目")
    public static class FileEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "相对路径")
        private String path;

        @Schema(description = "字节大小")
        private Long size;
    }
}
