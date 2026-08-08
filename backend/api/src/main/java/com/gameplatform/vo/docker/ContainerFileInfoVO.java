package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器文件信息视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器文件信息视图对象")
public class ContainerFileInfoVO {

    @Schema(description = "文件/目录名称")
    private String name;

    @Schema(description = "完整路径")
    private String path;

    @Schema(description = "是否为目录")
    private Boolean isDirectory;

    @Schema(description = "文件大小(字节)")
    private Long size;

    @Schema(description = "修改时间")
    private LocalDateTime modifiedTime;

    @Schema(description = "权限字符串")
    private String permissions;

    @Schema(description = "所有者")
    private String owner;

    @Schema(description = "所属组")
    private String group;
}
