package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * API 令牌创建响应 VO（ADR-0029）。
 *
 * <p>{@code token} 为明文，<b>仅在此出现一次</b>；不落库、不入日志、不进 GET 列表。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "API 令牌创建响应（含明文，仅此一次可见）")
public class ApiTokenCreatedVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "令牌ID")
    private Long id;

    @Schema(description = "令牌名")
    private String name;

    @Schema(description = "明文令牌（仅此一次返回）")
    private String token;

    @Schema(description = "作用域")
    private String scope;

    @Schema(description = "过期时刻")
    private LocalDateTime expiresAt;

}
