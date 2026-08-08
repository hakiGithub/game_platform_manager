package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 已启用插件扩展资源（用于前端列表快速查询）。
 *
 * <p>与 .enabled_plugins.yaml 双写，yaml 为事实来源。
 * MODEL_ISOLATED 策略，物理表 ext_plugin_l4d2_enabledpluginresource。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class EnabledPluginResource extends AbstractExtension<EnabledPluginSpec> {
}
