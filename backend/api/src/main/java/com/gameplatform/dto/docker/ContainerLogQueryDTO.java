package com.gameplatform.dto.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 容器日志查询DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器日志查询DTO")
public class ContainerLogQueryDTO {

    @Schema(description = "日志行数，默认100，最大2000")
    private Integer lines = 100;

    @Schema(description = "开始时间")
    private String since;

    @Schema(description = "结束时间")
    private String until;

    @Schema(description = "是否显示时间戳，默认false")
    private Boolean timestamps = false;

    @Schema(description = "关键词过滤")
    private String keyword;
}
