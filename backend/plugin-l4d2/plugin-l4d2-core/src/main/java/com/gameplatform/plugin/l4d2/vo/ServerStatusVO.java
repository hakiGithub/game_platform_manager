package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 服务器状态响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "服务器状态响应")
public class ServerStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 服务器名称
     */
    @Schema(description = "服务器名称")
    private String hostname;

    /**
     * 服务器是否在线（RCON 可达为 true，离线/不可达为 false）
     */
    @Schema(description = "服务器是否在线")
    private Boolean online;

    /**
     * 当前地图
     */
    @Schema(description = "当前地图")
    private String map;

    /**
     * 玩家数（当前/最大）
     */
    @Schema(description = "玩家数（当前/最大）", example = "4/8")
    private String players;

    /**
     * 当前玩家数量
     */
    @Schema(description = "当前玩家数量")
    private Integer currentPlayers;

    /**
     * 最大玩家数量
     */
    @Schema(description = "最大玩家数量")
    private Integer maxPlayers;

    /**
     * 游戏难度
     */
    @Schema(description = "游戏难度")
    private String difficulty;

    /**
     * 游戏模式
     */
    @Schema(description = "游戏模式")
    private String gameMode;

    /**
     * 玩家列表
     */
    @Schema(description = "玩家列表")
    private List<PlayerInfoVO> users;

    /**
     * 服务器版本
     */
    @Schema(description = "服务器版本")
    private String version;

    /**
     * 协议版本
     */
    @Schema(description = "协议版本")
    private String protocolVersion;

    /**
     * 操作系统类型
     */
    @Schema(description = "操作系统类型")
    private String osType;

    /**
     * 服务器类型
     */
    @Schema(description = "服务器类型")
    private String serverType;

    /**
     * 离线原因（online=false 时填充）
     */
    @Schema(description = "离线原因")
    private String reason;
}
