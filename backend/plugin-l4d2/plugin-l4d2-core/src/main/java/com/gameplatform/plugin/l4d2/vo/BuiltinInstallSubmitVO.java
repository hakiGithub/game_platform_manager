package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内置插件安装任务提交结果。
 *
 * <p>安装已改走任务中心异步执行，同步阶段仅返回任务 ID，进度通过
 * {@code GET /api/plugin/l4d2/plugins/builtin/install-status} 轮询。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "内置插件安装任务提交结果")
public class BuiltinInstallSubmitVO {

    @Schema(description = "任务中心任务 ID")
    private String taskId;

    @Schema(description = "内置插件 ID")
    private String pluginId;

    @Schema(description = "内置插件名称")
    private String pluginName;
}
