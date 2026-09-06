package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Steam302 主机加速配置项
 *
 * <p>对应配置前缀 {@code game-platform.steam302}：
 * <pre>
 * game-platform:
 *   steam302:
 *     image: registry.example.com/steam302:15.0.4
 *     data-dir: /opt/steam302
 *     container-name: steam302
 * </pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform.steam302")
public class Steam302Properties {

    /** 容器镜像（安装任务 docker pull 用，阿里云深圳仓库） */
    private String image = "registry.cn-shenzhen.aliyuncs.com/haki_hub/gfw-302:15.0.4";

    /** 宿主机数据目录（挂载为容器 /data：ini/证书/Caddyfile 等运行时产物） */
    private String dataDir = "/opt/steam302";

    /** 容器名 */
    private String containerName = "steam302";

    /** 单条远程命令超时（毫秒） */
    private long commandTimeoutMs = 30_000;

    /** 安装任务中等待证书生成的超时（秒） */
    private long certWaitTimeoutSeconds = 90;
}
