package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 分片上传初始化请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "分片上传初始化请求")
public class ChunkUploadInitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 原始文件名 */
    @NotBlank(message = "文件名不能为空")
    @Schema(description = "原始文件名", required = true)
    private String filename;

    /** 文件总大小（字节） */
    @Positive(message = "文件大小必须为正数")
    @Schema(description = "文件总大小（字节）", required = true)
    private long totalSize;

    /** 总分片数 */
    @Positive(message = "分片数必须为正数")
    @Schema(description = "总分片数", required = true)
    private int totalChunks;

    /** 目标路径（相对 installPath，可选；为空则默认 addons/{filename}） */
    @Schema(description = "目标路径（相对 installPath，可选）")
    private String targetPath;
}
