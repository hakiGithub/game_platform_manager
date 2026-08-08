package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 地图批量裁剪请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "地图批量裁剪请求")
public class MapTrimBatchDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 待裁剪的地图名（VPK 文件名）列表
     */
    @NotEmpty(message = "地图名列表不能为空")
    @Schema(description = "待裁剪的 VPK 文件名列表", required = true)
    private List<String> mapNames;
}
