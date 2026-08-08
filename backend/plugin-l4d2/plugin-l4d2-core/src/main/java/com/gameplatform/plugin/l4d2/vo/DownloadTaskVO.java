package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 下载任务视图对象。
 *
 * <p>对应 {@link com.gameplatform.plugin.l4d2.extension.DownloadTaskSpec}，
 * 增加 {@code formattedSpeed} 与 {@code formattedSize} 计算字段。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "L4D2 下载任务视图")
public class DownloadTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID（雪花 ID） */
    @Schema(description = "任务ID")
    private String taskId;

    /** 实例 ID */
    @Schema(description = "实例ID")
    private Long instanceId;

    /** 任务类型：URL / WORKSHOP */
    @Schema(description = "任务类型")
    private String taskType;

    /** 下载 URL */
    @Schema(description = "下载URL")
    private String taskUrl;

    /** 文件名 */
    @Schema(description = "文件名")
    private String filename;

    /** 文件大小（字节） */
    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    /** 已下载大小（字节） */
    @Schema(description = "已下载大小（字节）")
    private Long downloadedSize;

    /** 进度（0-100） */
    @Schema(description = "进度（0-100）")
    private Double progress;

    /** 下载速度（bytes/second） */
    @Schema(description = "下载速度（bytes/second）")
    private Double downloadSpeed;

    /** 状态：PENDING / DOWNLOADING / COMPLETED / FAILED / CANCELLED / PENDING_MANUAL */
    @Schema(description = "任务状态")
    private String status;

    /** 错误信息 */
    @Schema(description = "错误信息")
    private String errorMessage;

    /** 目标路径 */
    @Schema(description = "目标路径")
    private String targetPath;

    /** Workshop ID */
    @Schema(description = "Workshop ID")
    private String workshopId;

    /** Workshop 标题 */
    @Schema(description = "Workshop 标题")
    private String workshopTitle;

    /** 预览图 URL */
    @Schema(description = "预览图 URL")
    private String previewUrl;

    /** 开始时间（ISO 格式字符串） */
    @Schema(description = "开始时间")
    private String startTime;

    /** 完成时间（ISO 格式字符串） */
    @Schema(description = "完成时间")
    private String completeTime;

    /** 格式化后的速度（如 "1.23 MB/s"），实时计算 */
    @Schema(description = "格式化速度")
    private String formattedSpeed;

    /** 格式化后的大小（如 "12.34 MB"），实时计算 */
    @Schema(description = "格式化大小")
    private String formattedSize;

    // ===== 静态工厂与格式化方法 =====

    /**
     * 格式化下载速度为可读字符串（对齐源项目 download.go:417-428）：
     * <ul>
     *   <li>speed &lt; 1024 → "%.2f B/s"</li>
     *   <li>speed &lt; 1024*1024 → "%.2f KB/s"</li>
     *   <li>speed &lt; 1024*1024*1024 → "%.2f MB/s"</li>
     *   <li>否则 → "%.2f GB/s"</li>
     * </ul>
     */
    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) {
            return String.format("%.2f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024L * 1024) {
            return String.format("%.2f KB/s", bytesPerSec / 1024);
        } else if (bytesPerSec < 1024L * 1024 * 1024) {
            return String.format("%.2f MB/s", bytesPerSec / (1024L * 1024));
        } else {
            return String.format("%.2f GB/s", bytesPerSec / (1024L * 1024 * 1024));
        }
    }

    /**
     * 格式化文件大小为可读字符串（对齐源项目 download.go:431-445）：
     * <ul>
     *   <li>size &lt;= 0 → "未知大小"</li>
     *   <li>size &lt; 1024 → "%d B"</li>
     *   <li>size &lt; 1024*1024 → "%.2f KB"</li>
     *   <li>size &lt; 1024*1024*1024 → "%.2f MB"</li>
     *   <li>否则 → "%.2f GB"</li>
     * </ul>
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return String.format("%d B", bytes);
        } else if (bytes < 1024L * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 实时计算格式化速度。
     */
    public String getFormattedSpeed() {
        Double speed = downloadSpeed;
        return speed == null ? formatSpeed(0) : formatSpeed(speed);
    }

    /**
     * 实时计算格式化大小。
     */
    public String getFormattedSize() {
        Long size = fileSize;
        return size == null ? formatSize(0) : formatSize(size);
    }
}
