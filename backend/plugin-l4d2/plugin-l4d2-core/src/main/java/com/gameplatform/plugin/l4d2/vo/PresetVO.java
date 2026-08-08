package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 预设信息响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "预设信息响应")
public class PresetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 预设名称
     */
    @Schema(description = "预设名称")
    private String name;

    /**
     * 预设显示名称
     */
    @Schema(description = "预设显示名称")
    private String displayName;

    /**
     * 预设描述
     */
    @Schema(description = "预设描述")
    private String description;

    /**
     * 游戏模式
     */
    @Schema(description = "游戏模式")
    private String gameMode;

    /**
     * 难度
     */
    @Schema(description = "难度")
    private String difficulty;

    /**
     * 最大玩家数
     */
    @Schema(description = "最大玩家数")
    private Integer maxPlayers;

    /**
     * 启用的插件列表
     */
    @Schema(description = "启用的插件列表")
    private List<String> enabledPlugins;

    /**
     * 禁用的插件列表
     */
    @Schema(description = "禁用的插件列表")
    private List<String> disabledPlugins;
}
