package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * Workshop 下载请求 DTO（Task 4.2 用，本 Task 先创建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "Workshop 下载请求")
public class WorkshopDownloadDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标实例 ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** Workshop URL 或纯数字 ID */
    @NotBlank(message = "Workshop URL或ID不能为空")
    @Schema(description = "Workshop URL或ID", required = true)
    private String workshopUrlOrId;
}
