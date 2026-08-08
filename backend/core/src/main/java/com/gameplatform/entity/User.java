package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户实体类
 * 对应表: sys_user
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class User extends BaseEntity {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码哈希值
     */
    private String passwordHash;

    /**
     * JWT密钥(用于生成用户专属Token)
     */
    private String jwtSecret;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录IP
     */
    private String lastLoginIp;

}
