package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用链接解析可下载项视图对象（对齐源项目 link_parser.go LinkParseItem）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "通用链接解析可下载项")
public class LinkParseItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项 ID（Workshop 情况下为 publishedFileId） */
    @Schema(description = "项 ID")
    private String id;

    /** 标题 */
    @Schema(description = "标题")
    private String title;

    /** 文件名 */
    @Schema(description = "文件名")
    private String filename;

    /** 文件大小 */
    @Schema(description = "文件大小")
    private String fileSize;

    /** 文件下载 URL */
    @Schema(description = "文件下载 URL")
    private String fileUrl;

    /** 预览图 URL */
    @Schema(description = "预览图 URL")
    private String previewUrl;

    /** Referer 头（用于下载时设置） */
    @Schema(description = "Referer")
    private String referer;

    /** 是否支持下载 */
    @Schema(description = "是否支持下载")
    private boolean supported;

    /** 不支持原因（supported=false 时填写） */
    @Schema(description = "不支持原因")
    private String disabledReason;
}
