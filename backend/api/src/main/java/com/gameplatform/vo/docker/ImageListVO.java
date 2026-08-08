package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 镜像列表视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "镜像列表视图对象")
public class ImageListVO {

    @Schema(description = "镜像ID（短ID）")
    private String imageId;

    @Schema(description = "镜像完整ID")
    private String imageIdFull;

    @Schema(description = "仓库标签列表")
    private List<String> repoTags;

    @Schema(description = "镜像大小(MB)")
    private Long size;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "被使用的容器数量")
    private Integer usedByContainers;

    @Schema(description = "是否为悬空镜像")
    private Boolean isDangling;

    @Schema(description = "标签信息")
    private Map<String, String> labels;
}
