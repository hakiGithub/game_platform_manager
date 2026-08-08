package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 日志文件信息响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "日志文件信息")
public class LogFileInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件名 */
    @Schema(description = "文件名")
    private String name;

    /** 完整路径 */
    @Schema(description = "完整路径")
    private String path;

    /** 文件大小（字节） */
    @Schema(description = "文件大小（字节）")
    private long size;

    /** 最后修改时间（毫秒时间戳） */
    @Schema(description = "最后修改时间（毫秒时间戳）")
    private long lastModified;

    /** 是否为错误日志 */
    @Schema(description = "是否为错误日志")
    private boolean isErrorLog;
}
