package com.gameplatform.vo.docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器健康状态视图对象
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "容器健康状态视图对象")
public class ContainerHealthVO {

    @Schema(description = "容器ID")
    private String containerId;

    @Schema(description = "健康状态：healthy/unhealthy/starting/none")
    private String status;

    @Schema(description = "最后检查时间")
    private LocalDateTime lastCheck;

    @Schema(description = "连续失败次数")
    private Integer failingStreak;

    @Schema(description = "健康检查日志（最近5条）")
    private java.util.List<HealthLog> log;

    /**
     * 健康检查日志
     */
    @Data
    @Schema(description = "健康检查日志")
    public static class HealthLog {
        @Schema(description = "检查开始时间")
        private LocalDateTime start;

        @Schema(description = "检查结束时间")
        private LocalDateTime end;

        @Schema(description = "退出码")
        private Integer exitCode;

        @Schema(description = "检查输出")
        private String output;
    }
}
