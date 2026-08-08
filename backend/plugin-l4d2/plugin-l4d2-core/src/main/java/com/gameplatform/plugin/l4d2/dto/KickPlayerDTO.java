package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 踢出玩家请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "踢出玩家请求")
public class KickPlayerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 玩家名称或ID
     */
    @NotBlank(message = "玩家名称或ID不能为空")
    @Schema(description = "玩家名称或ID", required = true, example = "Player1")
    private String target;

    /**
     * 踢出原因
     */
    @Schema(description = "踢出原因", example = "违规操作")
    private String reason;
}
