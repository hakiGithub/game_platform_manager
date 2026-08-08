package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.vo.backup.BackupContent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 备份响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "备份信息响应")
public class BackupVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 备份ID（雪花ID） */
    @Schema(description = "备份ID")
    private String id;

    /** 备份名称 */
    @Schema(description = "备份名称")
    private String name;

    /** 备份描述 */
    @Schema(description = "备份描述")
    private String description;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 备份内容 */
    @Schema(description = "备份内容")
    private BackupContent content;

    /** 创建者 */
    @Schema(description = "创建者")
    private String owner;

    /** 状态 */
    @Schema(description = "状态")
    private String status;
}
