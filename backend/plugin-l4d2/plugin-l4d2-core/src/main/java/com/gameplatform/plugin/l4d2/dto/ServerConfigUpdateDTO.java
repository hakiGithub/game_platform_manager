package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 服务器配置更新请求 DTO
 *
 * <p>字段对齐 {@link com.gameplatform.plugin.l4d2.vo.ServerConfigVO}，
 * 除 extraConfig/customConfig 外都可为 null，null 字段在写入时跳过。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "服务器配置更新请求")
public class ServerConfigUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 服务器名称
     */
    @Schema(description = "服务器名称", example = "My L4D2 Server")
    private String hostname;

    /**
     * RCON 密码
     */
    @Schema(description = "RCON 密码", example = "rcon_password")
    private String rconPassword;

    /**
     * 服务器密码
     */
    @Schema(description = "服务器密码", example = "server_password")
    private String svPassword;

    /**
     * 最大玩家数
     */
    @Schema(description = "最大玩家数", example = "8")
    private Integer maxPlayers;

    /**
     * 可见最大玩家数
     */
    @Schema(description = "可见最大玩家数", example = "8")
    private Integer visibleMaxPlayers;

    /**
     * 地图名称
     */
    @Schema(description = "地图名称", example = "c1m1_hotel")
    private String mapName;

    /**
     * 游戏模式
     */
    @Schema(description = "游戏模式", example = "coop")
    private String gameMode;

    /**
     * 难度
     */
    @Schema(description = "难度", example = "Normal")
    private String difficulty;

    /**
     * 其他配置项（key → value 字符串形式）
     */
    @Schema(description = "其他配置项")
    private Map<String, String> extraConfig;

    /**
     * 自定义配置（写入 marker 之后的原始文本）
     */
    @Schema(description = "自定义配置（写入 marker 之后的原始文本）")
    private String customConfig;
}
