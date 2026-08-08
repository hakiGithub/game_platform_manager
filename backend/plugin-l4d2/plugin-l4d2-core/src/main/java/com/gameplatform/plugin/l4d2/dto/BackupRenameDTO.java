package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 重命名备份请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "重命名备份请求")
public class BackupRenameDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 备份ID */
    @NotBlank(message = "备份ID不能为空")
    @Schema(description = "备份ID", required = true)
    private String backupId;

    /** 新名称 */
    @NotBlank(message = "新名称不能为空")
    @Schema(description = "新名称", required = true)
    private String newName;
}
