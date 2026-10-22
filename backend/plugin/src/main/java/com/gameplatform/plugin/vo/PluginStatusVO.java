package com.gameplatform.plugin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 插件状态响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "插件状态响应VO")
public class PluginStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 插件ID
     */
    @Schema(description = "插件ID")
    private String pluginId;

    /**
     * 游戏编码（来自插件扩展点 GameEnhancementExtension.getGameCode()，权威值）。
     * <p>
     * 插件 ID 与 gameCode 并非简单的 "plugin-" 前缀关系（如 plugin-dnf-tw → dnf_tw），
     * 宿主前端不得再从 pluginId 猜测，必须用本字段做实例查询与工作区路由。
     */
    @Schema(description = "游戏编码（插件扩展点声明的权威值）")
    private String gameCode;

    /**
     * 插件名称
     */
    @Schema(description = "插件名称")
    private String pluginName;

    /**
     * 插件版本
     */
    @Schema(description = "插件版本")
    private String version;

    /**
     * 插件状态
     */
    @Schema(description = "插件状态: CREATED, DISABLED, RESOLVED, STARTED, STOPPED")
    private String state;

    /**
     * 状态描述
     */
    @Schema(description = "状态描述")
    private String stateDesc;

    /**
     * 是否已启用
     */
    @Schema(description = "是否已启用")
    private Boolean enabled;

    /**
     * 是否正在运行
     */
    @Schema(description = "是否正在运行")
    private Boolean running;

    /**
     * 插件提供者
     */
    @Schema(description = "插件提供者")
    private String provider;

    /**
     * 插件描述
     */
    @Schema(description = "插件描述")
    private String description;

    /**
     * 插件依赖
     */
    @Schema(description = "插件依赖")
    private String dependencies;

    /**
     * 插件文件路径
     */
    @Schema(description = "插件文件路径")
    private String pluginPath;

    /**
     * 加载时间
     */
    @Schema(description = "加载时间")
    private LocalDateTime loadTime;

    /**
     * 启动时间
     */
    @Schema(description = "启动时间")
    private LocalDateTime startTime;

    /**
     * 获取状态描述
     */
    public String getStateDesc() {
        if (state == null) {
            return "未知";
        }
        return switch (state) {
            case "CREATED" -> "已创建";
            case "DISABLED" -> "已禁用";
            case "RESOLVED" -> "已解析";
            case "STARTED" -> "已启动";
            case "STOPPED" -> "已停止";
            default -> state;
        };
    }

}
