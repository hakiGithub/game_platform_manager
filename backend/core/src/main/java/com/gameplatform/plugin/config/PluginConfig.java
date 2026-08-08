package com.gameplatform.plugin.config;

import com.gameplatform.plugin.listener.PluginStateEventListener;
import com.gameplatform.plugin.manager.GamePlatformPluginManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 插件框架配置类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Configuration
public class PluginConfig {

    /**
     * 插件目录路径
     */
    @Getter
    @Value("${game-platform.plugin.plugins-dir:${user.home}/game-platform/plugins}")
    private String pluginsDir;

    /**
     * 是否启用插件热加载
     */
    @Getter
    @Value("${game-platform.plugin.hot-reload:true}")
    private boolean hotReload;

    /**
     * 插件扫描间隔（秒）
     */
    @Getter
    @Value("${game-platform.plugin.scan-interval:30}")
    private int scanInterval;

    private PluginManager pluginManager;

    /**
     * 创建插件管理器Bean
     * 使用延迟注入避免循环依赖
     *
     * @return 插件管理器实例
     */
    @Bean
    public PluginManager pluginManager() {
        // 确保插件目录存在
        Path pluginsPath = Paths.get(pluginsDir);
        File pluginsDirFile = pluginsPath.toFile();

        if (!pluginsDirFile.exists()) {
            boolean created = pluginsDirFile.mkdirs();
            if (created) {
                log.info("创建插件目录: {}", pluginsDir);
            }
        }

        log.info("初始化插件管理器，插件目录: {}, 热加载: {}, 扫描间隔: {}秒",
                pluginsDir, hotReload, scanInterval);

        pluginManager = new GamePlatformPluginManager(pluginsPath);
        return pluginManager;
    }

    /**
     * 注册插件状态监听器
     * 使用 @PostConstruct 在 Bean 创建完成后注册监听器，避免循环依赖
     *
     * @param stateEventListener 插件状态监听器
     */
    @Autowired
    public void registerPluginStateListener(PluginStateEventListener stateEventListener) {
        if (pluginManager != null) {
            pluginManager.addPluginStateListener(stateEventListener);
            log.info("已注册插件状态监听器");
        }
    }

}
