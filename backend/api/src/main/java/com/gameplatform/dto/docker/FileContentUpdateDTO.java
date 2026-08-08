package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件内容更新DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "文件内容更新DTO")
public class FileContentUpdateDTO {

    @Schema(description = "文件路径", required = true)
    private String path;

    @Schema(description = "文件内容", required = true)
    private String content;

    @Schema(description = "文件编码，默认UTF-8")
    private String encoding = "UTF-8";

    @Schema(description = "是否备份原文件，默认true")
    private Boolean backup = true;
}
