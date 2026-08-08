package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 实例 ID 请求 DTO
 * 用于只需要 instanceId 参数的请求
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "实例 ID 请求")
public class InstanceIdDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;
}
