package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件拷贝DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "文件拷贝DTO")
public class FileCopyDTO {

    @Schema(description = "拷贝方向：toContainer-主机到容器，fromContainer-容器到主机", required = true)
    private String direction;

    @Schema(description = "源路径", required = true)
    private String sourcePath;

    @Schema(description = "目标路径", required = true)
    private String destinationPath;

    @Schema(description = "是否覆盖已存在的文件，默认false")
    private Boolean overwrite = false;
}
