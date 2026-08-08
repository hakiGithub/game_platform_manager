package com.gameplatform.plugin.exception;

/**
 * 插件路径冲突异常
 * 当两个插件的控制器注册了相同的 URL 路径时抛出。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
public class PluginPathConflictException extends PluginException {

    private final String conflictPath;
    private final String existingPluginId;

    public PluginPathConflictException(String pluginId, String conflictPath, String existingPluginId) {
        super(pluginId, "路径冲突: " + conflictPath + " 已被插件 " + existingPluginId + " 注册");
        this.conflictPath = conflictPath;
        this.existingPluginId = existingPluginId;
    }

    public String getConflictPath() {
        return conflictPath;
    }

    public String getExistingPluginId() {
        return existingPluginId;
    }
}
