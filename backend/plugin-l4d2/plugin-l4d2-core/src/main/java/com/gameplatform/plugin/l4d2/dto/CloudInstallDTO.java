package com.gameplatform.plugin.l4d2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 云盘转存安装请求（ADR-0025）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CloudInstallDTO {

    /** 目标实例 ID */
    @NotNull(message = "instanceId 不能为空")
    private Long instanceId;

    /** 云盘账号 name（主前端「云盘账号」页管理） */
    @NotBlank(message = "accountName 不能为空")
    private String accountName;

    /** 分享链接 */
    @NotBlank(message = "shareUrl 不能为空")
    private String shareUrl;

    /** 提取码（可空） */
    private String passcode;

    /** 地图来源（如 ORANGE，可空；用于转存目录命名） */
    private String source;

    /** 地图来源 ID（可空；用于转存目录命名） */
    private String sourceId;

    /** 地图标题（可空，仅记录展示） */
    private String title;
}
