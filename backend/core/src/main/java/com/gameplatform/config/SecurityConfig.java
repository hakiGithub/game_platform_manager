package com.gameplatform.config;

import com.gameplatform.common.result.Result;
import com.gameplatform.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security配置类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /**
     * 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤器链配置
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF
                .csrf(AbstractHttpConfigurer::disable)
                
                // 禁用Session
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                
                // 配置请求授权
                .authorizeHttpRequests(auth -> auth
                        // 允许匿名访问的接口
                        .requestMatchers(
                                "/auth/login",
                                "/auth/register",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/webjars/**",
                                "/error"
                        ).permitAll()
                        
                        // 静态资源
                        .requestMatchers(
                                "/favicon.ico",
                                "/static/**",
                                "/public/**"
                        ).permitAll()

                        // 插件前端静态资源（Wujie 子应用）
                        // 注意: context-path=/api 已剥离，匹配 /pf4j/plugin/{gameCode}/ui/**
                        // Wujie 加载子应用资源时不带 Authorization header，必须放行
                        .requestMatchers(
                                new AntPathRequestMatcher("/pf4j/plugin/*/ui/**"),
                                new AntPathRequestMatcher("/pf4j/plugins/*/ui/**")
                        ).permitAll()
                        
                        // WebSocket端点（需要认证，但由WebSocket处理器处理）
                        .requestMatchers(
                                "/ws/**"
                        ).permitAll()

                        // 云盘账号管理（ADR-0024）：凭证类敏感资产，仅管理员
                        // （context-path=/api 已剥离；内置 admin 用户持有 ROLE_ADMIN，见 UserDetailsServiceImpl）
                        .requestMatchers(
                                "/cloud/**"
                        ).hasRole("ADMIN")

                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                )
                
                // 未认证（含 JWT 过期/无效）统一返回 401 JSON，
                // 前端据此自动跳转登录页（默认 EntryPoint 会返回 403，导致前端无法区分"未登录"与"无权限"）
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, ex) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ResultCode.UNAUTHORIZED)));
                }))

                // 添加JWT过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }

}
