package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建备份请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "创建备份请求")
public class BackupCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 备份名称 */
    @NotBlank(message = "备份名称不能为空")
    @Schema(description = "备份名称", required = true)
    private String name;

    /** 备份描述 */
    @Schema(description = "备份描述")
    private String description;
}
