package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * L4D2 重启配置响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "L4D2 重启配置响应")
public class RestartConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否优先使用 RCON 模式（AUTO 模式下生效） */
    @Schema(description = "是否优先使用 RCON 模式")
    private Boolean byRcon;

    /** 容器名（命令模式默认 docker restart {containerName}） */
    @Schema(description = "Docker 容器名")
    private String containerName;

    /** 自定义重启命令（非空时覆盖默认 docker restart 命令） */
    @Schema(description = "自定义重启命令")
    private String customCmd;

    /** 可用重启模式列表 */
    @Schema(description = "可用重启模式")
    private List<String> availableModes;

    /** 当前是否启用重启功能 */
    @Schema(description = "是否启用重启功能")
    private Boolean enabled;
}
