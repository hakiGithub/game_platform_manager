package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 重启偏好 spec（ADR-0020）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class RestartConfigSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 重启偏好：true=优先 RCON 重启，false=优先命令重启（AUTO 模式分流依据） */
    private Boolean byRcon;

    /** 命令模式默认容器名（docker restart {containerName}） */
    private String containerName;

    /** 自定义重启命令（非空时覆盖默认 docker restart） */
    private String customCmd;

    /** 命令模式执行超时（毫秒） */
    private Long commandTimeoutMs;

    /** 重启功能启用开关 */
    private Boolean enabled;
}
