package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内置插件安装任务状态（任务中心 TaskVO 的瘦身版，供前端轮询）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "内置插件安装任务状态")
public class BuiltinInstallStatusVO {

    @Schema(description = "任务中心任务 ID")
    private String taskId;

    @Schema(description = "任务状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED")
    private String status;

    @Schema(description = "进度百分比 0-100")
    private Integer progress;

    @Schema(description = "进度描述")
    private String progressMessage;

    @Schema(description = "失败时的错误信息")
    private String errorMessage;

    @Schema(description = "结果摘要（终态时一句话描述）")
    private String resultSummary;

    @Schema(description = "任务结果数据（终态返回；批装含 total/processed/success/failed/results 明细）")
    private Object result;
}
