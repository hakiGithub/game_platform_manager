package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 解析 Workshop 链接请求 DTO（Task 4.2 用，本 Task 先创建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "解析 Workshop 链接请求")
public class ParseWorkshopDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Workshop URL */
    @NotBlank(message = "Workshop 链接不能为空")
    @Schema(description = "Workshop URL", required = true)
    private String url;
}
