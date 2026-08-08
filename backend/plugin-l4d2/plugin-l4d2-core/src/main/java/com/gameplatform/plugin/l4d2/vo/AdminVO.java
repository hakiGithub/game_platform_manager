package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员信息响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "管理员信息响应")
public class AdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID（雪花ID）
     */
    @Schema(description = "记录ID")
    private String id;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long instanceId;

    /**
     * SteamID
     */
    @Schema(description = "SteamID")
    private String steamId;

    /**
     * 管理员权限标志
     */
    @Schema(description = "管理员权限标志")
    private String adminFlags;

    /**
     * 备注信息
     */
    @Schema(description = "备注信息")
    private String remark;

    /**
     * 是否激活
     */
    @Schema(description = "是否激活")
    private Boolean isActive;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
