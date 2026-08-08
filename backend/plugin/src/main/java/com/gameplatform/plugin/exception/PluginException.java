package com.gameplatform.plugin.exception;

/**
 * 插件框架基础异常
 * 所有插件相关的异常均继承自此基类。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public class PluginException extends RuntimeException {

    private final String pluginId;

    public PluginException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public PluginException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
