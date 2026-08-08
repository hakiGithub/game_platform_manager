package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * RCON 命令请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "RCON 命令请求")
public class RconCommandDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * RCON 命令
     */
    @NotBlank(message = "命令不能为空")
    @Schema(description = "RCON 命令", required = true, example = "status")
    private String command;
}
