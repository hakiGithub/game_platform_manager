package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 远端插件仓库配置扩展资源。
 * <p>
 * PLUGIN_ISOLATED 策略，存入 {@code ext_plugin_l4d2} 表。插件全局仅一条记录，
 * name 固定为 {@code store-config}，spec 保存仓库地址/分支/代理/令牌（对齐
 * ADR-0020 {@code RestartConfigResource} 单例模式），启动后首次访问时加载，保存即落库。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)
public class StoreConfigResource extends AbstractExtension<StoreConfigSpec> {
}
