package com.gameplatform.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.dto.ApiTokenCreateDTO;
import com.gameplatform.service.ApiTokenService;
import com.gameplatform.vo.ApiTokenCreatedVO;
import com.gameplatform.vo.ApiTokenVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API 令牌管理控制器（ADR-0029）。
 *
 * <p>context-path=/api 已剥离，实际路径 /api/tokens。权限由 SecurityConfig
 * {@code /tokens/** → hasRole("ADMIN")} 收紧；API 令牌主体永不含 ROLE_ADMIN，
 * 故令牌无法管理令牌（防令牌管令牌回路）。明文只在 POST 响应出现一次。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "API 令牌管理", description = "长期可吊销 API Token（ADR-0029）")
@RestController
@RequestMapping("/tokens")
@RequiredArgsConstructor
@Validated
public class ApiTokenController {

    private final ApiTokenService apiTokenService;

    @Operation(summary = "签发 API 令牌（明文仅此一次返回）")
    @PostMapping
    public Result<ApiTokenCreatedVO> create(@Validated @RequestBody ApiTokenCreateDTO dto) {
        return Result.success(apiTokenService.create(dto));
    }

    @Operation(summary = "列出当前用户的全部令牌（不含明文与哈希）")
    @GetMapping
    public Result<List<ApiTokenVO>> list() {
        return Result.success(apiTokenService.list());
    }

    @Operation(summary = "吊销令牌（幂等；未知 id 返回 body code=404）")
    @DeleteMapping("/{id}")
    public Result<ApiTokenVO> revoke(@PathVariable("id") Long id) {
        return Result.success(apiTokenService.revoke(id));
    }

}
