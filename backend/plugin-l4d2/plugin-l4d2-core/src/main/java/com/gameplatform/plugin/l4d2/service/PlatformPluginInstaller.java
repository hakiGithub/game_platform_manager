package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 平台插件（SourceMod + Metamod）内置安装器（薄包装）。
 *
 * <p>历史版本：直接从 classpath 读取平台插件 ZIP 安装。现已经重构为委托给
 * {@link BuiltinPluginInstaller}，以便与"内置插件市场"共用同一套清单与 ZIP 资源。
 *
 * <p>保留此类是为了：
 * <ol>
 *   <li>兼容 {@link PresetService#apply} 中的 {@code platformPluginInstaller.install(instanceId)} 调用</li>
 *   <li>保留 {@link #PLATFORM_PLUGIN_NAME} 常量供预设匹配</li>
 * </ol>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPluginInstaller {

    /** 平台插件名（与 preset.yaml 中 platform.linux 一致，与 builtin-plugins.yaml 中的 id 一致）。 */
    public static final String PLATFORM_PLUGIN_NAME = "1.11插件平台linux版";

    private final BuiltinPluginInstaller builtinPluginInstaller;

    /**
     * 检查平台插件是否已安装到实例的 plugins_store 目录。
     *
     * @param instanceId 实例 ID
     * @return true 表示已安装
     */
    public boolean isInstalled(Long instanceId) {
        return builtinPluginInstaller.isInstalled(instanceId, PLATFORM_PLUGIN_NAME);
    }

    /**
     * 从内置 classpath 资源安装平台插件到实例容器（委托给 BuiltinPluginInstaller）。
     *
     * @param instanceId 实例 ID
     * @return 安装结果描述
     */
    public String install(Long instanceId) {
        if (isInstalled(instanceId)) {
            log.info("平台插件已安装，跳过: instanceId={}", instanceId);
            return "平台插件已安装，无需重复安装";
        }
        try {
            String msg = builtinPluginInstaller.install(instanceId, PLATFORM_PLUGIN_NAME);
            log.info("内置平台插件安装完成: instanceId={}", instanceId);
            return msg;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("内置平台插件安装失败 instanceId={}", instanceId, e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "平台插件安装失败: " + e.getMessage(), e);
        }
    }

    /**
     * 暴露 InstanceFileService 给上层（用于校验路径可达性）。
     */
    public InstanceFileService getInstanceFileService() {
        return builtinPluginInstaller.getInstanceFileService();
    }
}
