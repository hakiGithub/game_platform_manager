package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 设置最大玩家数请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "设置最大玩家数请求")
public class SetMaxPlayersDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 最大玩家数（4-30）
     */
    @NotNull(message = "最大玩家数不能为空")
    @Min(value = 4, message = "最小玩家数为4")
    @Max(value = 30, message = "最大玩家数为30")
    @Schema(description = "最大玩家数", required = true, example = "8", minimum = "4", maximum = "30")
    private Integer maxPlayers;
}
