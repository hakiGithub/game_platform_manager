package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * L4D2 分片上传业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ChunkUploadSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上传ID（UUIDv4） */
    private String uploadId;

    /** 实例ID */
    private Long instanceId;

    /** 原始文件名 */
    private String originalFilename;

    /** 文件总大小（字节） */
    private long totalSize;

    /** 总分片数 */
    private int totalChunks;

    /** 已接收分片数 */
    private int receivedChunks;

    /** 临时目录绝对路径 */
    private String tempDir;

    /** 完成后目标路径（相对 installPath） */
    private String targetPath;

    /** 状态：UPLOADING / COMPLETED / EXPIRED / FAILED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 已接收分片索引集合 */
    private Set<Integer> receivedIndexes;
}
