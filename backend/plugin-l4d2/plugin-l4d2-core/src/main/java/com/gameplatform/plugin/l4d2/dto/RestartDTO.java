package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 重启请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "L4D2 重启请求")
public class RestartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例 ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 重启模式：AUTO / RCON / COMMAND，可选，默认 AUTO */
    @Schema(description = "重启模式：AUTO/RCON/COMMAND，默认 AUTO")
    private String mode;
}
