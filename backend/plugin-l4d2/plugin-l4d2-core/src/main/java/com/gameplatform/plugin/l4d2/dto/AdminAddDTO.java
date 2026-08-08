package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 添加管理员请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "添加管理员请求")
public class AdminAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * SteamID
     */
    @NotBlank(message = "SteamID 不能为空")
    @Schema(description = "SteamID", required = true, example = "STEAM_0:0:123456")
    private String steamId;

    /**
     * 管理员权限标志
     */
    @Schema(description = "管理员权限标志", example = "99:z", defaultValue = "99:z")
    private String adminFlags = "99:z";

    /**
     * 备注信息
     */
    @Schema(description = "备注信息", example = "服务器管理员")
    private String remark;
}
