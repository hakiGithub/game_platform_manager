package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 重启配置更新 DTO。
 *
 * <p>所有字段均可选；未传（null）字段保持原值不变。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "L4D2 重启配置更新请求")
public class RestartConfigUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否优先使用 RCON 模式 */
    @Schema(description = "是否优先使用 RCON 模式")
    private Boolean byRcon;

    /** Docker 容器名 */
    @Schema(description = "Docker 容器名")
    private String containerName;

    /** 自定义重启命令（覆盖默认 docker restart 命令） */
    @Schema(description = "自定义重启命令")
    private String customCmd;

    /** 命令模式执行超时（毫秒） */
    @Schema(description = "命令模式执行超时（毫秒）")
    private Long commandTimeoutMs;

    /** 是否启用重启功能 */
    @Schema(description = "是否启用重启功能")
    private Boolean enabled;
}
