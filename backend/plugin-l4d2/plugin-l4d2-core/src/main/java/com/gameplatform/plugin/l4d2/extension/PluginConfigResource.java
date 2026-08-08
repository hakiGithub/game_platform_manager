package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 SourceMod 插件配置扩展资源。
 * <p>
 * MODEL_ISOLATED 策略，物理表 {@code ext_plugin_l4d2_pluginconfigresource}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class PluginConfigResource extends AbstractExtension<PluginConfigSpec> {
}
