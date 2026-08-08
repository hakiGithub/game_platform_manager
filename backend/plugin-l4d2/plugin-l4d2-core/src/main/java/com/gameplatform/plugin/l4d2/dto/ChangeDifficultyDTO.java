package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 切换难度请求 DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "切换难度请求")
public class ChangeDifficultyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /**
     * 难度（简单/普通/高级/专家）
     */
    @NotBlank(message = "难度不能为空")
    @Schema(description = "难度", required = true, example = "普通", allowableValues = {"简单", "普通", "高级", "专家"})
    private String difficulty;
}
