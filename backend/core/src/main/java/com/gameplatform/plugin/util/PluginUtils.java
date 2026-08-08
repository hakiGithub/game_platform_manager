package com.gameplatform.plugin.util;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件工具类
 * <p>
 * 集中管理框架内多处使用的通用方法，消除代码重复。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Slf4j
public final class PluginUtils {

    private PluginUtils() {
    }

    // ==================== 扩展点查找 ====================

    /** gameCode → pluginId 缓存（避免重复遍历） */
    private static final Map<String, String> gameCodeCache = new ConcurrentHashMap<>();

    /** extensionClassName → pluginId 缓存 */
    private static final Map<String, String> extensionClassCache = new ConcurrentHashMap<>();

    /**
     * 根据扩展点实例查找对应的插件ID。
     * 使用缓存避免重复遍历所有插件。
     *
     * @param extension 扩展点实例
     * @param pluginManager 插件管理器
     * @return 插件ID，未找到返回 null
     */
    public static String findPluginIdByExtension(GameEnhancementExtension extension,
                                                   PluginManager pluginManager) {
        String className = extension.getClass().getName();

        // 先查缓存
        String cached = extensionClassCache.get(className);
        if (cached != null) {
            return cached;
        }

        // 遍历查找
        for (PluginWrapper plugin : pluginManager.getPlugins()) {
            List<GameEnhancementExtension> exts =
                    pluginManager.getExtensions(GameEnhancementExtension.class, plugin.getPluginId());
            for (GameEnhancementExtension ext : exts) {
                if (ext.getClass().getName().equals(className)) {
                    String pluginId = plugin.getPluginId();
                    extensionClassCache.put(className, pluginId);
                    return pluginId;
                }
            }
        }
        return null;
    }

    /**
     * 根据游戏编码查找插件ID。
     *
     * @param gameCode 游戏编码
     * @param pluginManager 插件管理器
     * @return 插件ID，未找到返回 null
     */
    public static String findPluginIdByGameCode(String gameCode, PluginManager pluginManager) {
        // 先查缓存
        String cached = gameCodeCache.get(gameCode);
        if (cached != null && pluginManager.getPlugin(cached) != null) {
            return cached;
        }

        List<GameEnhancementExtension> extensions = pluginManager.getExtensions(GameEnhancementExtension.class);
        for (GameEnhancementExtension ext : extensions) {
            if (gameCode.equals(ext.getGameCode())) {
                String pluginId = findPluginIdByExtension(ext, pluginManager);
                if (pluginId != null) {
                    gameCodeCache.put(gameCode, pluginId);
                }
                return pluginId;
            }
        }
        return null;
    }

    /**
     * 清除缓存（插件卸载/重载时调用）。
     *
     * @param pluginId 被卸载的插件ID
     */
    public static void invalidateCache(String pluginId) {
        gameCodeCache.entrySet().removeIf(e -> e.getValue().equals(pluginId));
        extensionClassCache.entrySet().removeIf(e -> e.getValue().equals(pluginId));
    }

    // ==================== 配置加载 ====================

    /**
     * 从插件 JAR 中加载 plugin.properties。
     *
     * @param wrapper 插件包装器
     * @return Properties 对象（不为 null，可能为空）
     */
    public static Properties loadPluginProperties(PluginWrapper wrapper) {
        Properties props = new Properties();
        try (InputStream is = wrapper.getPluginClassLoader().getResourceAsStream("plugin.properties")) {
            if (is != null) {
                props.load(is);
                log.debug("已加载插件 [{}] 的 plugin.properties", wrapper.getPluginId());
            }
        } catch (Exception e) {
            log.warn("无法读取插件 [{}] 的 plugin.properties", wrapper.getPluginId(), e);
        }
        return props;
    }

    // ==================== 资源读取 ====================

    /**
     * 从插件 ClassLoader 读取资源文件内容。
     *
     * @param wrapper      插件包装器
     * @param resourcePath 资源路径（相对于 ClassLoader 根）
     * @return 文件内容字符串，不存在返回 null
     */
    public static String readResourceAsString(PluginWrapper wrapper, String resourcePath) {
        try (InputStream is = wrapper.getPluginClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取插件 [{}] 资源失败: {}", wrapper.getPluginId(), resourcePath, e);
            return null;
        }
    }

    // ==================== 路径处理 ====================

    /**
     * 去掉路径中的 /api 前缀（因为主应用 context-path 已经是 /api）。
     *
     * @param path 原始路径
     * @return 去掉 /api 前缀后的路径
     */
    public static String stripApiPrefix(String path) {
        if (path.startsWith("/api/")) {
            return path.substring(4);
        }
        if (path.equals("/api")) {
            return "";
        }
        return path;
    }

    /**
     * 构建插件静态资源的完整 URL 前缀。
     *
     * @param gameCode 游戏编码
     * @return URL 前缀，如 "/api/pf4j/plugin/l4d2/ui"
     */
    public static String buildResourceUrlPrefix(String gameCode) {
        return "/api/pf4j/plugin/" + gameCode + "/ui";
    }

    /**
     * 构建插件前端入口的完整 URL。
     *
     * @param gameCode      游戏编码
     * @param frontendEntry 前端入口文件
     * @return 完整 URL
     */
    public static String buildFrontendEntryUrl(String gameCode, String frontendEntry) {
        return buildResourceUrlPrefix(gameCode) + "/" + frontendEntry;
    }

    /**
     * 构建插件 API 基础路径。
     *
     * @param gameCode 游戏编码
     * @return API 基础路径，如 "/api/plugin/l4d2"
     */
    public static String buildApiBasePath(String gameCode) {
        return "/api/plugin/" + gameCode;
    }
}
