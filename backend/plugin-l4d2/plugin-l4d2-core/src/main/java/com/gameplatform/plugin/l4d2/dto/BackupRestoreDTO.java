package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 还原备份请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "还原备份请求")
public class BackupRestoreDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    @NotNull(message = "实例ID不能为空")
    @Schema(description = "实例ID", required = true)
    private Long instanceId;

    /** 备份ID */
    @NotBlank(message = "备份ID不能为空")
    @Schema(description = "备份ID", required = true)
    private String backupId;
}
