package com.gameplatform.config;

import com.gameplatform.common.result.Result;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.service.ApiTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT / API Token 认证过滤器
 *
 * <p>两条互斥凭证分支（ADR-0029 D3/D5）：{@code gpm_} 前缀走长期 API Token 认证，
 * 其余走既有 JWT 认证，JWT 路径逐行原样保留（零回归）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final ApiTokenService apiTokenService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            // 从请求头中获取Token
            String token = getTokenFromRequest(request);

            // ===== 长期 API Token 分支（ADR-0029）：既有 JWT 路径不经过此处 =====
            if (StringUtils.hasText(token) && token.startsWith(ApiTokenService.PLAINTEXT_PREFIX)) {
                ApiTokenService.Decision decision =
                        apiTokenService.authenticate(token, request.getMethod());
                if (decision.getOutcome() == ApiTokenService.Decision.Outcome.ALLOW) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(decision.getUsername());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, apiTokenAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("API 令牌 '{}' (scope={}) → {} {}", decision.getTokenName(),
                            decision.getScope(), request.getMethod(), request.getRequestURI());
                    apiTokenService.touch(decision.getTokenId());
                } else if (decision.getOutcome() == ApiTokenService.Decision.Outcome.SCOPE_DENIED) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN);
                    return;
                } else {
                    log.warn("API 令牌认证失败: {} (path={} {})", decision.getReason(),
                            request.getMethod(), request.getRequestURI());
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
                    return;
                }
            }
            // ===== 既有 JWT 逻辑，原样保留（:50-67），一行不改 =====
            else if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                // 从Token中获取用户名
                String username = jwtTokenProvider.getUsernameFromToken(token);

                // 加载用户信息
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // 创建认证对象
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 设置到SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            log.error("认证失败: {}", e.getMessage());
            handleAuthenticationError(response, e);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中获取Token
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * API 令牌主体权限：固定 ROLE_USER + ROLE_API_TOKEN，丢弃 ROLE_ADMIN（ADR-0029 D5）。
     *
     * <p>与归属用户名无关：即使是 admin 签发的令牌也不是管理员主体，从而关闭"令牌管令牌"回路。</p>
     */
    private List<GrantedAuthority> apiTokenAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_API_TOKEN"));
    }

    /**
     * 处理认证错误（既有 401 出口，交由 EntryPoint/下游继续时不改变形状）
     */
    private void handleAuthenticationError(HttpServletResponse response, Exception e) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
    }

    /**
     * 统一的 JSON 错误写出（gpm_ 分支与既有 catch 分支共用）。
     */
    private void writeError(HttpServletResponse response, int status, ResultCode resultCode) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        Result<Void> result = Result.fail(resultCode);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

}
