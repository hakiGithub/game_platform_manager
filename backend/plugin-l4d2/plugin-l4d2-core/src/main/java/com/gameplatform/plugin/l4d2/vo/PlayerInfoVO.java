package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家信息响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家信息响应")
public class PlayerInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 玩家ID
     */
    @Schema(description = "玩家ID")
    private Integer id;

    /**
     * 玩家名称
     */
    @Schema(description = "玩家名称")
    private String name;

    /**
     * SteamID
     */
    @Schema(description = "SteamID")
    private String steamId;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ip;

    /**
     * 连接状态
     */
    @Schema(description = "连接状态")
    private String status;

    /**
     * 延迟（毫秒）
     */
    @Schema(description = "延迟（毫秒）")
    private Integer delay;

    /**
     * 丢包率
     */
    @Schema(description = "丢包率")
    private Integer loss;

    /**
     * 连接时长
     */
    @Schema(description = "连接时长")
    private String duration;

    /**
     * 连接速率
     */
    @Schema(description = "连接速率")
    private Integer linkRate;
}
