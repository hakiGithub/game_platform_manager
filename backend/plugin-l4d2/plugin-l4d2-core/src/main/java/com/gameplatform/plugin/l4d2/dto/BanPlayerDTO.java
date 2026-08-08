package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 封禁玩家请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "封禁玩家请求")
public class BanPlayerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 玩家 SteamID 或 ID
     */
    @NotBlank(message = "SteamID 或 ID 不能为空")
    @Schema(description = "SteamID 或 ID", required = true, example = "STEAM_0:0:123456")
    private String target;

    /**
     * 是否同时踢出
     */
    @Schema(description = "是否同时踢出", defaultValue = "true")
    private Boolean kick = true;

    /**
     * 封禁原因
     */
    @Schema(description = "封禁原因", example = "作弊")
    private String reason;
}
