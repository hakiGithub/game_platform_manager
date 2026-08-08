package com.gameplatform.plugin.exception;

/**
 * 插件配置异常
 * 当插件配置缺失、格式错误或校验失败时抛出。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public class PluginConfigException extends PluginException {

    public PluginConfigException(String pluginId, String message) {
        super(pluginId, message);
    }

    public PluginConfigException(String pluginId, String message, Throwable cause) {
        super(pluginId, message, cause);
    }
}
