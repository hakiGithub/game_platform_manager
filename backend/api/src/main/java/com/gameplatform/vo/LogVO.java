package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "操作日志响应VO")
public class LogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 日志ID
     */
    @Schema(description = "日志ID")
    private Long id;

    /**
     * 操作人
     */
    @Schema(description = "操作人")
    private String operator;

    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String operationType;

    /**
     * 操作目标
     */
    @Schema(description = "操作目标")
    private String operationTarget;

    /**
     * 操作内容
     */
    @Schema(description = "操作内容")
    private String operationContent;

    /**
     * 操作结果 success/fail
     */
    @Schema(description = "操作结果")
    private String operationResult;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
