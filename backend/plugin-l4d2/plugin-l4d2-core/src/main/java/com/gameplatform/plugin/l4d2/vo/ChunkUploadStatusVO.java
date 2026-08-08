package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * 分片上传进度响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "分片上传进度响应")
public class ChunkUploadStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上传ID */
    @Schema(description = "上传ID")
    private String uploadId;

    /** 总分片数 */
    @Schema(description = "总分片数")
    private int totalChunks;

    /** 已接收分片数 */
    @Schema(description = "已接收分片数")
    private int receivedChunks;

    /** 已接收分片索引集合 */
    @Schema(description = "已接收分片索引集合")
    private Set<Integer> receivedIndexes;

    /** 状态：UPLOADING / COMPLETED / EXPIRED / FAILED */
    @Schema(description = "状态")
    private String status;

    /** 进度百分比（0-100） */
    @Schema(description = "进度百分比（0-100）")
    private double progress;
}
