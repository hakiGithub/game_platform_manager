package com.gameplatform.plugin.context;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件运行时上下文持有者
 * <p>
 * 每个插件在加载时注册自己的上下文信息，插件代码可通过此类
 * 在任意位置获取当前插件的运行时上下文（pluginId、gameCode 等）。
 * <p>
 * 数据持久化请通过 Spring 注入 {@code ExtensionClient} Bean 完成。
 *
 * @author GamePlatform
 * @version 3.0.0
 */
public final class PluginContextHolder {

    private static final Map<String, PluginContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    private PluginContextHolder() {
    }

    /**
     * 注册插件上下文（由框架在加载插件时调用）
     *
     * @param context 插件上下文
     */
    public static void register(PluginContext context) {
        if (context != null && context.getPluginId() != null) {
            CONTEXT_MAP.put(context.getPluginId(), context);
        }
    }

    /**
     * 注销插件上下文（由框架在卸载插件时调用）
     *
     * @param pluginId 插件ID
     */
    public static void unregister(String pluginId) {
        if (pluginId != null) {
            CONTEXT_MAP.remove(pluginId);
        }
    }

    /**
     * 获取指定插件的上下文
     *
     * @param pluginId 插件ID
     * @return 插件上下文（Optional）
     */
    public static Optional<PluginContext> getContext(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CONTEXT_MAP.get(pluginId));
    }

    /**
     * 根据游戏编码获取插件上下文
     *
     * @param gameCode 游戏编码
     * @return 插件上下文（Optional）
     */
    public static Optional<PluginContext> getByGameCode(String gameCode) {
        return CONTEXT_MAP.values().stream()
                .filter(ctx -> gameCode.equals(ctx.getGameCode()))
                .findFirst();
    }

    /**
     * 获取所有已注册的插件上下文
     *
     * @return 不可变的上下文列表
     */
    public static List<PluginContext> getAllContexts() {
        return List.copyOf(CONTEXT_MAP.values());
    }

    /**
     * 检查插件是否已注册
     *
     * @param pluginId 插件ID
     * @return 是否已注册
     */
    public static boolean isRegistered(String pluginId) {
        return pluginId != null && CONTEXT_MAP.containsKey(pluginId);
    }

    /**
     * 清除所有上下文（仅用于测试）
     */
    static void clearAll() {
        CONTEXT_MAP.clear();
    }
}
