package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * URL 下载请求 DTO（支持多 URL 切分）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "URL 下载请求")
public class UrlDownloadDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标实例 ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 下载 URL（支持空白/换行分隔的多个 URL） */
    @NotBlank(message = "下载链接不能为空")
    @Schema(description = "下载URL（支持多URL换行分隔）", required = true)
    private String url;

    /** 文件名（可选，为空时由响应头或 URL 推断） */
    @Schema(description = "文件名（可选）")
    private String filename;

    /** Referer 头（可选） */
    @Schema(description = "Referer 头（可选）")
    private String referer;

    /** 目标路径（可选，VPK 默认 addons/） */
    @Schema(description = "目标路径（可选，VPK 默认 addons/）")
    private String targetPath;
}
