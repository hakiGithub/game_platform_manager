package com.gameplatform.plugin.l4d2;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * L4D2 游戏服务器增强插件主类
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public class L4D2Plugin extends Plugin {

    public L4D2Plugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("L4D2 插件启动中...");
        log.info("插件ID: {}", wrapper.getPluginId());
        log.info("插件版本: {}", wrapper.getDescriptor().getVersion());
        log.info("L4D2 插件启动完成");
    }

    @Override
    public void stop() {
        log.info("L4D2 插件停止中...");
        log.info("L4D2 插件已停止");
    }

    @Override
    public void delete() {
        log.info("L4D2 插件卸载中...");
        super.delete();
    }
}
