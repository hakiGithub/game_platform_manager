package com.gameplatform.plugin.manager;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.DefaultPluginManager;
import org.pf4j.JarPluginLoader;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginLoader;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

import java.nio.file.Path;
import java.util.List;

/**
 * 游戏平台自定义插件管理器
 * 继承PF4J的DefaultPluginManager，提供游戏平台特定的插件管理功能
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
public class GamePlatformPluginManager extends DefaultPluginManager {

    private final ClassLoader parentClassLoader;

    /**
     * 构造函数
     *
     * @param pluginsRoot 插件根目录
     */
    public GamePlatformPluginManager(Path pluginsRoot) {
        this(pluginsRoot, GamePlatformPluginManager.class.getClassLoader());
    }

    /**
     * 构造函数 - 使用指定的 ClassLoader
     * 
     * @param pluginsRoot 插件根目录
     * @param parentClassLoader 父 ClassLoader
     */
    public GamePlatformPluginManager(Path pluginsRoot, ClassLoader parentClassLoader) {
        super(pluginsRoot);
        this.parentClassLoader = parentClassLoader;
        log.info("GamePlatformPluginManager 初始化，插件目录: {}", pluginsRoot);
    }

    /**
     * 创建自定义的插件加载器
     * 使用父 ClassLoader 确保扩展点接口在同一个 ClassLoader 中
     */
    @Override
    protected PluginLoader createPluginLoader() {
        return new JarPluginLoader(this) {
            @Override
            public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor pluginDescriptor) {
                // 使用 parentFirst=true，确保扩展点接口从父 ClassLoader 加载
                PluginClassLoader classLoader = new PluginClassLoader(
                    pluginManager, 
                    pluginDescriptor, 
                    parentClassLoader,
                    true  // parentFirst - 先从父 ClassLoader 加载类
                );
                classLoader.addFile(pluginPath.toFile());
                return classLoader;
            }
        };
    }

    /**
     * 获取所有已加载插件的ID列表
     *
     * @return 插件ID列表
     */
    public List<String> getLoadedPluginIds() {
        return getPlugins().stream()
                .map(PluginWrapper::getPluginId)
                .toList();
    }

    /**
     * 检查插件是否已加载
     *
     * @param pluginId 插件ID
     * @return 是否已加载
     */
    public boolean isPluginLoaded(String pluginId) {
        return getPlugin(pluginId) != null;
    }

    /**
     * 获取插件状态
     *
     * @param pluginId 插件ID
     * @return 状态描述
     */
    public String getPluginState(String pluginId) {
        PluginWrapper plugin = getPlugin(pluginId);
        if (plugin == null) {
            return "NOT_FOUND";
        }
        return plugin.getPluginState().name();
    }

}
