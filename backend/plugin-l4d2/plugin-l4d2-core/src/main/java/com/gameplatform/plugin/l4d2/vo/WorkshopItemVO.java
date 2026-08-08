package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * Workshop 可下载项视图对象（对齐源项目 workshop.go WorkshopDownloadItem）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "Workshop 可下载项")
public class WorkshopItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Workshop 文件 ID（publishedfileid） */
    @Schema(description = "Workshop 文件 ID")
    private String publishedFileId;

    /** 标题 */
    @Schema(description = "标题")
    private String title;

    /** 文件名（Steam 返回的 filename 字段） */
    @Schema(description = "文件名")
    private String filename;

    /** 文件大小（Steam 返回字符串形式） */
    @Schema(description = "文件大小")
    private String fileSize;

    /** 文件下载 URL */
    @Schema(description = "文件下载 URL")
    private String fileUrl;

    /** 预览图 URL */
    @Schema(description = "预览图 URL")
    private String previewUrl;

    /** fileUrl 是否非空（前端用于判断是否可下载） */
    @Schema(description = "是否可下载")
    private boolean hasFileUrl;
}
