package com.gameplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.config.ApiTokenProperties;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.dto.ApiTokenCreateDTO;
import com.gameplatform.entity.ApiToken;
import com.gameplatform.entity.User;
import com.gameplatform.enums.TokenScope;
import com.gameplatform.mapper.ApiTokenMapper;
import com.gameplatform.mapper.UserMapper;
import com.gameplatform.service.ApiTokenService;
import com.gameplatform.util.ApiTokenCodec;
import com.gameplatform.vo.ApiTokenCreatedVO;
import com.gameplatform.vo.ApiTokenVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * API 令牌服务实现（ADR-0029）。
 *
 * <p>{@link #authenticate} 不写库、不设 SecurityContext、不依赖 Spring Security 类，
 * 仅依赖 Mapper，服务层单测无需 Web/Security 上下文。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenServiceImpl implements ApiTokenService {

    /** 单用户未吊销令牌配额上限（服务层常量，ADR-0029 R7）。 */
    private static final int MAX_ACTIVE_TOKENS_PER_USER = 20;

    /** read 作用域允许的安全方法（大写）。 */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final int DEFAULT_EXPIRES_IN_DAYS = 365;
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 3650;

    private final ApiTokenMapper apiTokenMapper;
    private final UserMapper userMapper;
    private final ApiTokenProperties apiTokenProperties;

    @Override
    @Transactional
    public ApiTokenCreatedVO create(ApiTokenCreateDTO dto) {
        User current = requireCurrentUser();

        // 兜底校验（Web 层 @Valid 之外，服务层直接调用时同样生效）
        if (dto == null || !StringUtils.hasText(dto.getName())) {
            throw new BusinessException("令牌名不能为空");
        }
        if (dto.getName().length() > 100) {
            throw new BusinessException("令牌名长度不能超过 100");
        }

        TokenScope scope;
        if (!StringUtils.hasText(dto.getScope())) {
            scope = TokenScope.READ;
        } else {
            scope = TokenScope.fromCode(dto.getScope())
                    .orElseThrow(() -> new BusinessException("作用域只能是 read 或 write"));
        }

        int days = dto.getExpiresInDays() == null ? DEFAULT_EXPIRES_IN_DAYS : dto.getExpiresInDays();
        if (days < MIN_EXPIRES_IN_DAYS || days > MAX_EXPIRES_IN_DAYS) {
            throw new BusinessException("有效天数必须在 1~3650 之间");
        }

        if (apiTokenMapper.countActiveByUser(current.getId()) >= MAX_ACTIVE_TOKENS_PER_USER) {
            throw new BusinessException("已达令牌数量上限（每用户 " + MAX_ACTIVE_TOKENS_PER_USER + " 个）");
        }
        if (apiTokenMapper.countActiveByName(current.getId(), dto.getName()) > 0) {
            throw new BusinessException("令牌名已存在: " + dto.getName());
        }

        String plaintext = ApiTokenCodec.generatePlaintext();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(days);

        ApiToken entity = new ApiToken();
        entity.setName(dto.getName());
        entity.setTokenPrefix(ApiTokenCodec.prefixOf(plaintext));
        entity.setTokenHash(ApiTokenCodec.hash(plaintext));
        entity.setScope(scope.getCode());
        entity.setUserId(current.getId());
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(0);
        apiTokenMapper.insert(entity);

        // 只打 prefix，绝不打明文/哈希
        log.info("创建 API 令牌 '{}' (id={}, prefix={}, scope={}, expiresAt={}) by {}",
                entity.getName(), entity.getId(), entity.getTokenPrefix(), scope.getCode(),
                expiresAt, current.getUsername());

        return ApiTokenCreatedVO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .token(plaintext)
                .scope(scope.getCode())
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    public List<ApiTokenVO> list() {
        User current = requireCurrentUser();
        LambdaQueryWrapper<ApiToken> wrapper = new LambdaQueryWrapper<ApiToken>()
                .eq(ApiToken::getUserId, current.getId())
                .orderByDesc(ApiToken::getCreateTime);
        return apiTokenMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional
    public ApiTokenVO revoke(Long id) {
        User current = requireCurrentUser();
        ApiToken existing = apiTokenMapper.selectById(id);
        if (existing == null) {
            // 沿用平台「业务错误 HTTP 200 + body code」口径：由 GlobalExceptionHandler 转 code=404
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "API 令牌不存在: " + id);
        }
        int affected = apiTokenMapper.revokeById(id, LocalDateTime.now());
        if (affected == 0) {
            log.debug("API 令牌 id={} 已吊销，幂等跳过 by {}", id, current.getUsername());
        } else {
            log.info("吊销 API 令牌 id={} (prefix={}) by {}", id, existing.getTokenPrefix(), current.getUsername());
        }
        // 重查取最新投影
        ApiToken latest = apiTokenMapper.selectById(id);
        return toVO(latest != null ? latest : existing);
    }

    @Override
    public Decision authenticate(String plainToken, String httpMethod) {
        ApiToken row = apiTokenMapper.selectActiveByHash(ApiTokenCodec.hash(plainToken));
        if (row == null) {
            return Decision.invalid("not_found");
        }
        if (row.getRevoked() != null && row.getRevoked() == 1) {
            return Decision.invalid("revoked");
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Decision.invalid("expired");
        }
        TokenScope scope = TokenScope.fromCode(row.getScope()).orElse(null);
        if (scope == null) {
            // 脏 scope：fail-closed，绝不当作 write
            return Decision.invalid("invalid_scope");
        }
        if (scope == TokenScope.READ && !isSafeMethod(httpMethod)) {
            return Decision.scopeDenied("scope_read");
        }
        return Decision.allow(row.getId(), row.getName(), row.getUsername(), scope);
    }

    @Override
    public void touch(Long tokenId) {
        if (tokenId == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threshold = now.minusSeconds(apiTokenProperties.getLastUsedThrottleSeconds());
            apiTokenMapper.touchLastUsed(tokenId, now, threshold);
        } catch (Exception e) {
            // best-effort：失败只记日志，绝不影响认证链路
            log.warn("更新 API 令牌 last_used_at 失败 (id={}): {}", tokenId, e.getMessage());
        }
    }

    private static boolean isSafeMethod(String httpMethod) {
        return httpMethod == null || SAFE_METHODS.contains(httpMethod.toUpperCase());
    }

    private User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("用户未登录");
        }
        User user = userMapper.selectByUsername(authentication.getName());
        if (user == null) {
            throw new BusinessException("用户不存在: " + authentication.getName());
        }
        return user;
    }

    /** 服务层投影：绝不带出 tokenHash / 明文。 */
    private ApiTokenVO toVO(ApiToken t) {
        return ApiTokenVO.builder()
                .id(t.getId())
                .name(t.getName())
                .prefix(t.getTokenPrefix())
                .scope(t.getScope())
                .expiresAt(t.getExpiresAt())
                .revoked(t.getRevoked())
                .revokedAt(t.getRevokedAt())
                .lastUsedAt(t.getLastUsedAt())
                .createTime(t.getCreateTime())
                .build();
    }

}
