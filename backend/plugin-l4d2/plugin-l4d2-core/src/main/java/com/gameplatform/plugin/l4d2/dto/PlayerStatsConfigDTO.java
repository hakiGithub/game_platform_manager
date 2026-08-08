package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 玩家统计配置更新请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "玩家统计配置更新请求")
public class PlayerStatsConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否启用采集 */
    @Schema(description = "是否启用采集")
    private Boolean enable;
}
