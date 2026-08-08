package com.gameplatform.plugin.constant;

/**
 * 插件框架常量定义
 * 统一管理所有路径前缀、配置键名、默认值等常量，避免硬编码。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public final class PluginConstants {

    private PluginConstants() {
    }

    // ==================== 路径前缀 ====================

    /** 插件框架 REST API 前缀（主应用 context-path 为 /api，框架控制器映射在 /pf4j 下） */
    public static final String FRAMEWORK_API_PREFIX = "/pf4j";

    /** 插件静态资源 URL 前缀（完整路径含 /api） */
    public static final String PLUGIN_RESOURCE_URL_PREFIX = "/api/pf4j/plugin";

    /** 插件静态资源映射路径（不含 /api，由 context-path 补全） */
    public static final String PLUGIN_RESOURCE_MAPPING = "/pf4j/plugin";

    /** 插件 API 基础路径模板（插件控制器使用） */
    public static final String PLUGIN_API_BASE_TEMPLATE = "/api/plugin/{gameCode}";

    /** 插件前端入口 URL 模板 */
    public static final String PLUGIN_FRONTEND_ENTRY_TEMPLATE = "/api/pf4j/plugin/{gameCode}/ui/{entry}";

    /** 插件 Thymeleaf 模板前缀 */
    public static final String PLUGIN_TEMPLATE_PREFIX = "plugin/";

    // ==================== 配置键名 ====================

    /** plugin.properties 中的键名 */
    public static final String PROP_PLUGIN_ID = "plugin.id";
    public static final String PROP_PLUGIN_CLASS = "plugin.class";
    public static final String PROP_PLUGIN_VERSION = "plugin.version";
    public static final String PROP_GAME_CODE = "plugin.gameCode";
    public static final String PROP_BASE_PACKAGE = "plugin.basePackage";

    // ==================== 默认值 ====================

    /** 默认前端入口文件 */
    public static final String DEFAULT_FRONTEND_ENTRY = "index.html";

    /** 默认图标路径 */
    public static final String DEFAULT_ICON = "assets/icon.png";

    /** 静态资源缓存天数 */
    public static final int STATIC_RESOURCE_CACHE_DAYS = 7;

    // ==================== 数据库 ====================

    /** 插件信息表名 */
    public static final String TABLE_PLUGIN_INFO = "plugin_info";

    // ==================== Spring Bean 名称 ====================

    /** 插件数据访问 Bean 名称 */
    public static final String BEAN_PLUGIN_DATA_ACCESS = "pluginDataAccess";

    /** 插件上下文持有者 Bean 名称 */
    public static final String BEAN_PLUGIN_CONTEXT_HOLDER = "pluginContextHolder";
}
