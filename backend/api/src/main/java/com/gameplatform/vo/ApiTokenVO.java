package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * API 令牌列表/详情投影 VO（ADR-0029）。
 *
 * <p>不含 {@code tokenHash} 与明文；{@code prefix} 为明文前 12 字符（非机密展示用）。
 * 序列化前由服务层投影，禁止把实体直接当响应。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "API 令牌信息")
public class ApiTokenVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "令牌ID")
    private Long id;

    @Schema(description = "令牌名")
    private String name;

    @Schema(description = "明文前缀（前 12 字符）")
    private String prefix;

    @Schema(description = "作用域")
    private String scope;

    @Schema(description = "过期时刻")
    private LocalDateTime expiresAt;

    @Schema(description = "是否吊销：0-未吊销 1-已吊销")
    private Integer revoked;

    @Schema(description = "吊销时刻")
    private LocalDateTime revokedAt;

    @Schema(description = "最近使用时间")
    private LocalDateTime lastUsedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
