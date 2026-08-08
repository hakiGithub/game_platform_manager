package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 切换游戏模式请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "切换游戏模式请求")
public class ChangeGameModeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 游戏模式
     */
    @NotBlank(message = "游戏模式不能为空")
    @Schema(description = "游戏模式", required = true, example = "合作", 
            allowableValues = {"合作", "写实", "生存", "对抗", "拾荒", "坚守"})
    private String gameMode;
}
