package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "用户响应VO")
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private Long id;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * 最后登录时间
     */
    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录IP
     */
    @Schema(description = "最后登录IP")
    private String lastLoginIp;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
