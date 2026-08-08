package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 插件全量导出任务 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件全量导出任务状态")
public class PluginExportTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    @Schema(description = "任务ID")
    private String taskId;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long instanceId;

    /**
     * 任务状态：RUNNING / COMPLETED / FAILED / CANCELLED
     */
    @Schema(description = "任务状态", allowableValues = {"RUNNING", "COMPLETED", "FAILED", "CANCELLED"})
    private String status;

    /**
     * 总文件数
     */
    @Schema(description = "总文件数")
    private int totalFiles;

    /**
     * 已处理文件数
     */
    @Schema(description = "已处理文件数")
    private int processedFiles;

    /**
     * 下载URL（完成后填充）
     */
    @Schema(description = "下载URL")
    private String downloadUrl;

    /**
     * 错误信息（失败时填充）
     */
    @Schema(description = "错误信息")
    private String error;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;
}
