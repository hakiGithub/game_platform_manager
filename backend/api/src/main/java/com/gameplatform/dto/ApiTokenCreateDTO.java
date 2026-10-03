package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * API 令牌创建请求 DTO（ADR-0029）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "API 令牌创建请求")
public class ApiTokenCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "令牌名", required = true, example = "gpmcli")
    @NotBlank(message = "令牌名不能为空")
    @Size(max = 100, message = "令牌名长度不能超过 100")
    private String name;

    @Schema(description = "作用域：read / write", example = "read")
    @Pattern(regexp = "read|write", message = "作用域只能是 read 或 write")
    private String scope = "read";

    @Schema(description = "有效天数（1~3650，缺省 365）", example = "365")
    @Min(value = 1, message = "有效天数不能小于 1")
    @Max(value = 3650, message = "有效天数不能大于 3650")
    private Integer expiresInDays = 365;

}
