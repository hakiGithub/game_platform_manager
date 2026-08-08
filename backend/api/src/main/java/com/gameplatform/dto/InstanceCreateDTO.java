package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 游戏实例创建请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "游戏实例创建请求DTO")
public class InstanceCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例名称
     */
    @Schema(description = "实例名称", required = true, example = "Minecraft服务器1")
    @NotBlank(message = "实例名称不能为空")
    private String instanceName;

    /**
     * 主机ID
     */
    @Schema(description = "主机ID", required = true, example = "1")
    @NotNull(message = "主机ID不能为空")
    private Long hostId;

    /**
     * 游戏ID
     */
    @Schema(description = "游戏ID", required = true, example = "1")
    @NotNull(message = "游戏ID不能为空")
    private Long gameId;

    /**
     * 部署类型 docker/native
     */
    @Schema(description = "部署类型", required = true, example = "docker", allowableValues = {"docker", "native"})
    @NotBlank(message = "部署类型不能为空")
    private String deployType;

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
