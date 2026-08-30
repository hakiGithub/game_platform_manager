package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地图上传提交结果（ADR-0018：上传已进入执行队列）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "地图上传提交结果")
public class MapUploadSubmitVO {

    @Schema(description = "执行队列任务 ID")
    private String taskId;

    @Schema(description = "地图文件名")
    private String filename;

    @Schema(description = "文件大小（字节）")
    private long size;
}
