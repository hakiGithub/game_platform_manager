package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 服务器配置响应 VO
 *
 * <p>字段对齐源项目 server.cfg 解析结果：托管字段 + 自定义配置（marker 之后内容）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "服务器配置响应")
public class ServerConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long instanceId;

    /**
     * 服务器名称
     */
    @Schema(description = "服务器名称")
    private String hostname;

    /**
     * RCON 密码
     */
    @Schema(description = "RCON 密码")
    private String rconPassword;

    /**
     * 服务器密码
     */
    @Schema(description = "服务器密码")
    private String svPassword;

    /**
     * 最大玩家数
     */
    @Schema(description = "最大玩家数")
    private Integer maxPlayers;

    /**
     * 可见最大玩家数
     */
    @Schema(description = "可见最大玩家数")
    private Integer visibleMaxPlayers;

    /**
     * 当前地图
     */
    @Schema(description = "当前地图")
    private String mapName;

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
     * 其他配置项（key → value 字符串形式）
     */
    @Schema(description = "其他配置项")
    private Map<String, String> extraConfig;

    /**
     * 自定义配置（marker 之后的原始文本）
     */
    @Schema(description = "自定义配置（marker 之后的原始文本）")
    private String customConfig;
}
