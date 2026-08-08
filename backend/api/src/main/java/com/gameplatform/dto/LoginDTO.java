package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "登录请求DTO")
public class LoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户名
     */
    @Schema(description = "用户名", required = true, example = "admin")
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码", required = true, example = "admin123")
    @NotBlank(message = "密码不能为空")
    private String password;

}
