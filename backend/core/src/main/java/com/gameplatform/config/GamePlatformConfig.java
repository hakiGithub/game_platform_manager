package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台自定义配置属性类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform")
public class GamePlatformConfig {

    /**
     * 插件配置
     */
    private PluginConfig plugin = new PluginConfig();

    /**
     * SSH配置
     */
    private SshConfig ssh = new SshConfig();

    /**
     * Docker配置
     */
    private DockerConfig docker = new DockerConfig();

    /**
     * 备份配置
     */
    private BackupConfig backup = new BackupConfig();

    /**
     * 文件存储配置
     */
    private StorageConfig storage = new StorageConfig();

    @Data
    public static class PluginConfig {
        /**
         * 插件目录
         */
        private String pluginsDir;

        /**
         * 是否启用插件热加载
         */
        private Boolean hotReload;

        /**
         * 扫描间隔(秒)
         */
        private Integer scanInterval;
    }

    @Data
    public static class SshConfig {
        /**
         * 连接超时时间(毫秒)
         */
        private Integer connectTimeout;

        /**
         * 默认端口
         */
        private Integer defaultPort;

        /**
         * 会话超时时间(毫秒)
         */
        private Integer sessionTimeout;
    }

    @Data
    public static class DockerConfig {
        /**
         * Docker Host
         */
        private String host;

        /**
         * Docker API版本
         */
        private String apiVersion;

        /**
         * 连接超时(秒)
         */
        private Integer connectTimeout;

        /**
         * 读取超时(秒)
         */
        private Integer readTimeout;
    }

    @Data
    public static class BackupConfig {
        /**
         * 备份目录
         */
        private String backupDir;

        /**
         * 最大备份数量(每个实例)
         */
        private Integer maxBackups;

        /**
         * 备份保留天数
         */
        private Integer retentionDays;

        /**
         * 压缩格式: zip, tar.gz
         */
        private String compressFormat;

        /**
         * 备份超时时间(分钟)
         */
        private Integer timeoutMinutes;

        /**
         * 是否启用自动清理
         */
        private Boolean autoCleanup;
    }

    @Data
    public static class StorageConfig {
        /**
         * 文件存储根目录
         */
        private String baseDir;

        /**
         * 临时文件目录
         */
        private String tempDir;
    }

}
