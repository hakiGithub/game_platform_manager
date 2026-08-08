package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 解析下载链接请求 DTO（Task 4.2 用，本 Task 先创建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "解析下载链接请求")
public class ParseLinkDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待解析的链接 */
    @NotBlank(message = "链接不能为空")
    @Schema(description = "待解析的链接", required = true)
    private String url;
}
