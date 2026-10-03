package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * API 令牌实体（ADR-0029 长期可吊销凭证）。
 *
 * <p>对应表 api_token。{@code tokenHash} 为明文 SHA-256 小写 hex（机密，仅存储不返回）；
 * 归属用户名 {@code username} 为查询辅助字段，仅在 selectActiveByHash 的 JOIN 中填充，非物理列。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_token")
public class ApiToken extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 令牌名（人工识别与审计，同一用户下未吊销令牌不允许重名） */
    private String name;

    /** 明文前 12 字符（非机密，列表展示） */
    @TableField("token_prefix")
    private String tokenPrefix;

    /** SHA-256(明文) 小写 hex（64 字符，唯一索引，认证查询路径） */
    @TableField("token_hash")
    private String tokenHash;

    /** 作用域：read / write */
    private String scope;

    /** 归属用户 sys_user.id */
    @TableField("user_id")
    private Long userId;

    /** 过期时刻，NULL = 不过期 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 吊销标记 0/1（吊销不用 is_deleted） */
    private Integer revoked;

    /** 吊销时刻 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** 最近使用时间（≥60s 节流） */
    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;

    /** 归属用户名（非物理列，仅 selectActiveByHash 的 JOIN 填充） */
    @TableField(exist = false)
    private String username;

}
