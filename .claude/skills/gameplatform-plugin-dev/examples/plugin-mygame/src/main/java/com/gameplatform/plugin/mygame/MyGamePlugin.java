package com.gameplatform.plugin.mygame;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * PF4J 插件入口类。
 * <p>
 * 仅承载生命周期日志，业务逻辑全部由 Spring 子容器中的 Extension / Controller / Service 完成。
 * <p>
 * 必须在 plugin.properties 的 plugin.class 与 pom.xml 的 Plugin-Class Manifest 中保持一致。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public class MyGamePlugin extends Plugin {

    public MyGamePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("[MyGame] 插件启动 - id={}, version={}",
                wrapper.getPluginId(),
                wrapper.getDescriptor().getVersion());
    }

    @Override
    public void stop() {
        log.info("[MyGame] 插件停止 - id={}", wrapper.getPluginId());
    }

    @Override
    public void delete() {
        log.info("[MyGame] 插件卸载 - id={}", wrapper.getPluginId());
        super.delete();
    }
}
