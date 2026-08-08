package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 游戏元数据更新请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "游戏元数据更新请求DTO")
public class GameUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 游戏ID
     */
    @Schema(description = "游戏ID", required = true, example = "1")
    @NotNull(message = "游戏ID不能为空")
    private Long id;

    /**
     * 游戏名称
     */
    @Schema(description = "游戏名称", example = "Minecraft")
    private String gameName;

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
