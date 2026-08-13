package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 下载任务业务数据。
 *
 * <p>状态码字符串（taskStatus）：
 * <ul>
 *   <li>PENDING：等待下载</li>
 *   <li>DOWNLOADING：下载中</li>
 *   <li>COMPLETED：下载完成</li>
 *   <li>FAILED：下载失败</li>
 *   <li>CANCELLED：已取消</li>
 *   <li>PENDING_MANUAL：Steam API 未返回 file_url，等待用户配置代理</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Data
public class DownloadTaskSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID（雪花 ID，作为 Resource name） */
    private String taskId;

    /** 实例ID */
    private Long instanceId;

    /** 任务类型：URL / WORKSHOP */
    private String taskType;

    /** 下载URL */
    private String taskUrl;

    /** Referer 头 */
    private String referer;

    /** 任务状态（PENDING / DOWNLOADING / COMPLETED / FAILED / CANCELLED / PENDING_MANUAL） */
    private String taskStatus;

    /** 进度（0-100） */
    private Double progress;

    /** 文件名 */
    private String filename;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 已下载大小（字节） */
    private Long downloadedSize;

    /** 下载速度（bytes/second） */
    private Double downloadSpeed;

    /** 错误信息 */
    private String errorMessage;

    /** 文件类型 */
    private String fileType;

    /** 目标路径 */
    private String targetPath;

    /** Workshop ID（仅 Workshop 任务） */
    private String workshopId;

    /** Workshop 标题（仅 Workshop 任务） */
    private String workshopTitle;

    /** 预览图 URL */
    private String previewUrl;

    /** 开始时间（ISO 格式字符串） */
    private String startTime;

    /** 完成时间（ISO 格式字符串） */
    private String completeTime;

    /** 重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 是否删除 */
    private Boolean isDeleted;

    /** 主应用补丁安装任务 ID（PatchInstallService 接入后 URL 任务执行委托任务中心，ADR-0006） */
    private String patchTaskId;

    /** 备注 */
    private String remark;
}
