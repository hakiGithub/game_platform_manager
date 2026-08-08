package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 游戏元数据创建请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "游戏元数据创建请求DTO")
public class GameCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 游戏名称
     */
    @Schema(description = "游戏名称", required = true, example = "Minecraft")
    @NotBlank(message = "游戏名称不能为空")
    private String gameName;

    /**
     * 游戏代码
     */
    @Schema(description = "游戏代码", required = true, example = "minecraft")
    @NotBlank(message = "游戏代码不能为空")
    private String gameCode;

    /**
     * 游戏描述
     */
    @Schema(description = "游戏描述")
    private String description;

    /**
     * 支持的部署类型
     */
    @Schema(description = "支持的部署类型", example = "[\"docker\", \"native\"]")
    private List<String> supportedDeployTypes;

    /**
     * 默认端口
     */
    @Schema(description = "默认端口", example = "25565")
    private Integer defaultPort;

    /**
     * 环境依赖
     */
    @Schema(description = "环境依赖")
    private Map<String, Object> environmentDeps;

    /**
     * 部署配置模板
     */
    @Schema(description = "部署配置模板")
    private Map<String, Object> deployConfig;

    /**
     * 自定义操作
     */
    @Schema(description = "自定义操作")
    private Map<String, Object> customOperations;

    /**
     * 图标URL
     */
    @Schema(description = "图标URL")
    private String iconUrl;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
