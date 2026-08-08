package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 监控历史查询请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "监控历史查询请求")
public class MonitorHistoryQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    /**
     * 时间范围（分钟）
     * 如果未指定开始/结束时间，则使用此参数计算范围
     */
    @Schema(description = "时间范围（分钟）", example = "60", defaultValue = "60")
    private Integer timeRangeMinutes = 60;
}
