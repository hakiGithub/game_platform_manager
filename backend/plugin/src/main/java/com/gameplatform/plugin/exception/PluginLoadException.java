package com.gameplatform.plugin.exception;

/**
 * 插件加载异常
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public class PluginLoadException extends PluginException {

    public PluginLoadException(String pluginId, String message) {
        super(pluginId, message);
    }

    public PluginLoadException(String pluginId, String message, Throwable cause) {
        super(pluginId, message, cause);
    }
}
