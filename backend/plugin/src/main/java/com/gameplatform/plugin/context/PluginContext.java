package com.gameplatform.plugin.context;

import java.util.Map;

/**
 * 插件运行时上下文
 * <p>
 * 封装插件运行时的关键信息，由框架在加载插件时构建并注册到 {@link PluginContextHolder}。
 * <p>
 * 数据持久化通过子容器注入的 {@code ExtensionClient} Bean 完成，不再经由此接口传递。
 *
 * @author GamePlatform
 * @version 3.0.0
 */
public interface PluginContext {

    /**
     * 获取插件ID
     *
     * @return 插件ID（如 "plugin-l4d2"）
     */
    String getPluginId();

    /**
     * 获取游戏编码
     *
     * @return 游戏编码（如 "l4d2"）
     */
    String getGameCode();

    /**
     * 获取游戏名称
     *
     * @return 游戏名称（如 "求生之路2"）
     */
    String getGameName();

    /**
     * 获取插件版本
     *
     * @return 版本号（如 "1.0.0"）
     */
    String getVersion();

    /**
     * 获取插件自定义属性
     * <p>
     * 框架加载插件时从 plugin.properties 读取的自定义属性，
     * 插件可通过此方法获取非标准属性的值。
     *
     * @return 属性Map（不可变）
     */
    Map<String, String> getCustomProperties();
}
