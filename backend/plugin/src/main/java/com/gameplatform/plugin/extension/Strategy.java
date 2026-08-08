package com.gameplatform.plugin.extension;

/**
 * 扩展资源存储策略。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum Strategy {

    /**
     * 全局共享大表 {@code extensions}，所有插件所有模型混居。
     */
    SHARED,

    /**
     * 插件级隔离表 {@code ext_{pluginId}}，该插件所有模型混居，插件间物理隔离。
     */
    PLUGIN_ISOLATED,

    /**
     * 模型级隔离表 {@code ext_{pluginId}_{kind}}，仅该插件该模型，隔离粒度最细。
     */
    MODEL_ISOLATED
}
