package com.gameplatform.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtConfig jwtConfig;

    /**
     * 生成密钥
     */
    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成Token
     *
     * @param username 用户名
     * @return Token
     */
    public String generateToken(String username) {
        return generateToken(username, new HashMap<>());
    }

    /**
     * 生成Token(带额外信息)
     *
     * @param username 用户名
     * @param claims   额外信息
     * @return Token
     */
    public String generateToken(String username, Map<String, Object> claims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtConfig.getExpiration());

        JwtBuilder builder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSecretKey());

        if (claims != null && !claims.isEmpty()) {
            builder.claims(claims);
        }

        return builder.compact();
    }

    /**
     * 从Token中获取用户名
     *
     * @param token Token
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * 从Token中获取Claims
     *
     * @param token Token
     * @return Claims
     */
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证Token是否有效
     *
     * @param token Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e) {
            log.warn("无效的JWT Token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.warn("JWT Token已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的JWT Token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT Token为空: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("JWT Token验证失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 判断Token是否过期
     *
     * @param token Token
     * @return 是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 刷新Token
     *
     * @param token 旧Token
     * @return 新Token
     */
    public String refreshToken(String token) {
        Claims claims = getClaimsFromToken(token);
        String username = claims.getSubject();
        return generateToken(username);
    }

    /**
     * 获取过期时间(毫秒)
     *
     * @return 过期时间
     */
    public Long getExpiration() {
        return jwtConfig.getExpiration();
    }

}
