package com.gameplatform.plugin.listener;

import com.gameplatform.entity.PluginInfo;
import com.gameplatform.mapper.PluginInfoMapper;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 插件生命周期钩子
 * 处理插件启动、停止等生命周期事件
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
public class PluginLifecycleHook {

    private final PluginInfoMapper pluginInfoMapper;
    private PluginManager pluginManager;

    public PluginLifecycleHook(PluginInfoMapper pluginInfoMapper) {
        this.pluginInfoMapper = pluginInfoMapper;
    }

    /**
     * 延迟注入 PluginManager，打破循环依赖
     */
    @Autowired
    public void setPluginManager(@Lazy PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 插件创建时的钩子
     */
    public void onPluginCreate(PluginWrapper plugin) {
        String pluginId = plugin.getPluginId();
        log.info("插件创建: {}", pluginId);
        
        // 更新运行时状态
        updateRuntimeState(pluginId, "CREATED");
    }

    /**
     * 插件启动时的钩子
     */
    public void onPluginStart(PluginWrapper plugin) {
        String pluginId = plugin.getPluginId();
        log.info("执行插件启动钩子: {}", pluginId);

        try {
            // 更新数据库中的插件状态和运行时状态
            updatePluginStatus(pluginId, 1);
            updateRuntimeState(pluginId, "STARTED");
            updateStartTime(pluginId);

            log.info("插件启动钩子执行完成: {}", pluginId);
        } catch (Exception e) {
            log.error("插件启动钩子执行失败: {}", pluginId, e);
        }
    }

    /**
     * 插件停止时的钩子
     */
    public void onPluginStop(PluginWrapper plugin) {
        String pluginId = plugin.getPluginId();
        log.info("执行插件停止钩子: {}", pluginId);

        try {
            // 更新数据库中的插件状态和运行时状态
            updatePluginStatus(pluginId, 0);
            updateRuntimeState(pluginId, "STOPPED");

            log.info("插件停止钩子执行完成: {}", pluginId);
        } catch (Exception e) {
            log.error("插件停止钩子执行失败: {}", pluginId, e);
        }
    }

    /**
     * 插件禁用时��钩子
     */
    public void onPluginDisable(PluginWrapper plugin) {
        String pluginId = plugin.getPluginId();
        log.info("插件禁用: {}", pluginId);
        updatePluginStatus(pluginId, 0);
        updateRuntimeState(pluginId, "DISABLED");
    }

    /**
     * 插件解析时的钩子
     */
    public void onPluginResolve(PluginWrapper plugin) {
        String pluginId = plugin.getPluginId();
        log.info("插件解析完成: {}", pluginId);
        updateRuntimeState(pluginId, "RESOLVED");
        updateLoadTime(pluginId);
    }

    /**
     * 更新插件状态
     */
    private void updatePluginStatus(String pluginId, int status) {
        try {
            PluginInfo pluginInfo = pluginInfoMapper.selectByPluginId(pluginId);
            if (pluginInfo != null) {
                pluginInfoMapper.updateStatus(pluginInfo.getId(), status);
                log.debug("更新插件状态: {} -> {}", pluginId, status);
            } else {
                // 插件信息不存在，创建新记录
                createPluginInfo(pluginId, status);
            }
        } catch (Exception e) {
            log.error("更新插件状态失败: {}", pluginId, e);
        }
    }

    /**
     * 更新运行时状态
     */
    private void updateRuntimeState(String pluginId, String runtimeState) {
        try {
            PluginInfo pluginInfo = pluginInfoMapper.selectByPluginId(pluginId);
            if (pluginInfo != null) {
                pluginInfoMapper.updateRuntimeState(pluginInfo.getId(), runtimeState);
                log.debug("更新插件运行时状态: {} -> {}", pluginId, runtimeState);
            }
        } catch (Exception e) {
            log.error("更新插件运行时状态失败: {}", pluginId, e);
        }
    }

    /**
     * 更新启动时间
     */
    private void updateStartTime(String pluginId) {
        try {
            PluginInfo pluginInfo = pluginInfoMapper.selectByPluginId(pluginId);
            if (pluginInfo != null) {
                pluginInfoMapper.updateStartTime(pluginInfo.getId(), 
                        LocalDateTime.now().toString());
                log.debug("更新插件启动时间: {}", pluginId);
            }
        } catch (Exception e) {
            log.error("更新插件启动时间失败: {}", pluginId, e);
        }
    }

    /**
     * 更新加载时间
     */
    private void updateLoadTime(String pluginId) {
        try {
            PluginInfo pluginInfo = pluginInfoMapper.selectByPluginId(pluginId);
            if (pluginInfo != null) {
                pluginInfoMapper.updateLoadTime(pluginInfo.getId(), 
                        LocalDateTime.now().toString());
                log.debug("更新插件加载时间: {}", pluginId);
            }
        } catch (Exception e) {
            log.error("更新插件加载时间失败: {}", pluginId, e);
        }
    }

    /**
     * 创建插件信息记录
     */
    private void createPluginInfo(String pluginId, int status) {
        if (pluginManager == null) {
            log.warn("PluginManager 尚未初始化，跳过创建插件信息: {}", pluginId);
            return;
        }
        
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            return;
        }

        // 获取游戏编码（如果存在扩展点）
        String gameCode = getGameCodeFromExtension(pluginId);

        PluginInfo pluginInfo = new PluginInfo();
        pluginInfo.setPluginId(pluginId);
        pluginInfo.setPluginName(plugin.getDescriptor().getPluginDescription());
        pluginInfo.setVersion(plugin.getDescriptor().getVersion());
        pluginInfo.setAuthor(plugin.getDescriptor().getProvider());
        pluginInfo.setStatus(status);
        pluginInfo.setRuntimeState("CREATED");
        pluginInfo.setGameCode(gameCode);
        pluginInfo.setCreateTime(LocalDateTime.now());
        pluginInfo.setUpdateTime(LocalDateTime.now());

        pluginInfoMapper.insert(pluginInfo);
        log.info("创建插件信息记录: {}", pluginId);
    }

    /**
     * 从扩展点获取游戏编码
     */
    private String getGameCodeFromExtension(String pluginId) {
        if (pluginManager == null) {
            return null;
        }
        
        try {
            return pluginManager.getExtensions(GameEnhancementExtension.class, pluginId)
                    .stream()
                    .findFirst()
                    .map(GameEnhancementExtension::getGameCode)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("获取游戏编码失败: {}", pluginId);
            return null;
        }
    }

    // ==================== 实例生命周期钩子 ====================

    /**
     * 执行实例创建钩子：通知 gameCode 匹配的插件扩展点。
     *
     * @param instanceId 实例ID
     * @param gameCode   实例所属游戏编码
     * @param config     实例配置信息
     */
    public void executeInstanceCreateHooks(Long instanceId, String gameCode, Map<String, Object> config) {
        executeInstanceHooks(gameCode, ext -> ext.onInstanceCreate(instanceId, config),
                "创建", instanceId);
    }

    /**
     * 执行实例配置更新钩子（ADR-0009）：通知 gameCode 匹配的插件扩展点。
     * 实参为 update 后的完整新 configInfo；每次更新都触发，插件异常不影响实例更新。
     *
     * @param instanceId 实例ID
     * @param gameCode   实例所属游戏编码
     * @param config     更新后的完整 configInfo
     */
    public void executeInstanceUpdateHooks(Long instanceId, String gameCode, Map<String, Object> config) {
        executeInstanceHooks(gameCode, ext -> ext.onInstanceUpdate(instanceId, config),
                "更新", instanceId);
    }

    /**
     * 执行实例启动钩子：通知 gameCode 匹配的插件扩展点。
     *
     * @param instanceId 实例ID
     * @param gameCode   实例所属游戏编码
     */
    public void executeInstanceStartHooks(Long instanceId, String gameCode) {
        executeInstanceHooks(gameCode, ext -> ext.onInstanceStart(instanceId),
                "启动", instanceId);
    }

    /**
     * 执行实例停止钩子：通知 gameCode 匹配的插件扩展点。
     *
     * @param instanceId 实例ID
     * @param gameCode   实例所属游戏编码
     */
    public void executeInstanceStopHooks(Long instanceId, String gameCode) {
        executeInstanceHooks(gameCode, ext -> ext.onInstanceStop(instanceId),
                "停止", instanceId);
    }

    /**
     * 执行实例删除钩子：通知 gameCode 匹配的插件扩展点。
     *
     * @param instanceId 实例ID
     * @param gameCode   实例所属游戏编码
     */
    public void executeInstanceDeleteHooks(Long instanceId, String gameCode) {
        executeInstanceHooks(gameCode, ext -> ext.onInstanceDelete(instanceId),
                "删除", instanceId);
    }

    /**
     * 遍历所有已加载的扩展点，对 gameCode 匹配的扩展点执行指定动作。
     *
     * @param gameCode   游戏编码
     * @param action     对扩展点执行的动作
     * @param actionName 动作名称（用于日志）
     * @param instanceId 实例ID（用于日志）
     */
    private void executeInstanceHooks(String gameCode,
                                       java.util.function.Consumer<GameEnhancementExtension> action,
                                       String actionName, Long instanceId) {
        if (pluginManager == null) {
            return;
        }
        try {
            pluginManager.getExtensions(GameEnhancementExtension.class).stream()
                    .filter(ext -> gameCode != null && gameCode.equals(ext.getGameCode()))
                    .forEach(ext -> {
                        try {
                            action.accept(ext);
                            log.debug("实例{}钩子执行成功: plugin={}, instance={}",
                                    actionName, ext.getGameCode(), instanceId);
                        } catch (Exception e) {
                            log.error("实例{}钩子执行失败: plugin={}, instance={}",
                                    actionName, ext.getGameCode(), instanceId, e);
                        }
                    });
        } catch (Exception e) {
            log.error("获取扩展点失败", e);
        }
    }

}
