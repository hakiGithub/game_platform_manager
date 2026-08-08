package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT配置属性类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /**
     * JWT密钥
     */
    private String secret;

    /**
     * JWT过期时间(毫秒)
     */
    private Long expiration;

    /**
     * Token前缀
     */
    private String tokenPrefix;

    /**
     * Token请求头名称
     */
    private String header;

}
