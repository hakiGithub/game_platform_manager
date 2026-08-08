package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * RCON 命令执行结果响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "RCON 命令执行结果响应")
public class RconResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    @Schema(description = "是否成功")
    private Boolean success;

    /**
     * 命令输出
     */
    @Schema(description = "命令输出")
    private String output;

    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String error;

    /**
     * 执行时间（毫秒）
     */
    @Schema(description = "执行时间（毫秒）")
    private Long executionTime;
}
