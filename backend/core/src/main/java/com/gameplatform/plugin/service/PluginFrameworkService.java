package com.gameplatform.plugin.service;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.vo.PluginManifestVO;
import com.gameplatform.plugin.vo.PluginStatusVO;
import org.pf4j.PluginWrapper;

import java.util.List;
import java.util.Optional;

/**
 * 插件框架服务接口
 * 提供插件管理相关的核心功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface PluginFrameworkService {

    /**
     * 获取所有已加载的插件
     *
     * @return 插件包装器列表
     */
    List<PluginWrapper> getAllPlugins();

    /**
     * 根据插件ID获取插件
     *
     * @param pluginId 插件ID
     * @return 插件包装器
     */
    Optional<PluginWrapper> getPlugin(String pluginId);

    /**
     * 获取插件状态
     *
     * @param pluginId 插件ID
     * @return 插件状态VO
     */
    PluginStatusVO getPluginStatus(String pluginId);

    /**
     * 获取所有插件状态列表
     *
     * @return 插件状态列表
     */
    List<PluginStatusVO> getAllPluginStatus();

    /**
     * 根据游戏编码获取插件清单
     *
     * @param gameCode 游戏编码
     * @return 插件清单VO
     */
    PluginManifestVO getManifestByGameCode(String gameCode);

    /**
     * 根据插件ID获取插件清单
     *
     * @param pluginId 插件ID
     * @return 插件清单VO
     */
    PluginManifestVO getManifestByPluginId(String pluginId);

    /**
     * 启动插件
     *
     * @param pluginId 插件ID
     * @return 是否成功
     */
    boolean startPlugin(String pluginId);

    /**
     * 停止插件
     *
     * @param pluginId 插件ID
     * @return 是否成功
     */
    boolean stopPlugin(String pluginId);

    /**
     * 卸载插件
     *
     * @param pluginId 插件ID
     * @return 是否成功
     */
    boolean unloadPlugin(String pluginId);

    /**
     * 卸载插件。
     *
     * @param pluginId  插件ID
     * @param purgeTasks 是否物理删除插件 source 的任务记录与日志
     *                   （热部署/重载传 false 保留历史）
     */
    boolean unloadPlugin(String pluginId, boolean purgeTasks);

    /**
     * 重新加载插件
     *
     * @param pluginId 插件ID
     * @return 是否成功
     */
    boolean reloadPlugin(String pluginId);

    /**
     * 加载新插件
     *
     * @param pluginPath 插件路径
     * @return 插件ID
     */
    String loadPlugin(String pluginPath);

    /**
     * 获取插件资源文件内容
     *
     * @param pluginId 插件ID
     * @param resourcePath 资源路径（相对于ui目录）
     * @return 资源内容字节数组
     */
    byte[] getPluginResource(String pluginId, String resourcePath);

    /**
     * 获取插件的Content-Type
     *
     * @param resourcePath 资源路径
     * @return Content-Type
     */
    String getContentType(String resourcePath);

    /**
     * 检查插件是否存在
     *
     * @param pluginId 插件ID
     * @return 是否存在
     */
    boolean pluginExists(String pluginId);

    /**
     * 根据游戏编码获取插件ID
     *
     * @param gameCode 游戏编码
     * @return 插件ID
     */
    Optional<String> getPluginIdByGameCode(String gameCode);

    /**
     * 根据游戏编码获取游戏增强扩展点实例（用于读取插件声明的部署方式等）。
     *
     * @param gameCode 游戏编码
     * @return 扩展点实例；插件不存在或未加载时返回 null
     */
    GameEnhancementExtension getExtensionByGameCode(String gameCode);

}
