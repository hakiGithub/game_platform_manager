package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 插件安装结果 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "插件安装结果")
public class InstallResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 插件 ID（内置插件批量安装时用于追踪）
     */
    @Schema(description = "插件ID")
    private String pluginId;

    /**
     * 插件名称
     */
    @Schema(description = "插件名称")
    private String pluginName;

    /**
     * 安装状态：SUCCESS / FAILED
     */
    @Schema(description = "安装状态", allowableValues = {"SUCCESS", "FAILED"})
    private String status;

    /**
     * 提示信息
     */
    @Schema(description = "提示信息")
    private String message;

    /**
     * 已安装的文件相对路径列表
     */
    @Schema(description = "已安装的文件相对路径列表")
    private List<String> installedFiles;
}
