package com.gameplatform.plugin.loader;

import com.gameplatform.plugin.config.PluginConfig;
import com.gameplatform.plugin.context.PluginSpringContextFactory;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.manager.GamePlatformPluginManager;
import com.gameplatform.plugin.util.PluginUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * 插件自动加载器
 * <p>
 * 应用启动时自动扫描并加载 plugins 目录下的所有插件，
 * 并在应用就绪后为每个插件创建 Spring 子容器。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginAutoLoader implements ApplicationRunner {

    private final PluginManager pluginManager;
    private final PluginConfig pluginConfig;
    private final PluginSpringContextFactory springContextFactory;

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 开始加载插件 ==========");
        log.info("插件目录: {}", pluginConfig.getPluginsDir());

        try {
            pluginManager.loadPlugins();
            log.info("已加载 {} 个插件", pluginManager.getPlugins().size());

            pluginManager.startPlugins();
            log.info("已启动 {} 个插件", pluginManager.getPlugins().stream()
                    .filter(p -> p.getPluginState() == PluginState.STARTED)
                    .count());

            printPluginDetails();
            printExtensionDetails();

        } catch (Exception e) {
            log.error("插件加载失败", e);
        }

        log.info("========== 插件加载完成 ==========");
    }

    /**
     * 在应用完全启动后，为插件创建 Spring 子容器。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info(">>> [PluginSpring] Application ready, initializing plugin Spring contexts...");
        initPluginSpringContexts();
        log.info(">>> [PluginSpring] Plugin Spring context initialization complete.");
    }

    /**
     * 为所有已加载的插件创建 Spring 子容器。
     */
    private void initPluginSpringContexts() {
        List<GameEnhancementExtension> extensions =
                pluginManager.getExtensions(GameEnhancementExtension.class);

        log.info("[PluginSpring] Found {} GameEnhancementExtension(s)", extensions.size());

        for (GameEnhancementExtension ext : extensions) {
            String pluginId = PluginUtils.findPluginIdByExtension(ext, pluginManager);
            if (pluginId == null) {
                log.warn("[PluginSpring] Cannot find pluginId for extension: {}", ext.getClass().getName());
                continue;
            }

            PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
            if (wrapper == null) {
                log.warn("[PluginSpring] Cannot find wrapper for pluginId: {}", pluginId);
                continue;
            }

            log.info("[PluginSpring] Creating Spring context for pluginId: {}", pluginId);

            try {
                Properties props = PluginUtils.loadPluginProperties(wrapper);
                springContextFactory.loadPluginSpringContext(wrapper, ext, props);
                log.info("插件 [{}] Spring 子容器创建成功", pluginId);
            } catch (Exception e) {
                log.error("插件 [{}] Spring 子容器创建失败", pluginId, e);
                ext.onLoadError(null, e);
            }
        }
    }

    private void printPluginDetails() {
        List<PluginWrapper> plugins = pluginManager.getPlugins();
        if (plugins.isEmpty()) {
            log.info("没有找到任何插件");
            return;
        }

        log.info("---------- 插件列表 ----------");
        for (PluginWrapper plugin : plugins) {
            log.info("插件ID: {}, 版本: {}, 状态: {}, 描述: {}",
                    plugin.getPluginId(),
                    plugin.getDescriptor().getVersion(),
                    plugin.getPluginState(),
                    plugin.getDescriptor().getPluginDescription());
        }
    }

    private void printExtensionDetails() {
        List<GameEnhancementExtension> extensions = pluginManager.getExtensions(GameEnhancementExtension.class);

        log.info("查找 GameEnhancementExtension 扩展点...");
        log.info("PluginManager 类型: {}", pluginManager.getClass().getName());
        log.info("已加载插件数量: {}", pluginManager.getPlugins().size());

        if (extensions.isEmpty()) {
            log.info("没有找到任何游戏增强扩展点");
            for (PluginWrapper plugin : pluginManager.getPlugins()) {
                log.info("插件 {} 的扩展点类: {}", plugin.getPluginId(),
                    plugin.getPluginManager().getExtensionClasses(plugin.getPluginId()));
            }
            return;
        }

        log.info("---------- 游戏增强扩展点 ----------");
        for (GameEnhancementExtension extension : extensions) {
            log.info("游戏编码: {}, 游戏名称: {}, 版本: {}",
                    extension.getGameCode(),
                    extension.getGameName(),
                    extension.getVersion());
        }
    }
}
