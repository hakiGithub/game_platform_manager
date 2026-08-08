package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * L4D2 插件商店下载任务 VO。
 *
 * <p>用于跟踪从 GitHub 仓库下载 plugin.zip 并安装到实例的异步任务状态。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件商店下载任务状态")
public class PluginStoreDownloadTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "实例ID")
    private Long instanceId;

    @Schema(description = "插件ID")
    private String pluginId;

    /** 状态：PENDING / DOWNLOADING / INSTALLING / COMPLETED / FAILED / CANCELLED */
    @Schema(description = "任务状态",
            allowableValues = {"PENDING", "DOWNLOADING", "INSTALLING", "COMPLETED", "FAILED", "CANCELLED"})
    private String status;

    /** 0-100 */
    @Schema(description = "进度 0-100")
    private int progress;

    @Schema(description = "总字节数")
    private long totalBytes;

    @Schema(description = "已下载字节数")
    private long downloadedBytes;

    @Schema(description = "文件名")
    private String filename;

    @Schema(description = "错误信息")
    private String error;

    @Schema(description = "状态消息")
    private String message;

    @Schema(description = "总文件数（多文件场景）")
    private int total;

    @Schema(description = "已下载文件数")
    private int downloaded;

    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;
}
