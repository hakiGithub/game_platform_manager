package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内置插件批量安装任务提交结果（异步，返回 taskId 供轮询）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "内置插件批量安装任务提交结果")
public class BuiltinBatchInstallSubmitVO {

    @Schema(description = "任务中心任务 ID")
    private String taskId;

    @Schema(description = "待安装插件数量")
    private int total;
}
