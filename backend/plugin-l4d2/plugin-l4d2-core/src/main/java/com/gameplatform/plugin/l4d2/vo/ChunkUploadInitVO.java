package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 分片上传初始化响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "分片上传初始化响应")
public class ChunkUploadInitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上传ID（UUIDv4） */
    @Schema(description = "上传ID")
    private String uploadId;

    /** 服务端期望的分片大小（字节） */
    @Schema(description = "分片大小（字节）")
    private long chunkSize;
}
