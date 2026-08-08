package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * Steam 游玩时长查询请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "Steam 游玩时长查询请求")
public class PlaytimeQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 玩家 SteamID（STEAM_X:Y:Z 格式） */
    @NotBlank(message = "SteamID 不能为空")
    @Schema(description = "玩家 SteamID（STEAM_X:Y:Z 格式）", required = true)
    private String steamId;
}
