package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 游戏实例更新请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "游戏实例更新请求DTO")
public class InstanceUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID", required = true, example = "1")
    @NotNull(message = "实例ID不能为空")
    private Long id;

    /**
     * 实例名称
     */
    @Schema(description = "实例名称", example = "Minecraft服务器1")
    private String instanceName;

    /**
     * 端口配置
     */
    @Schema(description = "端口配置")
    private Map<String, Object> portConfig;

    /**
     * 配置信息
     */
    @Schema(description = "配置信息")
    private Map<String, Object> configInfo;

    /**
     * 安装路径
     */
    @Schema(description = "安装路径", example = "/opt/games/minecraft")
    private String installPath;

    /**
     * 启动命令
     */
    @Schema(description = "启动命令")
    private String startCommand;

    /**
     * 停止命令
     */
    @Schema(description = "停止命令")
    private String stopCommand;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
