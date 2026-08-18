package com.gameplatform.plugin.extension;

import com.gameplatform.plugin.constant.PluginConstants;
import com.gameplatform.plugin.context.PluginContext;
import org.pf4j.ExtensionPoint;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 游戏增强扩展点接口
 * <p>
 * 插件通过实现此接口来提供游戏特定的功能增强。
 * 每个插件应恰好提供一个实现此接口的 {@code @Extension} 类。
 *
 * <h3>实现规范</h3>
 * <ul>
 *   <li>{@code getGameCode()} 必须全局唯一，建议使用小写英文 + 连字符（如 {@code "l4d2"}）</li>
 *   <li>{@code getVersion()} 必须遵循语义化版本规范（如 {@code "1.0.0"}）</li>
 *   <li>控制器路径必须以 {@code /api/plugin/{gameCode}/} 开头</li>
 *   <li>持久化数据使用 {@link ExtensionModel} 注解声明存储策略，通过 {@code ExtensionClient} 访问</li>
 *   <li>前端资源放在 JAR 内 {@code ui/} 目录下</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public interface GameEnhancementExtension extends ExtensionPoint {

    // ==================== 元数据（必须实现） ====================

    /**
     * 获取游戏编码（全局唯一标识）
     * <p>
     * 命名规范：小写英文 + 连字符，如 "l4d2", "minecraft", "palworld"
     *
     * @return 游戏编码
     */
    String getGameCode();

    /**
     * 获取游戏名称（用于前端展示）
     *
     * @return 游戏名称，如 "求生之路2"
     */
    String getGameName();

    /**
     * 获取插件版本（遵循语义化版本规范）
     *
     * @return 版本号，如 "1.0.0"
     */
    String getVersion();

    /**
     * 获取插件描述
     *
     * @return 插件描述信息
     */
    String getDescription();

    // ==================== 清单与配置 ====================

    /**
     * 获取插件清单信息（用于前端动态加载插件 UI 和 API）。
     * <p>
     * 子类可覆盖此方法提供自定义的清单数据。
     * 默认实现包含基本的元数据信息和前端入口。
     *
     * @return 清单信息 Map
     */
    default Map<String, Object> getManifest() {
        return Map.of(
            "gameCode", getGameCode(),
            "gameName", getGameName(),
            "version", getVersion(),
            "description", getDescription(),
            "frontendEntry", PluginConstants.PLUGIN_RESOURCE_URL_PREFIX
                + "/" + getGameCode() + "/ui/" + getFrontendEntry()
        );
    }

    /**
     * 获取插件配置字段定义。
     * <p>
     * 插件可声明自己的配置项，框架会在前端自动渲染配置表单。
     * 返回空列表表示插件无需用户配置。
     *
     * @return 配置字段定义列表
     */
    default List<PluginConfigField> getConfigFields() {
        return Collections.emptyList();
    }

    // ==================== 生命周期钩子 ====================

    /**
     * 插件加载后的初始化钩子（在 Spring 子容器创建之后调用）。
     * <p>
     * 适用于：初始化缓存、注册回调、预热连接等。
     *
     * @param context 插件运行时上下文
     */
    default void onLoad(PluginContext context) {
        // 默认空实现
    }

    /**
     * 插件卸载前的清理钩子（在 Spring 子容器关闭之前调用）。
     * <p>
     * 适用于：释放资源、关闭连接、清理临时文件等。
     */
    default void onUnload() {
        // 默认空实现
    }

    /**
     * 实例创建时的钩子。
     *
     * @param instanceId 实例ID
     * @param config     实例配置信息
     */
    default void onInstanceCreate(Long instanceId, Map<String, Object> config) {
    }

    /**
     * 实例配置更新后的钩子（ADR-0009）。
     * <p>
     * 实参为 update 后的<b>完整新 configInfo</b>（与 onInstanceCreate 对称）。
     * 每次更新都触发，不做平台侧 configInfo diff——配置是否真变由插件自行比对。
     * 典型消费：实例 configInfo 变更（改密码/端口）→ 主动失效对应连接池。
     *
     * @param instanceId 实例ID
     * @param config     更新后的完整 configInfo
     */
    default void onInstanceUpdate(Long instanceId, Map<String, Object> config) {
    }

    /**
     * 实例启动前的钩子。
     *
     * @param instanceId 实例ID
     */
    default void onInstanceStart(Long instanceId) {
    }

    /**
     * 实例停止后的钩子。
     *
     * @param instanceId 实例ID
     */
    default void onInstanceStop(Long instanceId) {
    }

    /**
     * 实例删除时的钩子。
     *
     * @param instanceId 实例ID
     */
    default void onInstanceDelete(Long instanceId) {
    }

    /**
     * 插件加载失败时的错误处理钩子。
     *
     * @param context 插件上下文（可能为 null）
     * @param error   异常信息
     */
    default void onLoadError(PluginContext context, Throwable error) {
    }

    // ==================== 前端资源 ====================

    /**
     * 获取插件提供的菜单清单（ADR-0001）。
     * <p>
     * 主应用 PluginFrameworkServiceImpl 调用此方法拼装 manifest.frontend.menus，
     * 不再硬编码任何插件菜单。插件应返回完整的菜单列表（包括"通用"菜单如
     * 仪表盘、备份、日志等），主应用不预置任何默认菜单。
     *
     * <h3>实现规范</h3>
     * <ul>
     *   <li>同插件内 {@link PluginMenuDeclaration#getPath()} 必须唯一，重复抛 IllegalStateException</li>
     *   <li>{@link PluginMenuDeclaration#getRequireInstance()} 默认 true；
     *       纯资源页（如地图中心）显式设 false</li>
     *   <li>菜单 path 需与子应用前端路由（如 plugin-l4d2/frontend/src/router/index.ts）一致</li>
     *   <li>返回空列表表示插件不提供任何菜单</li>
     * </ul>
     *
     * @return 菜单声明列表，不可为 null
     * @since 2.1.0
     */
    default List<PluginMenuDeclaration> getMenus() {
        return Collections.emptyList();
    }

    /**
     * 获取插件图标路径（相对于插件 JAR 包内的 ui 目录）。
     *
     * @return 图标路径，如 "assets/icon.png"
     */
    default String getIcon() {
        return PluginConstants.DEFAULT_ICON;
    }

    /**
     * 获取前端入口文件路径（相对于插件 JAR 包内的 ui 目录）。
     *
     * @return 入口文件路径，如 "index.html"
     */
    default String getFrontendEntry() {
        return PluginConstants.DEFAULT_FRONTEND_ENTRY;
    }

    // ==================== Spring 与数据库 ====================

    /**
     * 获取插件基础包名，用于 Spring 组件扫描。
     * 默认返回此扩展点实现类所在的包名。
     */
    default String getBasePackage() {
        return this.getClass().getPackage().getName();
    }

    // ==================== 插件依赖 ====================

    /**
     * 获取此插件依赖的其他插件 gameCode 列表。
     * 框架会确保依赖插件先加载。
     * 返回空列表表示无依赖。
     *
     * @return 依赖的 gameCode 列表
     */
    default List<String> getDependencies() {
        return Collections.emptyList();
    }

    // ==================== 部署方式扩展 ====================

    /**
     * 声明本游戏插件的部署方式配置模板（v3.6.0）。
     *
     * <p>主应用读取游戏部署配置时合并插件声明：
     * <ul>
     *   <li>部署选项：声明的部署类型自动加入该游戏部署向导的选项
     *       （仅限主应用已支持的部署类型 code，未知 code 忽略并告警）</li>
     *   <li>配置覆盖：同一部署类型下，插件声明整节替换主应用游戏元数据
     *       （games/*.yml）的同名配置节，插件优先</li>
     * </ul>
     * 执行仍走主应用部署适配器（DeployAdapter 体系），插件仅提供配置。
     *
     * @return 部署方式声明列表；未声明返回空列表
     */
    default List<DeployConfigDeclaration> getDeployConfigs() {
        return Collections.emptyList();
    }
}
