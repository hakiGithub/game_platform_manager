package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 重启偏好扩展资源（ADR-0020）。
 * <p>
 * PLUGIN_ISOLATED 策略，存入 {@code ext_{pluginId}} 表。插件全局仅一条记录，
 * name 固定为 {@code restart-config}，spec 保存重启偏好（RCON 优先、容器名、
 * 自定义命令、命令超时、启用开关），启动后首次访问时加载，保存即落库。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)
public class RestartConfigResource extends AbstractExtension<RestartConfigSpec> {
}
