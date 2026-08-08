package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应VO")
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 访问令牌
     */
    @Schema(description = "访问令牌")
    private String token;

    /**
     * 令牌类型
     */
    @Schema(description = "令牌类型")
    private String tokenType;

    /**
     * 过期时间(毫秒)
     */
    @Schema(description = "过期时间(毫秒)")
    private Long expiresIn;

    /**
     * 用户信息
     */
    @Schema(description = "用户信息")
    private UserVO user;

}
